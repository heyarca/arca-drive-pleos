package app.mate.gleo

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the car does with the driver's words. The car never does the work itself: a spoken request becomes
 * a job on the driver's own compute (MateClient), the car shows one status line while it moves, and opens
 * the result or takes an approval only while it is stopped. The older on-device flows (departure briefing,
 * voice reply, arrival notice) are kept below.
 */
class CompanionBrain(private val ctx: android.content.Context, private val store: PromiseStore, private val pleos: PleosBridge) {

    /** Orb THINKING while a delegated job is being worked out (summary, route maths). */
    val thinking = MutableStateFlow(false)
    val reel = ReelMaker(ctx)
    val playing = MutableStateFlow(false)
    /** Work the driver handed off while moving. Shown as cards only when parked. */
    val tasks = MutableStateFlow<List<TaskCard>>(emptyList())
    data class TaskCard(val id: String, val kind: String, val title: String, val body: String, val state: String, val file: String = "", val at: Long = System.currentTimeMillis())

    /** Today's calendar as the car knows it (demo data, like the promises). */
    private val nextSchedule = Triple("14:00", "수원 고객사 미팅", 92)   // time, title, round-trip km from the destination
    private val chargerName = "양재 미팅룸 지하 2층 충전소"
    private val chargerLat = 37.46895; private val chargerLon = 127.03915
    private var batteryOffered = false

    /** One short line for the driving screen (never more than ~24 chars of meaning). */
    val line = MutableStateFlow("")
    /** Cards that only appear when parked: things that need one tap. */
    val cards = MutableStateFlow<List<Card>>(emptyList())
    val log = MutableStateFlow<List<String>>(emptyList())

    data class Card(val id: String, val title: String, val body: String, val action: String)

    private var awaiting: Pending? = null
    private sealed class Pending {
        data class ArrivalSend(val promiseId: String) : Pending()
        data class Reply(val promiseId: String, val text: String) : Pending()
        object ChargeStop : Pending()
    }

    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    /** Update in place (cards never jump around); new work is appended. */
    private fun upsertTask(t: TaskCard) {
        Log.i(PleosBridge.TAG, "TASK ${t.id} -> ${t.state}")
        tasks.value = if (tasks.value.any { it.id == t.id }) tasks.value.map { if (it.id == t.id) t else it } else tasks.value + t
    }
    /** Speak after the current line has finished (no talking over itself). */
    private fun speakSoon(display: String, voice: String = display, then: () -> Unit = {}) {
        if (pleos.speaking.value || awaiting != null || thinking.value) { h.postDelayed({ speakSoon(display, voice, then) }, 1200); return }
        speak(display, voice); then()
    }

    init {
        refreshCards()
        pleos.onGear = { g -> onGear(g) }
        pleos.onUtterance = { onUtterance(it) }
        pleos.onNearDestination = { onNear() }
        pleos.onArrived = { name -> onArrived(name) }
        pleos.onSoc = { soc -> onSoc(soc) }
        pleos.onMoving = { m -> onMoving(m) }
    }

    // ---------- Mate: work handed to the driver's own compute ----------
    val client = MateClient(ctx)
    /** Jobs handed off from this car, in the order they were asked for. */
    val jobs = MutableStateFlow<List<Job>>(emptyList())
    /** The job whose result is open on screen. Only ever non-null while the car is stopped. */
    val viewing = MutableStateFlow<String?>(null)
    /** Scroll position of each result page, kept when the page closes because the car moved. */
    val scroll = HashMap<String, Int>()
    private val io = java.util.concurrent.Executors.newCachedThreadPool()
    private val polling = HashSet<String>()

    private fun upsertJob(j: Job) { jobs.value = if (jobs.value.any { it.id == j.id }) jobs.value.map { if (it.id == j.id) j else it } else jobs.value + j }
    private fun lastPage() = jobs.value.lastOrNull { (it.kind == "proposal" || it.kind == "edit") && it.state == "READY" }

