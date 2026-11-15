package app.mate.gleo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A promise = something the driver told another person they would do.
 * Mate keeps it open until there is outside evidence it closed (message sent, reply, file delivered).
 */
data class Promise(
    val id: String,
    val counterpart: String,
    val content: String,
    val due: String,            // "yyyy-MM-dd HH:mm" or ""
    val place: String = "",     // destination POI this promise is tied to (optional)
    val status: Status = Status.OPEN,
    val evidence: String = "",  // what closed it
    val source: String = "",    // where Mate heard it (meeting, voice memo, in-car)
) {
    enum class Status { OPEN, DRAFTED, CLOSED }
}

class PromiseStore(context: Context) {
    private val prefs = context.getSharedPreferences("mate_promises", Context.MODE_PRIVATE)
    val outbox = MutableStateFlow<List<String>>(emptyList())
    private val _items = MutableStateFlow<List<Promise>>(emptyList())
    val items: StateFlow<List<Promise>> = _items

    init { _items.value = load() }

    fun open(): List<Promise> = _items.value.filter { it.status != Promise.Status.CLOSED }

    fun add(counterpart: String, content: String, due: String, source: String): Promise {
        val p = Promise(
            id = "p" + System.currentTimeMillis(),
            counterpart = counterpart, content = content, due = due, source = source,
        )
        save(_items.value + p)
        return p
    }

    fun update(id: String, f: (Promise) -> Promise) = save(_items.value.map { if (it.id == id) f(it) else it })

    fun close(id: String, evidence: String) = update(id) { it.copy(status = Promise.Status.CLOSED, evidence = evidence) }

    fun send(to: String, text: String) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        outbox.value = outbox.value + "$ts → $to · $text"
    }

    fun resetDemo() = save(seed())

    private fun save(list: List<Promise>) {
        _items.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("counterpart", it.counterpart); put("content", it.content)
                put("due", it.due); put("place", it.place); put("status", it.status.name)
                put("evidence", it.evidence); put("source", it.source)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun load(): List<Promise> {
        val raw = prefs.getString("items", null) ?: return seed().also { save(it) }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Promise(
                o.getString("id"), o.getString("counterpart"), o.getString("content"), o.optString("due"),
                o.optString("place"), Promise.Status.valueOf(o.optString("status", "OPEN")),
                o.optString("evidence"), o.optString("source"),
            )
        }
    }

    /** Demo data: what Mate already heard before the driver got in the car (sample people, not real accounts). */
    private fun seed(): List<Promise> {
        val today = LocalDateTime.now().toLocalDate()
        return listOf(
            Promise("p1", "김지현 팀장", "10시 미팅 도착 시간 알려드리기", "$today 09:55", place = "양재 미팅룸", source = "어제 통화"),
            Promise("p3", "박서연 매니저", "다음 주 시연 일정 회신", "$today 15:00", source = "카톡"),
            Promise("p2", "이준 대표", "견적서 보내드리기", "$today 18:00", source = "어제 미팅 녹음"),
        )
    }
}
