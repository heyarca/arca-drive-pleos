package app.heyarca.drive

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The companion logic. Rules are deliberately simple and on-device so it runs inside the
 * Pleos emulator with no external account. In production the same hooks call the ARCA server
 * (promise extraction from meetings / messages, drafting in the user's tone).
 */
class CompanionBrain(private val store: PromiseStore, private val pleos: PleosBridge) {

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
    }

    init {
        refreshCards()
        pleos.onGear = { g -> onGear(g) }
        pleos.onUtterance = { onUtterance(it) }
        pleos.onNearDestination = { onNear() }
        pleos.onArrived = { name -> onArrived(name) }
    }

    private fun speak(display: String, voice: String = display) {
        line.value = display
        pleos.say(voice)
        log.value = (log.value + "ARCA: $voice").takeLast(30)
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
        if (open.isEmpty()) { speak("오늘 남은 약속 없음", "오늘 닫을 약속은 없어요. 운전에만 집중하세요."); return }
        val tied = open.firstOrNull { it.place.isNotBlank() }
        if (tied != null) {
            awaiting = Pending.ArrivalSend(tied.id)
            speak(
                "약속 ${open.size}개 · 도착 전 1건",
                "출발하셨네요. 오늘 닫을 약속이 ${open.size}개예요. ${tied.counterpart}께 도착 시간 알려드리기로 하셨죠. " +
                    "도착 10분 전에 제가 보내드릴까요?",
            )
        } else {
            speak("약속 ${open.size}개", "오늘 닫을 약속이 ${open.size}개예요. 정차하면 화면에 정리해둘게요.")
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
                speak("도착 10분 전 자동 발송", "네, 도착 10분 전에 보낼게요.")
                return
            } else if (no) { awaiting = null; speak("알겠어요", "알겠어요. 직접 하실 걸로 둘게요."); return }
            is Pending.Reply -> if (yes) {
                val pr = store.items.value.first { it.id == p.promiseId }
                store.send(pr.counterpart, p.text)
                store.close(p.promiseId, "메시지 발송: \"${p.text}\"")
                awaiting = null
                speak("보냈어요 · 약속 닫힘", "보냈어요. ${pr.counterpart} 건은 닫아둘게요.")
                return
            } else if (no) { awaiting = null; speak("보류", "보류할게요. 정차하면 다시 보여드릴게요."); return }
            null -> {}
        }

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

        // "...하기로 했어 / 기억해" -> new promise, tied to the person if ARCA knows them
        if (t.contains("기억") || t.contains("약속 추가") || t.contains("하기로 했")) {
            val m = Regex("(.+?)(한테|에게|께|이랑|하고|랑)\\s*(.+)").find(t)
            val whoRaw = m?.groupValues?.get(1)?.trim() ?: "상대"
            val who = matchPerson(whoRaw)?.counterpart ?: whoRaw
            val what = (m?.groupValues?.get(3) ?: t).replace(Regex("\\s*(기억해|기억|줘)\\.?\\s*$"), "").trim()
            val core = what.replace(Regex("\\s*했(어|어요|다)?$"), "").trim()
            val existing = matchPerson(whoRaw)?.takeIf { it.status != Promise.Status.CLOSED }
            if (existing != null) {
                store.update(existing.id) { it.copy(content = core.removeSuffix("하기로").trim() + " 확정 회신", due = "", source = "차 안 음성 · 갱신") }
                speak("약속 갱신 · ${existing.counterpart}", "기억해둘게요. ${existing.counterpart}와 $core 한 걸로 바꿔둘게요.")
            } else {
                val p = store.add(who, core, "", "차 안 음성")
                speak("새 약속 · ${p.counterpart}", "기억해둘게요. ${p.counterpart}와 $core 한 약속, 제가 챙길게요.")
            }
            return
        }

        if (t.contains("뭐") && (t.contains("약속") || t.contains("남았"))) { briefOnDeparture(); return }

        speak("정차 후 정리", "들었어요. 정차하면 화면에 정리해둘게요.")
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

    // 3) FusedLocation geofence (300 m) or ETA <= 10 min -> close the arrival promise with evidence.
    fun onNear() {
        val p = store.items.value.firstOrNull { it.place.isNotBlank() && it.status == Promise.Status.DRAFTED } ?: return
        // geofence fires ~300 m out; real ETA from NaviHelper if it is already that close, else a city-speed estimate
        val eta = pleos.car.value.remainSec?.takeIf { it in 1..900 }?.let { (it / 60).coerceAtLeast(1) } ?: 3
        val msg = "${p.place} 거의 다 왔어요. 약 ${eta}분 뒤 도착합니다."
        pleos.car.value = pleos.car.value.copy(remainSec = eta * 60, remainM = 300)
        store.send(p.counterpart, msg)
        store.close(p.id, "도착 알림 발송: \"$msg\"")
        speak("${p.counterpart}께 도착 알림 보냄", "${p.counterpart}께 ${eta}분 뒤 도착한다고 보냈어요.")
    }

    private fun onArrived(name: String) {
        speak("$name 도착", "$name 에 도착했어요.")
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
        speak("주차 · 닫힌 약속 $closed · 남은 ${open.size}", "주차했어요. 이번 이동에서 ${closed}건 닫았고, 남은 ${open.size}건은 화면에 올려둘게요.")
    }

    fun tapCard(id: String) {
        val p = store.items.value.firstOrNull { it.id == id } ?: return
        val msg = polish(when {
            p.content.contains("견적") -> "견적서는 내일 오전 10시까지 메일로 드릴게요"
            p.content.contains("시연") || p.content.contains("회신") -> "다음 주 화요일 시연 그대로 진행할게요. 편한 시간 알려주세요"
            else -> p.content
        })
        store.send(p.counterpart, msg)
        store.close(p.id, "1탭 승인 발송: \"$msg\"")
        refreshCards()
        line.value = "${p.counterpart} 건 닫힘"
    }
}