    /** Which kind of work a spoken request is. Narrow on purpose: three kinds, anything else is not a job. */
    fun kindOf(t: String): String? = when {
        Regex("(결제|버튼|버그)").containsMatchIn(t) && Regex("(고쳐|고치|수정|잡아)").containsMatchIn(t) -> "bugfix"
        Regex("(제안|페이지)").containsMatchIn(t) && Regex("(만들|작성|써 ?줘)").containsMatchIn(t) -> "proposal"
        lastPage() != null && Regex("(올려|내려|바꿔|수정|고쳐|빼 ?줘|추가|넣어|맨 ?위|줄여)").containsMatchIn(t) -> "edit"
        else -> null
    }

    /** DELEGATE_TASK. requestId is the idempotency key: a retry returns the same job and starts nothing new. */
    fun delegate(kind: String, request: String, requestId: String = "car-" + java.util.UUID.randomUUID().toString().take(8), onJob: (Job?, String) -> Unit = { _, _ -> }) {
        // the same kind of work is already running: a repeat (Gleo retry, driver saying it twice) is the same job, not a new one
        jobs.value.lastOrNull { it.kind == kind && it.active }?.let { j ->
            Log.i(PleosBridge.TAG, "JOB ${j.id} $kind already running -> same job returned (requestId=$requestId)")
            onJob(j.copy(deduped = true), ""); return
        }
        val parent = if (kind == "edit") lastPage()?.id else null
        if (kind == "edit" && parent == null) { speak("수정할 페이지가 아직 없어요."); onJob(null, "There is no page to edit yet."); return }
        val transcript = if (kind == "proposal") runCatching { ctx.assets.open(MEETING).bufferedReader().use { it.readText() } }.getOrNull() else null
        thinking.value = true
        io.execute {
            val r = runCatching { client.createJob(kind, request, requestId, transcript, parent) }
            h.post {
                thinking.value = false
                val j = r.getOrNull()
                if (j == null) {
                    Log.w(PleosBridge.TAG, "JOB create failed ${r.exceptionOrNull()}")
                    speak("내 컴퓨터에 연결하지 못했어요.", "내 클라우드 컴퓨터에 연결하지 못했어요. 잠시 뒤에 다시 말씀해 주세요.")
                    onJob(null, "The driver's compute could not be reached."); return@post
                }
                Log.i(PleosBridge.TAG, "JOB ${j.id} ${j.kind} accepted requestId=$requestId deduped=${j.deduped} compute=${j.compute}")
                upsertJob(j); onJob(j, "")
                if (!polling.add(j.id)) return@post          // same job again (retry): nothing new starts
                if (!j.deduped) speak(j.statusLine.ifBlank { "맡겼어요." }, when (kind) {
                    "bugfix" -> "네, 결제 버튼 버그는 내 컴퓨터에서 고칠게요."
                    "edit" -> "네, 그렇게 고칠게요."
                    else -> "네, 방금 미팅 기록으로 제안 페이지 만들게요."
                })
                poll(j.id)
            }
        }
    }

    private fun poll(id: String) {
        io.execute {
            var last = ""; var finished = false
            while (true) {
                val j = runCatching { client.job(id) }.getOrNull()
                if (j != null) {
                    val sig = "${j.state} ${j.step} ${j.deployState}"
                    if (sig != last) {
                        val first = last.isEmpty(); last = sig
                        h.post {
                            upsertJob(j)
                            Log.i(PleosBridge.TAG, "JOB ${j.id} ${j.state} ${j.step}/${j.steps} ${j.statusLine}")
                            if (j.active) line.value = j.statusLine
                            if (!j.active && !finished) { finished = true; onJobFinished(j) }
                            else if (j.state == "DEPLOYED" && j.deployBy == "phone" && !first) speakSoon("${j.statusLine}\n폰에서 승인", "폰에서 승인해서 배포했어요.")
                        }
                    }
                    if (j.state == "DEPLOYED" || j.state == "FAILED" || (j.state == "READY" && j.kind != "bugfix")) break
                }
                Thread.sleep(if (finished) 2000 else 700)   // a fix waiting for approval is watched slowly: the phone may approve it
            }
        }
    }

