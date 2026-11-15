package app.heyarca.drive

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class ArcaDriveApp : Application() {
    lateinit var store: PromiseStore
        private set
    lateinit var pleos: PleosBridge
        private set
    lateinit var brain: CompanionBrain
        private set

    override fun onCreate() {
        super.onCreate()
        store = PromiseStore(this)
        pleos = PleosBridge(this)
        brain = CompanionBrain(this, store, pleos)
        pleos.start()
        val f = IntentFilter().apply { DEMO_ACTIONS.forEach { addAction(it) } }
        registerReceiver(demo, f, RECEIVER_EXPORTED)
    }

    /** Demo / test hooks (adb broadcast). Production input is the car mic via SpeechToText. */
    private val demo = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                ACTION_SAY -> i.getStringExtra("text")?.let { brain.onUtterance(it) }
                ACTION_PCM -> i.getStringExtra("name")?.let { n ->
                    runCatching { assets.open("demo/$n.pcm").use { it.readBytes() } }.getOrNull()?.let { pleos.listenPcm(it) }
                }
                ACTION_NEAR -> brain.onNear()
                ACTION_RESET -> { android.util.Log.i(PleosBridge.TAG, "RESET demo data"); store.resetDemo(); store.outbox.value = emptyList(); brain.resetTasks(); brain.refreshCards(); brain.line.value = ""; pleos.heard.value = "" }
                ACTION_TASK -> when (i.getStringExtra("name")) {   // same entry points the STT intents call
                    "meeting" -> brain.delegateMeeting()
                    "reel" -> brain.delegateReel()
                    "confirm" -> brain.confirmTask("meeting")
                    "play" -> brain.playing.value = true
                    "close" -> brain.playing.value = false
                }
                ACTION_DEST -> pleos.setDestination(
                    (i.getStringExtra("name") ?: "양재 미팅룸").replace('_', ' '), i.getDoubleExtra("lat", 37.4688), i.getDoubleExtra("lon", 127.0390),
                    (i.getStringExtra("addr") ?: "서울 서초구 양재동").replace('_', ' '))
            }
        }
    }

    companion object {
        const val ACTION_SAY = "app.heyarca.drive.DEMO_SAY"
        const val ACTION_PCM = "app.heyarca.drive.DEMO_PCM"
        const val ACTION_NEAR = "app.heyarca.drive.DEMO_NEAR"
        const val ACTION_RESET = "app.heyarca.drive.DEMO_RESET"
        const val ACTION_DEST = "app.heyarca.drive.DEMO_DEST"
        const val ACTION_TASK = "app.heyarca.drive.DEMO_TASK"
        val DEMO_ACTIONS = listOf(ACTION_SAY, ACTION_PCM, ACTION_NEAR, ACTION_RESET, ACTION_DEST, ACTION_TASK)
    }
}
