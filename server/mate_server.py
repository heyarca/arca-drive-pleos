#!/usr/bin/env python3
"""메이트 (Mate) job server — the user's own compute ("내 클라우드 컴퓨터").

The car only gives orders and approves. The work runs here:
  demo    : this process on the developer's Mac Studio (emulator reaches it at http://10.0.2.2:8790)
  service : the same file on a per-subscriber cloud VM (infra/provision_user_vm.sh)

Jobs (one agent, three kinds of request):
  proposal : meeting transcript -> a real static proposal web page
  edit     : one spoken edit on that page -> before.html / after.html
  bugfix   : a real bug in a tiny web app (server/demo_shop) -> agent edits the code,
             the test goes FAIL -> PASS, preview URL; "deploy" copies the preview to
             data/deployed only after an approval (in the car: only while stopped; or from the phone).

The agent is `claude -p` (MATE_CLAUDE). If it fails or times out, a small rule-based fallback
runs instead and the job records engine="rules-fallback" (never silently).

Stdlib only. API:
  POST /v1/jobs                {kind, request, requestId, transcript?, parent?}  -> job   (dedupe on requestId)
  GET  /v1/jobs/<id>           -> job
  POST /v1/jobs/<id>/approve   {source: "car"|"phone", speedKmh?, gear?}         -> job   (car approvals only while stopped)
  GET  /m/<id>                 mobile page for the same job (status, result, approve)
  GET  /work/<id>/...          job files (result pages, preview)
  GET  /deployed/...           what has been deployed
"""
import difflib
import html
import http.server
import json
import os
import re
import shutil
import socket
import subprocess
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse, unquote

ROOT = Path(__file__).resolve().parent
DATA = Path(os.environ.get("MATE_DATA", ROOT / "data"))
WORK = DATA / "work"
DEPLOYED = DATA / "deployed"
AUDIT = DATA / "audit.log"
PORT = int(os.environ.get("MATE_PORT", "8790"))
BIND = os.environ.get("MATE_BIND", "0.0.0.0")
TOKEN = os.environ.get("MATE_TOKEN", "")
_sub = Path.home() / ".local/bin/claude-sub"
CLAUDE = os.environ.get("MATE_CLAUDE", str(_sub) if _sub.exists() else "claude")
MODEL = os.environ.get("MATE_MODEL", "sonnet")
AGENT_TIMEOUT = int(os.environ.get("MATE_AGENT_TIMEOUT", "75"))


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


PUBLIC = os.environ.get("MATE_PUBLIC_BASE", f"http://{lan_ip()}:{PORT}")   # what a phone can open
JOBS = {}
IDEM = {}
LOCK = threading.RLock()