    private fun onJobFinished(j: Job) {
        if (j.state == "FAILED") { speakSoon("${j.title} · 끝내지 못했어요", "${j.title} 작업을 끝내지 못했어요."); return }
        if (j.state == "DEPLOYED") { line.value = j.statusLine; return }
        val tail = if (pleos.car.value.moving) "차가 멈추면 보여 달라고 말씀해 주세요." else "보여 달라고 하시면 열게요."
        speakSoon(j.statusLine, when (j.kind) {
            "bugfix" -> "결제 버튼 고쳤어요. 테스트 통과했고 미리보기 올려 뒀어요. $tail"
            "edit" -> "말씀하신 대로 고쳤어요. $tail"
            else -> "${j.title} 준비됐어요. $tail"
        })
    }

    /** SHOW_RESULT. A result opens only because the driver asked, and only while the car is stopped. */
    fun showResult(jobId: String? = null): Pair<Job?, String> {
        val j = jobId?.let { id -> jobs.value.firstOrNull { it.id == id } } ?: jobs.value.lastOrNull { it.done }
        if (j == null) { speak("아직 준비된 결과가 없어요."); return null to "No finished job yet." }
        if (pleos.car.value.moving) {
            Log.i(PleosBridge.TAG, "SHOW refused ${j.id}: car moving (speed=${pleos.car.value.speedKmh})")
            speak(jobs.value.lastOrNull { it.active }?.statusLine ?: j.statusLine, "달리는 중에는 화면을 열지 않아요. 차가 멈추면 보여 드릴게요.")
            return null to "Refused: the car is moving. Results open only while the car is stopped."
        }
        Log.i(PleosBridge.TAG, "SHOW ${j.id} ${j.kind}")
        viewing.value = j.id; line.value = j.statusLine
        return j to ""
    }

    /** APPROVE_DEPLOY from inside the car. The driver's compute decides: it refuses unless the car reports speed 0. */
    fun approveDeploy(jobId: String? = null, onDone: (Job?, String) -> Unit = { _, _ -> }) {
        val j = jobId?.let { id -> jobs.value.firstOrNull { it.id == id } } ?: jobs.value.lastOrNull { it.waitingApproval }
        if (j == null || !j.waitingApproval) { speak("승인을 기다리는 배포가 없어요."); onDone(null, "No job is waiting for approval."); return }
        val c = pleos.car.value
        val speed = c.speedKmh ?: if (c.restricted) 1f else 0f
        io.execute {
            val (job, msg) = runCatching { client.approve(j.id, speed, c.gear) }.getOrElse { null to "내 클라우드 컴퓨터에 연결하지 못했어요." }
            h.post {
                if (job != null) {
                    Log.i(PleosBridge.TAG, "DEPLOY approved ${job.id} by car speed=$speed gear=${c.gear} -> ${job.deployUrl}")
                    upsertJob(job)
                    speak(job.statusLine, "배포했어요. 결제 버튼 수정이 반영됐어요.")
                } else {
                    Log.i(PleosBridge.TAG, "DEPLOY refused ${j.id} speed=$speed gear=${c.gear}: $msg")
                    speak("배포 승인은 정차해 있을 때만", msg)
                }
                onDone(job, msg)
            }
        }
    }

    /** speed > 0: whatever is open closes (the page keeps its scroll position). speed 0: say what is ready, open nothing. */
    private fun onMoving(moving: Boolean) {
        if (moving) {
            viewing.value?.let { Log.i(PleosBridge.TAG, "RESULT auto-closed $it: car moving") }
            viewing.value = null
            (jobs.value.lastOrNull { it.active } ?: jobs.value.lastOrNull())?.let { line.value = it.statusLine }
        } else if (jobs.value.any { it.done }) {
            val wait = jobs.value.any { it.waitingApproval }
            speakSoon(if (wait) "결제 버튼 수정 · 승인 대기" else "준비된 결과가 있어요", if (wait) "멈췄어요. 결제 버튼 수정이 승인을 기다리고 있어요." else "멈췄어요. 보여 달라고 하시면 열게요.")
        }
    }

    fun resetJobs() { jobs.value = emptyList(); viewing.value = null; scroll.clear() }

    private fun speak(display: String, voice: String = display) {
        line.value = display
        pleos.say(voice)
        log.value = (log.value + "메이트: $voice").takeLast(30)
    }

