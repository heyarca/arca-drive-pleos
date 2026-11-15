package app.mate.gleo

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One delegated piece of work, as the user's compute reports it (server/mate_server.py). */
data class Job(
    val id: String, val kind: String, val title: String, val state: String, val step: Int, val steps: Int,
    val statusLine: String, val summary: List<String>, val resultUrl: String, val beforeUrl: String,
    val mobileUrl: String, val engine: String, val compute: String,
    val deployState: String, val deployUrl: String, val deployBy: String, val deduped: Boolean,
) {
    val active get() = state == "QUEUED" || state == "WORKING"
    val done get() = state == "READY" || state == "DEPLOYED"
    val waitingApproval get() = kind == "bugfix" && state == "READY"
}

/**
 * The car never does the work. It sends the request to the user's own compute and reads the job back.
 *   demo    : the developer's Mac Studio, reached from the emulator at http://10.0.2.2:8790
 *   service : the subscriber's personal cloud VM (infra/provision_user_vm.sh), https + per-user token
 */
class MateClient(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mate_compute", Context.MODE_PRIVATE)
    var base: String
        get() = prefs.getString("url", DEFAULT_BASE)!!.trimEnd('/')
        set(v) { prefs.edit().putString("url", v).apply() }
    var token: String
        get() = prefs.getString("token", "")!!
        set(v) { prefs.edit().putString("token", v).apply() }

    fun createJob(kind: String, request: String, requestId: String, transcript: String?, parent: String?): Job {
        val body = JSONObject().put("kind", kind).put("request", request).put("requestId", requestId).put("source", "car")
        transcript?.let { body.put("transcript", it) }
        parent?.let { body.put("parent", it) }
        val (code, o) = call("POST", "/v1/jobs", body)
        if (code != 200) error("create job $code ${o.optString("error")}")
        return parse(o)
    }

    fun job(id: String): Job = parse(call("GET", "/v1/jobs/$id").second)

    /** In-car approval. The server refuses it unless the car reports speed 0. Returns the job, or the refusal message. */
    fun approve(id: String, speedKmh: Float, gear: String): Pair<Job?, String> {
        val (code, o) = call("POST", "/v1/jobs/$id/approve", JSONObject().put("source", "car").put("speedKmh", speedKmh.toDouble()).put("gear", gear))
        return if (code == 200) parse(o) to "" else null to o.optString("message", "승인하지 못했어요.")
    }

    private fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 4000; c.readTimeout = 8000
            if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            return code to JSONObject(text)
        } finally { c.disconnect() }
    }

    private fun parse(o: JSONObject): Job {
        val d = o.optJSONObject("deploy")
        val s = o.optJSONArray("summary")
        return Job(
            o.getString("id"), o.optString("kind"), o.optString("title"), o.optString("state"), o.optInt("step"), o.optInt("steps"),
            o.optString("statusLine"), (0 until (s?.length() ?: 0)).map { s!!.getString(it) }, o.optString("resultUrl"), o.optString("beforeUrl"),
            o.optString("mobileUrl"), o.optString("engine"), o.optString("compute"),
            d?.optString("state").orEmpty(), d?.optString("url").orEmpty(), d?.optString("by").orEmpty(), o.optBoolean("deduped"),
        )
    }

    companion object { const val DEFAULT_BASE = "http://10.0.2.2:8790" }
}