def now():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def audit(event, **kw):
    DATA.mkdir(parents=True, exist_ok=True)
    line = json.dumps({"at": now(), "event": event, **kw}, ensure_ascii=False)
    with open(AUDIT, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    print("AUDIT", line, flush=True)


def save(job):
    d = WORK / job["id"]
    d.mkdir(parents=True, exist_ok=True)
    (d / "job.json").write_text(json.dumps(job, ensure_ascii=False, indent=1), encoding="utf-8")


def update(job, **kw):
    with LOCK:
        job.update(kw)
        job["updatedAt"] = now()
        if "statusLine" in kw or "state" in kw:
            job["log"].append(f'{time.strftime("%H:%M:%S")} {job["state"]} {job.get("step", 0)}/{job.get("steps", 0)} {job.get("statusLine", "")}')
            audit("job_state", job=job["id"], kind=job["kind"], state=job["state"], step=job.get("step"), line=job.get("statusLine"))
        save(job)


def public(job):
    j = {k: v for k, v in job.items() if k not in ("content", "transcript")}
    j["mobileUrl"] = f'{PUBLIC}/m/{job["id"]}'
    return j


def load_jobs():
    if not WORK.exists():
        return
    for p in WORK.glob("*/job.json"):
        try:
            j = json.loads(p.read_text(encoding="utf-8"))
            if j.get("state") in ("QUEUED", "WORKING"):
                j["state"] = "FAILED"
                j["statusLine"] = "서버가 재시작되어 중단됨"
            JOBS[j["id"]] = j
            if j.get("requestId"):
                IDEM[j["requestId"]] = j["id"]
        except (OSError, ValueError):
            pass


# ---------------------------------------------------------------- the agent
def run_agent(prompt, cwd, tools=None, timeout=AGENT_TIMEOUT):
    """One `claude -p` run on this machine. Returns (ok, text, seconds)."""
    cmd = [CLAUDE, "-p", "--model", MODEL]
    if tools:
        cmd += ["--allowedTools", tools, "--permission-mode", "acceptEdits"]
    t0 = time.time()
    try:
        r = subprocess.run(cmd, input=prompt, capture_output=True, text=True, cwd=str(cwd), timeout=timeout)
        return r.returncode == 0 and bool(r.stdout.strip()), (r.stdout or r.stderr).strip(), time.time() - t0
    except (subprocess.TimeoutExpired, OSError) as e:
        return False, f"{type(e).__name__}: {e}", time.time() - t0


def extract_json(text):
    a, b = text.find("{"), text.rfind("}")
    if a < 0 or b <= a:
        raise ValueError("no JSON in agent output")
    return json.loads(text[a:b + 1])


SECTIONS = {"needs": "고객 요구", "proposal": "제안 내용", "timeline": "일정", "pricing": "견적", "next": "다음 단계"}

PROPOSAL_PROMPT = """너는 1인 B2B 컨설턴트의 업무 에이전트다. 아래 미팅 전사만 근거로 고객에게 보낼 제안 페이지 초안의 내용을 만든다.
규칙:
- 전사에 없는 숫자·가격·일정은 지어내지 않는다. 확정되지 않은 것은 "미확정"이라고 쓰고 unconfirmed 에 적는다.
- needs 의 각 항목에는 근거가 된 전사 한 줄을 evidence 에 그대로 인용한다(시각 포함).
- 한국어, 간결하게. 각 detail 은 한 문장.
- 다른 말 없이 JSON 하나만 출력한다.

형식:
{"client":"고객사 이름","title":"제안 제목(20자 이내)","subtitle":"한 줄 요약",
 "needs":[{"title":"","detail":"","evidence":""}],
 "proposal":[{"title":"","detail":""}],
 "timeline":[{"when":"","what":""}],
 "pricing":[{"item":"","price":"","note":""}],
 "unconfirmed":[""],
 "next":[""],
 "order":["needs","proposal","timeline","pricing","next"]}
needs 3개, proposal 2~3개, timeline 3~4개, pricing 1~3개, next 2~3개.

운전자의 요청: __REQUEST__

미팅 전사:
__TRANSCRIPT__
"""

EDIT_PROMPT = """너는 제안 페이지 초안을 고치는 업무 에이전트다. 아래 JSON 이 현재 페이지 내용이다.
운전자가 차에서 말로 한 수정 요청(음성 인식 결과라 오탈자가 있을 수 있다)을 정확히 한 번 반영한다.
규칙:
- 요청한 것만 바꾼다. 나머지 내용은 그대로 둔다.
- 섹션 순서는 order 배열로 바꾼다 (needs=고객 요구, proposal=제안 내용, timeline=일정, pricing=견적, next=다음 단계).
- 전사에 없는 숫자·가격은 지어내지 않는다.
- "changed" 에 무엇을 바꿨는지 한 문장(한국어, 25자 이내)으로 적는다.
- 다른 말 없이 같은 형식의 JSON 하나만 출력한다 (changed 필드 추가).

수정 요청: __REQUEST__

현재 내용:
__CONTENT__
"""

BUGFIX_PROMPT = """버그 리포트: 이 폴더의 데모 웹앱에서 "결제하기" 버튼을 눌러도 아무 일도 일어나지 않는다.
운전자의 요청: __REQUEST__
할 일: index.html 과 app.js 를 읽고 원인을 찾아 최소한의 수정으로 고쳐라. test.js 는 수정하지 마라.
고친 뒤 `node test.js` 를 실행해 PASS 를 확인하라.
마지막 줄에 원인과 수정 내용을 한국어 한 문장으로 요약하라.
"""


def esc(s):
    return html.escape(str(s if s is not None else ""))


def render_proposal(c, badge="초안 · 보내기 전 확인 필요", changed=""):
    def needs():
        return "".join(f'<div class="card"><h3>{esc(n.get("title"))}</h3><p>{esc(n.get("detail"))}</p>'
                       + (f'<p class="ev">근거 · {esc(n.get("evidence"))}</p>' if n.get("evidence") else "") + "</div>"
                       for n in c.get("needs", []))

    def proposal():
        return "".join(f'<div class="card"><span class="num">{i + 1}</span><h3>{esc(p.get("title"))}</h3><p>{esc(p.get("detail"))}</p></div>'
                       for i, p in enumerate(c.get("proposal", [])))

    def timeline():
        return '<div class="card tl">' + "".join(f'<div class="row"><b>{esc(t.get("when"))}</b><span>{esc(t.get("what"))}</span></div>'
                                                 for t in c.get("timeline", [])) + "</div>"

    def pricing():
        return '<div class="card tl">' + "".join(
            f'<div class="row"><span>{esc(p.get("item"))}<small>{esc(p.get("note"))}</small></span><b>{esc(p.get("price"))}</b></div>'
            for p in c.get("pricing", [])) + "</div>"

    def nxt():
        return '<div class="card"><ul>' + "".join(f"<li>{esc(x)}</li>" for x in c.get("next", [])) + "</ul></div>"

    body = {"needs": needs, "proposal": proposal, "timeline": timeline, "pricing": pricing, "next": nxt}
    order = [k for k in c.get("order", []) if k in body] or list(body)
    order += [k for k in body if k not in order]
    secs = "".join(f'<section id="{k}"><h2>{SECTIONS[k]}</h2><div class="grid {k}">{body[k]()}</div></section>' for k in order)
    unconf = c.get("unconfirmed") or []
    warn = ('<section><div class="warn"><b>확인이 필요한 항목</b><ul>' + "".join(f"<li>{esc(u)}</li>" for u in unconf)
            + "</ul></div></section>") if unconf else ""
    chg = f'<div class="chg">수정 반영 · {esc(changed)}</div>' if changed else ""
    return f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(c.get("client"))} 제안 · {esc(c.get("title"))}</title>