    // 1) Gear P -> D : brief only what has to close during this trip, by voice.
    private fun onGear(g: String) {
        when (g) {
            "D", "R" -> briefOnDeparture()
            "P" -> onParked()
        }
    }

    fun briefOnDeparture() {
        val open = store.open()
        val tied = open.firstOrNull { it.place.isNotBlank() && it.status == Promise.Status.OPEN }
        val c = pleos.car.value
        val mins = c.remainSec?.let { (it / 60).coerceAtLeast(1) }
        val where = c.destination?.let { d -> if (mins != null) "${d}까지 ${mins}분이에요. " else "${d}로 갈게요. " } ?: ""
        if (tied != null) {
            store.update(tied.id) { it.copy(status = Promise.Status.DRAFTED, evidence = "도착 직전 자동 알림 예약") }
            speak("도착 알림은 제가 보낼게요.\n${tied.counterpart} · 도착 직전 자동",
                "출발할게요. $where${tied.counterpart}께 도착 시간은 제가 알려드릴게요.")
        } else if (open.isEmpty()) {
            speak("편하게 운전하세요.", "출발할게요. ${where}편하게 운전하세요.")
        } else {
            speak("오늘 닫을 약속 ${open.size}개.", "출발할게요. ${where}오늘 닫을 약속이 ${open.size}개예요. 정차하면 정리해둘게요.")
        }
    }

    // 2) Everything the driver says while moving goes through here.
    fun onUtterance(raw: String) {
        val t = raw.trim()
        log.value = (log.value + "나: $t").takeLast(30)
        val yes = Regex("^(응|어|네|그래|좋아|보내|부탁|ㅇㅇ|오케이|ok)").containsMatchIn(t)
        val no = Regex("^(아니|됐어|하지 마|취소)").containsMatchIn(t)

        when (val p = awaiting) {
            is Pending.ArrivalSend -> if (yes) {
                store.update(p.promiseId) { it.copy(status = Promise.Status.DRAFTED, evidence = "도착 10분 전 자동 발송 예약") }
                awaiting = null
                speak("도착 10분 전에 보낼게요.", "네, 도착 10분 전에 보낼게요.")
                return
            } else if (no) { awaiting = null; speak("알겠어요.", "알겠어요. 직접 하실 걸로 둘게요."); return }
            is Pending.Reply -> if (yes) {
                val pr = store.items.value.first { it.id == p.promiseId }
                store.send(pr.counterpart, p.text)
                store.close(p.promiseId, "메시지 발송 · “${p.text}”")
                awaiting = null
                speak("보냈어요. ${pr.counterpart} 건은 닫았어요.", "보냈어요. ${pr.counterpart} 건은 닫아둘게요.")
                return
            } else if (no) { awaiting = null; speak("보류할게요.", "보류할게요. 정차하면 다시 보여드릴게요."); return }
            is Pending.ChargeStop -> if (yes) {
                awaiting = null; acceptChargeStop(); return
            } else if (no) { awaiting = null; speak("알겠어요.", "알겠어요. 필요하면 다시 말씀해 주세요."); return }
            null -> {}
        }

        // Mate: approve / show / hand off work to the driver's own compute
        if (Regex("(배포|베포)").containsMatchIn(t)) { approveDeploy(); return }
        if (Regex("닫아").containsMatchIn(t) && viewing.value != null) { viewing.value = null; return }
        if (Regex("(보여|띄워|열어 ?줘)").containsMatchIn(t) && jobs.value.isNotEmpty()) { showResult(); return }
        kindOf(t)?.let { k -> delegate(k, t); return }

        // "오늘 회의 요약해서 팀에 보내놔" -> real summary file + team draft (sending waits for a parked confirm)
        if (Regex("(회의|미팅).*(요약|정리)|(요약|정리).*(회의|미팅)").containsMatchIn(t)) { delegateMeeting(); return }

        // "오늘 찍은 걸로 릴스 만들어줘" -> background H.264 encode while driving
        if (Regex("(릴스|릴즈|리일|쇼츠|영상|하이라이트|찍은 사진|사진들)").containsMatchIn(t) && Regex("(만들|편집|뽑|정리)").containsMatchIn(t)) { delegateReel(); return }

        // "OO한테 ~라고 해줘 / 전해줘"  -> draft a reply in the driver's tone, confirm by voice
        Regex("(.+?)(한테|에게|께)\\s*(.+?)(라고|고)\\s*(전해|해|보내)").find(t)?.let { m ->
            val who = m.groupValues[1].trim()
            val what = m.groupValues[3].trim()
            val pr = matchPerson(who) ?: store.add(who, what, "", "차 안 음성")
            val msg = polish(what)
            awaiting = Pending.Reply(pr.id, msg)
            speak("${pr.counterpart}께 보낼까요?\n“$msg”", "${pr.counterpart}께 이렇게 보낼게요. $msg 보낼까요?")
            return
        }

        // "...하기로 했어 / 기억해" -> new promise, tied to the person if Mate knows them
        if (t.contains("기억") || t.contains("약속 추가") || t.contains("하기로 했")) {
            val m = Regex("(.+?)(한테|에게|께|이랑|하고|랑)\\s*(.+)").find(t)
            val whoRaw = m?.groupValues?.get(1)?.trim() ?: "상대"
            val who = matchPerson(whoRaw)?.counterpart ?: whoRaw
            val what = (m?.groupValues?.get(3) ?: t).replace(Regex("[,.?!\\s]*(기억해 ?둬|기억해|기억|줘)?[.?!]*\\s*$"), "").trim()
            val core = what.replace(Regex("[,\\s]*했(어|어요|다)?[,.]*$"), "").trim()
            val existing = matchPerson(whoRaw)?.takeIf { it.status != Promise.Status.CLOSED }
            if (existing != null) {
                store.update(existing.id) { it.copy(content = core.removeSuffix("하기로").trim() + " 확정 회신", due = "", source = "차 안 음성 · 갱신") }
                speak("${existing.counterpart}와의 약속,\n바꿔서 기억해둘게요.", "기억해둘게요. ${existing.counterpart}와 $core 한 걸로 바꿔둘게요.")
            } else {
                val p = store.add(who, core, "", "차 안 음성")
                speak("${p.counterpart}와의 약속,\n제가 챙길게요.", "기억해둘게요. ${p.counterpart}와 $core 한 약속, 제가 챙길게요.")
            }
            return
        }

        if (t.contains("뭐") && (t.contains("약속") || t.contains("남았"))) { briefOnDeparture(); return }

        speak("정차하면 정리해둘게요.", "들었어요. 정차하면 화면에 정리해둘게요.")
    }

