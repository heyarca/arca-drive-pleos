# infra — 구독자 1명 = VM 1대 ("내 클라우드 컴퓨터")

메이트는 차 안에서 음성으로 일을 맡기면, 에이전트가 **차가 아니라 사용자 본인의 컴퓨터**에서 일하는 구조입니다.
서비스 단계에서는 구독자마다 전용 AWS VM을 한 대씩 만들어 `server/mate_server.py`를 띄웁니다. VM·보안그룹·토큰·디스크가 사용자별로 분리됩니다.

> **공모전 데모에서는 이 스크립트를 실행하지 않았고 유료 클라우드 자원을 만들지 않았습니다. 데모의 '내 컴퓨터'는 Mac Studio입니다.**

## 컴퓨트 모드

| 모드 | 내 컴퓨터 | 앱이 호출하는 주소 |
|---|---|---|
| 데모 | 내 Mac Studio | `http://10.0.2.2:8790` (에뮬레이터 → 호스트) |
| 서비스 | 사용자별 AWS VM | `https://<user>.vm.mate.example` (도메인은 자리표시자) |

두 모드 모두 같은 서버, 같은 API(`/v1/jobs`, `Authorization: Bearer <token>`)입니다.

## 드라이런 (기본값, 아무것도 만들지 않음)

```bash
infra/provision_user_vm.sh demo-user --plan basic              # 계획 출력 + (자격증명 있으면) run-instances --dry-run
infra/provision_user_vm.sh demo-user --plan pro --offline      # aws 호출 없이 명령만 출력
infra/provision_user_vm.sh demo-user --destroy                 # 삭제 대상만 출력
```

## `--apply`가 하는 일

1. 사용자 전용 보안그룹 생성 (인바운드 443만, SSH 없음)
2. 사용자 토큰 생성 → SSM SecureString `/mate/users/<user-id>/token`
3. Ubuntu 24.04 arm64 인스턴스 실행 — 기본 t4g.small/30GB, 프로 t4g.large/60GB, 팀 t4g.xlarge/120GB (gp3 암호화), 태그 `mate:user`, `mate:plan`
4. `cloud-init.yaml`: `mate` 계정, python3·node·git·caddy·claude CLI 설치, 부팅 시 SSM에서 사용자 토큰을 `/etc/mate/env`(0600)로 가져오고 `mate-server.service` 시작, Caddy가 443 → 127.0.0.1:8790 (자동 TLS). 끝나면 인스턴스 ID와 엔드포인트 출력

사전 준비(스크립트가 만들지 않음): 인스턴스 프로파일 `mate-vm-role`, DNS 존, `MATE_REPO_URL`.

**AI는 내 구독 그대로.** VM이 뜬 뒤 구독자가 자기 AI 구독(Claude / ChatGPT)으로 공식 에이전트 CLI에 한 번 로그인합니다(예: `claude login`). 메이트는 모델 사용량을 되팔지 않으며, 이 경로에 모델 API 키는 없습니다. 메이트는 차에서 그 AI에게 일을 시키는 작업 환경입니다.
`--destroy --apply`: 태그로 인스턴스를 찾아 종료(디스크 함께 삭제)하고 SSM 파라미터와 보안그룹을 지웁니다 — 구독 해지 시 데이터 삭제.

## 차량 앱을 VM에 연결

앱은 컴퓨트 엔드포인트와 토큰을 설정에서 읽습니다. 개발·데모 중에는 다음으로 바꿉니다.

```bash
adb shell am broadcast -a app.mate.gleo.DEMO_ENDPOINT --es url https://<user>.vm.mate.example --es token <token>
```

## 보안 메모

- 인바운드 443만 개방, SSH 없음(운영 접속은 SSM Session Manager). 사용자별 토큰(SSM에서만 읽음). 모델 API 키 없음(구독자 본인 로그인). IMDSv2 강제.
- 루트 볼륨 gp3 암호화, 해지 시 `--destroy`로 VM·디스크·파라미터 삭제.
- 서버는 `mate` 계정으로 실행 (NoNewPrivileges, ProtectSystem=strict, 쓰기는 `/var/lib/mate`만). 감사 로그: `/var/lib/mate/audit.log`.
- 미해결: `mate-vm-role`의 SSM 권한은 사용자별로 좁혀야 함(사용자별 역할 또는 ABAC). 중지/재시작 시 공인 IP가 바뀌므로 Elastic IP나 DNS 갱신 필요.

## 가격·원가 (전부 추정, 검증 필요)

| 플랜 | VM | 구독가(추정) |
|---|---|---|
| 베이직 | 온디맨드 VM (일이 있을 때만 가동) | 월 9,900원 |
| 프로 | 상시 VM · 더 큰 사양 | 월 19,900원 |
| 팀/법인 | 1인당 | 월 39,000원 |

- VM 원가는 사용자 1명당 월 $3~8로 추정합니다. AI 모델 비용은 구독자 본인의 AI 구독에서 나가므로 메이트 원가에 없습니다.
- 스크립트의 인스턴스 사양(t4g.small / large / xlarge)은 자리표시자입니다. 온디맨드 상시 가동 단가로는 위 가격이 맞지 않으므로, 미사용 시 중지(아직 구현하지 않음)와 약정·스팟이 전제입니다.
- 인프라 로드맵(계획): 퍼블릭 클라우드 임대 → 약정·스팟 → Arca Labs 자체 데이터센터, 그룹 계열 클라우드와의 협력 가능성.