<style>
@font-face {{ font-family:"Asta Sans"; src:url("/static/asta_sans.ttf"); font-weight:300 800; }}
*{{box-sizing:border-box}} body{{margin:0;background:#F7F8FA;color:#131417;font-family:"Asta Sans",sans-serif;-webkit-text-size-adjust:100%}}
.hero{{padding:56px 48px 44px;background:linear-gradient(135deg,#3F4B81 0%,#8896D3 62%,#B6C1F3 100%);color:#fff}}
.badge{{display:inline-block;padding:8px 18px;border-radius:99px;background:rgba(255,255,255,.2);font-weight:600;font-size:20px}}
.chg{{display:inline-block;margin-left:10px;padding:8px 18px;border-radius:99px;background:#fff;color:#3F4B81;font-weight:700;font-size:20px}}
h1{{font-size:58px;line-height:1.15;margin:22px 0 12px;letter-spacing:-1.5px}} .sub{{font-size:28px;opacity:.92;margin:0}}
.client{{font-size:24px;font-weight:700;opacity:.9;margin-top:26px}}
main{{max-width:1180px;margin:0 auto;padding:12px 40px 80px}}
section{{margin-top:44px}} h2{{font-size:34px;margin:0 0 18px;letter-spacing:-.6px}}
.grid{{display:grid;gap:18px;grid-template-columns:repeat(auto-fit,minmax(300px,1fr))}} .grid.timeline,.grid.pricing,.grid.next{{grid-template-columns:1fr}}
.card{{background:#fff;border-radius:28px;padding:30px 32px;box-shadow:0 2px 14px rgba(19,20,23,.05)}}
.card h3{{margin:0 0 10px;font-size:28px;letter-spacing:-.4px}} .card p{{margin:0;font-size:23px;line-height:1.5;color:#44464E}}
.card .ev{{margin-top:14px;font-size:18px;color:#8896D3}} .num{{display:inline-grid;place-items:center;width:40px;height:40px;border-radius:50%;background:#EDEEF2;color:#3F4B81;font-weight:800;margin-bottom:14px}}
.tl .row{{display:flex;justify-content:space-between;gap:24px;padding:18px 0;border-bottom:1px solid #EDEEF2;font-size:24px}} .tl .row:last-child{{border:0}}
.tl b{{color:#3F4B81;white-space:nowrap}} small{{display:block;color:#8A8C93;font-size:18px;margin-top:4px}}
ul{{margin:0;padding-left:26px;font-size:24px;line-height:1.7}}
.warn{{background:#FFF4EF;border-radius:24px;padding:26px 32px;color:#B4381A}} .warn b{{font-size:24px}} .warn ul{{font-size:22px;margin-top:8px}}
footer{{margin-top:48px;color:#8A8C93;font-size:18px}}
</style></head><body>
<div class="hero"><span class="badge">{esc(badge)}</span>{chg}
<div class="client">{esc(c.get("client"))} 귀중</div><h1>{esc(c.get("title"))}</h1><p class="sub">{esc(c.get("subtitle"))}</p></div>
<main>{secs}{warn}
<footer>메이트가 미팅 기록(샘플 데이터)으로 만든 초안입니다. 가격·일정은 보내기 전에 직접 확인하세요.</footer></main>
</body></html>"""


def rules_proposal(transcript):
    """Fallback without a model: pick lines by simple cues. Labelled engine=rules-fallback."""
    said = [(m.group(1), m.group(2).strip(), m.group(3).strip()) for m in
            re.finditer(r"^\[(\d\d:\d\d)\]\s*([^:]+):\s*(.+)$", transcript, re.M)]
    client = next((re.search(r"\(([^)]+)\)", w).group(1) for _, w, _ in said if re.search(r"\(([^)]+사)\)", w)), "고객사")
    needs = [{"title": s[:18], "detail": s, "evidence": f"[{t}] {w}: {s}"} for t, w, s in said
             if client in w and re.search(r"필요|해야|원해|목표|려고", s)][:3]
    mine = [s for _, w, s in said if client not in w]
    return {"client": client, "title": f"{client} 제안 초안", "subtitle": "미팅 기록에서 뽑은 요구와 제안",
            "needs": needs, "proposal": [{"title": s[:18], "detail": s} for s in mine if "제안" in s][:3] or [{"title": "제안", "detail": "미확정"}],
            "timeline": [{"when": m.group(0), "what": s} for _, _, s in said for m in [re.search(r"\d+월\s*\S+( 주)?|이번 주 \S요일|다음 주", s)] if m][:4],
            "pricing": [{"item": "전체", "price": "미확정", "note": s} for _, _, s in said if "예산" in s][:1],
            "unconfirmed": ["가격과 범위는 미확정"], "next": [s for s in mine if re.search(r"까지|보내", s)][:3],
            "order": ["needs", "proposal", "timeline", "pricing", "next"]}


def rules_edit(content, request):
    c = json.loads(json.dumps(content))
    for key, words in {"timeline": "일정|스케줄", "pricing": "가격|견적|비용", "needs": "요구|니즈", "proposal": "제안 내용", "next": "다음"}.items():
        if re.search(words, request) and re.search(r"맨 ?위|위로|앞으로|먼저", request):
            c["order"] = [key] + [k for k in c.get("order", list(SECTIONS)) if k != key]
            c["changed"] = f"{SECTIONS[key]}을(를) 맨 위로"
            return c
    c["changed"] = "요청을 해석하지 못해 변경 없음"
    return c


# ---------------------------------------------------------------- job runners
def run_proposal(job):
    d = WORK / job["id"]
    client = "A사"
    update(job, state="WORKING", step=1, steps=3, statusLine=f"{client} 제안 페이지 1/3 미팅 기록 읽는 중")
    transcript = job.get("transcript") or ""
    (d / "transcript.txt").write_text(transcript, encoding="utf-8")
    time.sleep(1.2)
    update(job, step=2, statusLine=f"{client} 제안 페이지 2/3 작성 중")
    ok, out, sec = run_agent(PROPOSAL_PROMPT.replace("__REQUEST__", job["request"]).replace("__TRANSCRIPT__", transcript), d)
    engine = f"claude -p ({MODEL}) {sec:.1f}s"
    try:
        if not ok:
            raise ValueError(out[:200])
        content = extract_json(out)
    except ValueError as e:
        audit("agent_fallback", job=job["id"], reason=str(e)[:200])
        content, engine = rules_proposal(transcript), "rules-fallback"
    update(job, step=3, statusLine=f"{client} 제안 페이지 3/3 페이지 만드는 중", engine=engine)
    (d / "content.json").write_text(json.dumps(content, ensure_ascii=False, indent=1), encoding="utf-8")
    (d / "index.html").write_text(render_proposal(content), encoding="utf-8")
    summary = [f'요구 {len(content.get("needs", []))} · 제안 {len(content.get("proposal", []))} · 일정 {len(content.get("timeline", []))}단계']
    summary += [f"확인 필요: {u}" for u in (content.get("unconfirmed") or [])[:2]]
    update(job, state="READY", content=content, title=f'{content.get("client", client)} 제안 페이지', resultUrl=f'/work/{job["id"]}/index.html',
           summary=summary, statusLine=f'{content.get("client", client)} 제안 페이지 준비됨')


def run_edit(job):
    d = WORK / job["id"]
    parent = JOBS.get(job.get("parent") or "")
    if not parent or not parent.get("content"):
        update(job, state="FAILED", statusLine="고칠 페이지를 찾지 못했어요")
        return
    client = parent["content"].get("client", "A사")
    update(job, state="WORKING", step=1, steps=2, statusLine=f"{client} 제안 페이지 수정 1/2 반영 중")
    before = parent["content"]
    ok, out, sec = run_agent(EDIT_PROMPT.replace("__REQUEST__", job["request"]).replace("__CONTENT__", json.dumps(before, ensure_ascii=False)), d)
    engine = f"claude -p ({MODEL}) {sec:.1f}s"
    try:
        if not ok:
            raise ValueError(out[:200])
        after = extract_json(out)
    except ValueError as e:
        audit("agent_fallback", job=job["id"], reason=str(e)[:200])
        after, engine = rules_edit(before, job["request"]), "rules-fallback"
    changed = after.pop("changed", "") or "요청 반영"
    update(job, step=2, statusLine=f"{client} 제안 페이지 수정 2/2 페이지 만드는 중", engine=engine)
    (d / "before.html").write_text(render_proposal(before, badge="수정 전"), encoding="utf-8")
    (d / "after.html").write_text(render_proposal(after, badge="수정 후", changed=changed), encoding="utf-8")
    (d / "content.json").write_text(json.dumps(after, ensure_ascii=False, indent=1), encoding="utf-8")
    update(job, state="READY", content=after, title=f"{client} 제안 페이지 · 수정", resultUrl=f'/work/{job["id"]}/after.html',
           beforeUrl=f'/work/{job["id"]}/before.html', summary=[f'요청: “{job["request"]}”', f"반영: {changed}"],
           statusLine=f"{client} 제안 페이지 수정됨")


def node_test(repo):
    try:
        r = subprocess.run(["node", "test.js"], capture_output=True, text=True, cwd=str(repo), timeout=20)
        return r.returncode == 0, (r.stdout + r.stderr).strip()
    except (subprocess.TimeoutExpired, OSError) as e:
        return False, f"{type(e).__name__}: {e}"


def run_bugfix(job):
    d = WORK / job["id"]
    repo, orig = d / "repo", d / "orig"
    update(job, state="WORKING", step=1, steps=3, statusLine="결제 버튼 수정 1/3 버그 재현 중")
    for dst in (repo, orig):
        shutil.copytree(ROOT / "demo_shop", dst, dirs_exist_ok=True)
    ok0, out0 = node_test(repo)
    job["log"].append(f"test before: {out0}")
    update(job, step=2, statusLine="결제 버튼 수정 2/3 코드 고치는 중")
    ok, out, sec = run_agent(BUGFIX_PROMPT.replace("__REQUEST__", job["request"]), repo, tools="Read,Edit,Bash(node test.js)", timeout=max(AGENT_TIMEOUT, 100))
    engine = f"claude -p ({MODEL}) + Read/Edit/Bash {sec:.1f}s"
    ok1, out1 = node_test(repo)
    if not ok1:   # the agent did not get it passing: labelled fallback (a one-line id fix), never silent
        audit("agent_fallback", job=job["id"], reason=(out or "")[:200])
        js = (repo / "app.js").read_text(encoding="utf-8").replace("getElementById('payButton')", "getElementById('pay-button')")
        (repo / "app.js").write_text(js, encoding="utf-8")
        engine = "rules-fallback"
        ok1, out1 = node_test(repo)
    job["log"].append(f"test after: {out1}")
    diff = []
    for name in ("index.html", "app.js", "style.css", "test.js"):
        a = (orig / name).read_text(encoding="utf-8").splitlines()
        b = (repo / name).read_text(encoding="utf-8").splitlines()
        diff += list(difflib.unified_diff(a, b, f"a/{name}", f"b/{name}", lineterm="", n=1))
    (d / "fix.diff").write_text("\n".join(diff) + "\n", encoding="utf-8")
    minus = next((l[1:].strip() for l in diff if l.startswith("-") and not l.startswith("---")), "")
    plus = next((l[1:].strip() for l in diff if l.startswith("+") and not l.startswith("+++")), "")
    if not ok1:
        update(job, state="FAILED", engine=engine, statusLine="결제 버튼 수정 실패 · 테스트가 통과하지 않음")
        return
    update(job, step=3, statusLine="결제 버튼 수정 3/3 미리보기 올리는 중", engine=engine)
    time.sleep(1.0)
    note = (out or "").strip().splitlines()[-1][:120] if engine != "rules-fallback" and out else ""
    summary = [f"테스트: {'FAIL' if not ok0 else 'PASS'} → PASS", f"− {minus[:70]}", f"+ {plus[:70]}"] + ([note] if note else [])
    update(job, state="READY", title="결제 버튼 수정", resultUrl=f'/work/{job["id"]}/repo/index.html', diffUrl=f'/work/{job["id"]}/fix.diff',
           summary=summary, deploy={"state": "WAITING_APPROVAL"}, statusLine="결제 버튼 수정 · 미리보기 준비됨")


RUNNERS = {"proposal": run_proposal, "edit": run_edit, "bugfix": run_bugfix}


def create_job(body):
    kind = body.get("kind")
    if kind not in RUNNERS:
        return None, "unknown kind"
    rid = str(body.get("requestId") or "")
    with LOCK:
        if rid and rid in IDEM:   # Gleo retried the same action: hand back the same job, do not start another
            job = JOBS[IDEM[rid]]
            audit("job_dedupe", job=job["id"], requestId=rid)
            return dict(public(job), deduped=True), None
        jid = uuid.uuid4().hex[:12]
        job = {"id": jid, "kind": kind, "request": str(body.get("request") or ""), "requestId": rid, "parent": body.get("parent"),
               "transcript": body.get("transcript"), "title": {"proposal": "제안 페이지", "edit": "제안 페이지 수정", "bugfix": "결제 버튼 수정"}[kind],
               "state": "QUEUED", "step": 0, "steps": 0, "statusLine": "접수됨", "createdAt": now(), "updatedAt": now(), "log": [],
               "compute": os.environ.get("MATE_COMPUTE", f"own-machine:{socket.gethostname()}")}
        JOBS[jid] = job
        if rid:
            IDEM[rid] = jid
        save(job)
    audit("job_created", job=jid, kind=kind, request=job["request"], requestId=rid, source=body.get("source", "car"))

    def work():
        try:
            RUNNERS[kind](job)
        except Exception as e:   # noqa: BLE001 - a job must never take the server down
            update(job, state="FAILED", statusLine=f"실패: {type(e).__name__}", error=str(e)[:300])
    threading.Thread(target=work, daemon=True).start()
    return public(job), None


def approve(job, body):
    source = body.get("source", "car")
    gear = body.get("gear", "")
    if job["kind"] != "bugfix" or job["state"] not in ("READY", "DEPLOYED"):
        return 409, {"error": "not_deployable", "message": "배포할 수 있는 작업이 아니에요."}
    if job["state"] == "DEPLOYED":
        return 200, dict(public(job), deduped=True)
    speed = body.get("speedKmh")
    if source == "car" and (speed is None or float(speed) > 0):   # in the car: only while stopped (speed 0). The phone is outside the car.
        audit("deploy_rejected", job=job["id"], source=source, gear=gear, speedKmh=speed)
        return 409, {"error": "car_moving", "message": "차가 움직이는 동안에는 배포를 승인할 수 없어요. 정차해 있을 때만 승인합니다."}
    dst = DEPLOYED / "shop"
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(WORK / job["id"] / "repo", dst)
    update(job, state="DEPLOYED", deploy={"state": "DEPLOYED", "by": source, "gear": gear, "speedKmh": speed, "at": now(), "url": "/deployed/shop/index.html"},
           statusLine="결제 버튼 수정 · 배포됨")
    audit("deploy_approved", job=job["id"], source=source, gear=gear, speedKmh=speed, path=str(dst))
    return 200, public(job)


MOBILE = """<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>메이트 · 이어 보기</title><style>
@font-face{font-family:"Asta Sans";src:url("/static/asta_sans.ttf");font-weight:300 800}
*{box-sizing:border-box}body{margin:0;background:#F7F8FA;color:#131417;font-family:"Asta Sans",-apple-system,sans-serif}
header{padding:22px 20px 8px}header small{color:#3F4B81;font-weight:700}h1{font-size:26px;margin:6px 0 0;letter-spacing:-.5px}
.card{background:#fff;border-radius:22px;margin:14px 16px;padding:18px 20px;box-shadow:0 2px 12px rgba(19,20,23,.05)}
.line{font-size:19px;font-weight:700}.bar{height:8px;border-radius:9px;background:#EDEEF2;margin-top:12px;overflow:hidden}
.bar i{display:block;height:100%;background:linear-gradient(90deg,#3F4B81,#9BA8E2);transition:width .5s}
ul{margin:8px 0 0;padding-left:20px;color:#44464E;line-height:1.6}.meta{color:#8A8C93;font-size:13px;margin-top:10px}
iframe{width:100%;height:52vh;border:0;border-radius:16px;background:#F7F8FA}
a.open{display:block;text-align:center;padding:14px;border-radius:14px;background:#EDEEF2;color:#131417;font-weight:700;text-decoration:none;margin-top:10px}
button{width:100%;padding:18px;border:0;border-radius:16px;background:#44464E;color:#fff;font:700 18px "Asta Sans",sans-serif}
button:disabled{background:#EDEEF2;color:#8A8C93}.ok{color:#027A41;font-weight:700}
</style></head><body>
<header><small>메이트 for Gleo · 폰으로 이어 보기</small><h1 id="title">불러오는 중…</h1></header>
<div class="card"><div class="line" id="line"></div><div class="bar"><i id="bar" style="width:0"></i></div>
<ul id="sum"></ul><div class="meta" id="meta"></div></div>
<div class="card" id="dep" hidden><button id="btn">배포 승인</button><div class="meta" id="depmeta">차에서 내린 뒤에는 폰에서 이어서 승인해요. 승인 전에는 아무것도 배포되지 않습니다.</div></div>
<div class="card" id="res" hidden><iframe id="frame"></iframe><a class="open" id="open" target="_blank">새 창으로 열기</a></div>
<script>
const id="__ID__";let shown="";
async function tick(){
  try{
    const j=await (await fetch("/v1/jobs/"+id)).json();
    title.textContent=j.title; line.textContent=j.statusLine;
    bar.style.width=(j.state=="READY"||j.state=="DEPLOYED"?100:(j.steps?Math.round(100*(j.step-0.5)/j.steps):5))+"%";
    sum.innerHTML=(j.summary||[]).slice(0,3).map(s=>"<li>"+s.replace(/[<&]/g,c=>({"<":"&lt;","&":"&amp;"}[c]))+"</li>").join("");
    meta.textContent="작업 ID "+j.id+" · "+(j.engine||"")+" · "+j.compute;
    const url=(j.deploy&&j.deploy.url)||j.resultUrl;
    if(url&&url!=shown){shown=url;res.hidden=false;frame.src=url;open.href=url;}
    if(j.kind=="bugfix"&&(j.state=="READY"||j.state=="DEPLOYED")){dep.hidden=false;
      if(j.state=="DEPLOYED"){btn.disabled=true;btn.textContent="배포됨";depmeta.innerHTML='<span class="ok">배포 완료</span> · '+j.deploy.by+' 승인 · '+j.deploy.at;}}
  }catch(e){line.textContent="연결을 확인하는 중…";}
}
btn.onclick=async()=>{btn.disabled=true;await fetch("/v1/jobs/"+id+"/approve",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({source:"phone"})});tick();};
tick();setInterval(tick,1500);
</script></body></html>"""

TYPES = {".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
         ".json": "application/json; charset=utf-8", ".ttf": "font/ttf", ".txt": "text/plain; charset=utf-8", ".diff": "text/plain; charset=utf-8",
         ".png": "image/png"}


class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send(self, code, body, ctype="application/json; charset=utf-8", cache=False):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False)
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=86400" if cache else "no-store")
        self.end_headers()
        self.wfile.write(body)

    def authed(self):
        return not TOKEN or self.headers.get("Authorization", "") == f"Bearer {TOKEN}"

    def static(self, base, rel):
        p = (base / rel).resolve()
        if base.resolve() not in p.parents or not p.is_file() or p.name in ("job.json", "transcript.txt", "content.json"):
            return self.send(404, {"error": "not_found"})
        self.send(200, p.read_bytes(), TYPES.get(p.suffix, "application/octet-stream"), cache=p.suffix == ".ttf")

    def do_GET(self):
        path = unquote(urlparse(self.path).path)
        if path == "/health":
            return self.send(200, {"ok": True, "jobs": len(JOBS), "compute": os.environ.get("MATE_COMPUTE", f"own-machine:{socket.gethostname()}"), "public": PUBLIC})
        m = re.fullmatch(r"/v1/jobs/([0-9a-f]{12})", path)
        if m:   # the 12-hex job id is the capability for reading / approving that one job (phone link)
            job = JOBS.get(m.group(1))
            return self.send(200, public(job)) if job else self.send(404, {"error": "not_found"})
        m = re.fullmatch(r"/m/([0-9a-f]{12})", path)
        if m and m.group(1) in JOBS:
            return self.send(200, MOBILE.replace("__ID__", m.group(1)), "text/html; charset=utf-8")
        for prefix, base in (("/work/", WORK), ("/deployed/", DEPLOYED), ("/static/", ROOT / "static")):
            if path.startswith(prefix):
                return self.static(base, path[len(prefix):])
        self.send(404, {"error": "not_found"})

    def do_POST(self):
        path = urlparse(self.path).path
        n = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self.send(400, {"error": "bad_json"})
        if path == "/v1/jobs":
            if not self.authed():
                return self.send(401, {"error": "unauthorized"})
            job, err = create_job(body)
            return self.send(400, {"error": err}) if err else self.send(200, job)
        m = re.fullmatch(r"/v1/jobs/([0-9a-f]{12})/approve", path)
        if m:
            job = JOBS.get(m.group(1))
            if not job:
                return self.send(404, {"error": "not_found"})
            code, out = approve(job, body)
            return self.send(code, out)
        self.send(404, {"error": "not_found"})


if __name__ == "__main__":
    WORK.mkdir(parents=True, exist_ok=True)
    DEPLOYED.mkdir(parents=True, exist_ok=True)
    load_jobs()
    audit("server_start", port=PORT, agent=CLAUDE, model=MODEL, public=PUBLIC)
    http.server.ThreadingHTTPServer((BIND, PORT), H).serve_forever()