    /** Turn a spoken fragment into a polite one-line message. */
    private fun polish(s: String): String {
        var x = s.trim().removeSuffix(".").trim()
        x = x.replace(Regex("보낸다$"), "보내드릴게요").replace(Regex("한다$"), "할게요").replace(Regex("간다$"), "갈게요")
            .replace(Regex("드린다$"), "드릴게요").replace(Regex("이다$"), "이에요")
        if (!x.endsWith("요") && !x.endsWith("다")) x += "요"
        return "$x."
    }

    /** Match a heard name to a known counterpart. STT mangles names (이준 -> "이 중"), so compare by surname + title. */
    private fun matchPerson(heard: String): Promise? {
        val h = heard.replace(" ", "")
        val all = store.items.value.sortedBy { if (it.status == Promise.Status.CLOSED) 1 else 0 }.distinctBy { it.counterpart }
        all.firstOrNull { h.contains(it.counterpart.replace(" ", "")) }?.let { return it }
        val title = Regex("(대표|팀장|매니저|이사|부장|과장|님)$").find(h)?.value
        val cands = all.filter { c ->
            val cn = c.counterpart.replace(" ", "")
            cn.first() == h.firstOrNull() && (title == null || cn.endsWith(title))
        }
        if (cands.size == 1) return cands[0]
        return all.filter { c -> title != null && c.counterpart.replace(" ", "").endsWith(title) }.singleOrNull()
    }

    // ---------- Moment 1: delegate a meeting summary while driving ----------
    fun delegateMeeting() {
        val src = MeetingDelegate.latestTranscript(ctx)
        if (src == null) { speak("오늘 회의 기록이 없어요."); return }
        thinking.value = true
        line.value = "회의 기록 읽는 중…"
        upsertTask(TaskCard("meeting", "MEETING", "회의 요약", "정리하는 중", "WORKING"))
        Thread {
            val t0 = System.currentTimeMillis()
            val sum = runCatching { MeetingDelegate.summarize(ctx, src) }.getOrNull()
            // keep the THINKING orb on screen long enough to be read as "working" (>= 2.4 s)
            val wait = (2400 - (System.currentTimeMillis() - t0)).coerceAtLeast(0)
            h.postDelayed({
                thinking.value = false
                if (sum == null) { speak("회의 기록을 못 읽었어요."); return@postDelayed }
                upsertTask(TaskCard("meeting", "MEETING", sum.title, sum.draft, "DRAFT", sum.file.absolutePath))
                speak("결정 ${sum.decisions.size} · 할 일 ${sum.actions.size}\n팀 채널 초안으로 올려뒀어요.",
                    "회의 요약했어요. 결정 ${sum.decisions.size}개, 할 일 ${sum.actions.size}개예요. 팀 채널에 보낼 초안으로 올려뒀어요. 주차하면 한 번만 확인해 주세요.")
            }, wait)
        }.start()
    }

    // ---------- Moment 2: make a reel from today's drive photos, in the background ----------
    fun delegateReel() {
        val n = reel.photos().size
        if (n == 0) { speak("오늘 찍은 사진이 없어요."); return }
        if (reel.running) { speak("지금 만들고 있어요."); return }
        val c = pleos.car.value
        val title = "오늘의 드라이브"
        val sub = listOfNotNull(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd")),
            c.destination?.let { "→ $it" }).joinToString("  ")
        upsertTask(TaskCard("reel", "REEL", "오늘의 드라이브 릴스", "사진 ${n}장 · 편집 중", "WORKING"))
        speak("사진 ${n}장으로 릴스 편집 시작.\n도착 전에 끝내 둘게요.", "네, 오늘 찍은 사진 ${n}장으로 릴스 만들게요. 도착 전에 끝내 둘게요.")
        reel.start(title, sub) { f, photos, ms ->
            h.post {
                val sec = (photos * 2.2 + 3.6).toInt()
                upsertTask(TaskCard("reel", "REEL", "오늘의 드라이브 릴스", "사진 ${photos}장 · ${sec}초 · 720×1280", "READY", f.absolutePath))
                speakSoon("릴스 완성. ${sec}초짜리예요.", "릴스 다 만들었어요. ${sec}초짜리예요. 주차하면 바로 보여드릴게요.")
            }
        }
    }

    // ---------- Moment 3: proactive — battery vs. the rest of today's plan ----------
    private fun onSoc(soc: Int) {
        val c = pleos.car.value
        if (batteryOffered || c.gear == "P" || soc > 25) return
        val capKwh = 77.4
        val rangeKm = c.rangeM?.takeIf { it > 1000f }?.let { (it / 1000).toInt() } ?: (capKwh * soc / 100 * 5.3).toInt()
        val (time, title, needKm) = nextSchedule
        if (rangeKm >= needKm) return
        batteryOffered = true
        val after = (soc + 50.0 * 1.0 / capKwh * 100).toInt().coerceAtMost(90)   // 50 kW for a 1 h meeting
        chargeAfter = after
        Log.i(PleosBridge.TAG, "BATTERY soc=$soc range=${rangeKm}km need=${needKm}km -> offer charging stop")
        speakSoon("배터리 ${soc}% · ${rangeKm}km 남음\n$time $title 왕복 ${needKm}km",
            "배터리가 ${soc}%예요. $time $title 다녀오기엔 부족해요. 미팅하는 동안 충전되게 건물 지하 충전소로 안내할까요?") { awaiting = Pending.ChargeStop }
    }
    private var chargeAfter = 80

    private fun acceptChargeStop() {
        pleos.addChargingStop(chargerName, chargerLat, chargerLon, "서울 서초구 양재동")
        upsertTask(TaskCard("charge", "CHARGE", chargerName, "미팅 1시간 충전 · 약 ${chargeAfter}%까지 · 내비 경로에 추가됨", "DONE"))
        speak("도착지를 건물 충전소로 바꿨어요.\n미팅 끝나면 약 ${chargeAfter}%", "도착지를 같은 건물 지하 충전소로 바꿨어요. 미팅 끝날 때쯤 ${chargeAfter}% 정도 찰 거예요.")
    }

    // 3) FusedLocation geofence (300 m) or ETA <= 10 min -> close the arrival promise with evidence.
    fun onNear() {
        val p = store.items.value.firstOrNull { it.place.isNotBlank() && it.status == Promise.Status.DRAFTED } ?: return
        // geofence fires ~300 m out; real ETA from NaviHelper if it is already that close, else a city-speed estimate
        val eta = pleos.car.value.remainSec?.takeIf { it in 1..900 }?.let { (it / 60).coerceAtLeast(1) } ?: 3
        val msg = "${p.place} 거의 다 왔어요. 약 ${eta}분 뒤 도착합니다."
        pleos.car.value = pleos.car.value.copy(remainSec = eta * 60, remainM = 300)
        store.send(p.counterpart, msg)
        store.close(p.id, "도착 알림 발송 · “$msg”")
        speak("${p.counterpart}께 도착 알림 보냈어요.\n“약 ${eta}분 뒤 도착합니다”", "${p.counterpart}께 ${eta}분 뒤 도착한다고 보냈어요.")
    }

    private fun onArrived(name: String) {
        speak("$name, 도착했어요.", "$name 에 도착했어요.")
    }

    // 4) Gear P : the screen opens up. Only then show text and 1-tap cards.
    fun refreshCards() {
        cards.value = store.open().map {
            Card(it.id, "${it.counterpart} · ${it.content}",
                if (it.due.isNotBlank()) "오늘 ${it.due.takeLast(5)}까지 · ${it.source}" else it.source,
                "보내기")
        }
    }

    private fun onParked() {
        pleos.heard.value = ""
        refreshCards()
        val open = store.open()
        val closed = store.items.value.count { it.status == Promise.Status.CLOSED }
        val done = tasks.value.count { it.state != "WORKING" }
        if (jobs.value.isNotEmpty()) { speakSoon("내려서는 폰으로 이어 보세요.", "주차했어요. 내리신 뒤에는 폰으로 이어서 보실 수 있어요."); return }
        if (done > 0) speak("맡기신 일 ${done}건, 화면에 올려뒀어요.", "주차했어요. 맡기신 일 ${done}건이랑 남은 약속 ${open.size}건, 화면에 올려뒀어요.")
        else speak("이번 길에 ${closed}건을 닫았어요.", "주차했어요. 이번 이동에서 ${closed}건 닫았고, 남은 ${open.size}건은 화면에 올려둘게요.")
    }

    fun confirmTask(id: String) {
        val t = tasks.value.firstOrNull { it.id == id } ?: return
        Log.i(PleosBridge.TAG, "CONFIRM $id (state ${t.state})", Throwable("who"))
        if (t.state != "DRAFT") return
        if (t.kind == "MEETING") {
            store.send("팀 채널", t.body.lineSequence().first())
            upsertTask(t.copy(state = "APPROVED"))
            line.value = "팀 채널 발송함에 넣었어요."
        }
    }

    fun resetTasks() { resetJobs(); tasks.value = emptyList(); playing.value = false; batteryOffered = false; awaiting = null; thinking.value = false }

    fun tapCard(id: String) {
        val p = store.items.value.firstOrNull { it.id == id } ?: return
        val msg = polish(when {
            p.content.contains("견적") -> "견적서는 내일 오전 10시까지 메일로 드릴게요"
            p.content.contains("시연") || p.content.contains("회신") -> "다음 주 화요일 시연 그대로 진행할게요. 편한 시간 알려주세요"
            else -> p.content
        })
        store.send(p.counterpart, msg)
        store.close(p.id, "1탭 승인 발송 · “$msg”")
        refreshCards()
        line.value = "${p.counterpart}께 보냈어요."
    }

    companion object { const val MEETING = "meetings/2026-11-15_clientA_meeting.txt" }
}
