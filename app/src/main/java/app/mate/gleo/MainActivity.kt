package app.mate.gleo

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import androidx.compose.animation.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableLongStateOf

// Pleos tokens (background_primary / surface / text) + the Gleo AI group, as in the light Pleos Connect chrome.
private val Bg = Color(0xFFF7F8FA)
private val Paper = Color(0xFFFFFFFF)
private val Ink = Color(0xFF131417)
private val InkSoft = Color(0xFF44464E)
private val Gleo = Color(0xFF3F4B81)
private val GleoSoft = Color(0xFF9BA8E2)
private val Line = Color(0xFFEDEEF2)
private val Mute = Color(0xFF6B6E76)
private val Faint = Color(0xFFA9ABB3)
private val Good = Color(0xFF02A657)

// Asta Sans (OFL), one variable font file, weight axis per style
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Kr = FontFamily(listOf(400, 600, 700, 800).map { w ->
    Font(R.font.asta_sans, FontWeight(w), variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)))
})

class MainActivity : ComponentActivity() {
    private lateinit var brain: CompanionBrain
    private lateinit var app: MateApp

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as MateApp
        brain = app.brain
        if (checkSelfPermission(PleosBridge.PERM_SPEED) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(PleosBridge.PERM_SPEED), 1)
        handleGleo(intent)
        setContent { Screen(app, brain) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        app.pleos.startSpeed()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleGleo(intent) }

    /** Gleo Interface: Gleo AI -> startActivity(action, ACTION_PARAM json) -> callback broadcast. */
    private fun handleGleo(i: Intent?) {
        val action = i?.action ?: return
        if (action !in GLEO_ACTIONS) return
        val p = runCatching { JSONObject(i.getStringExtra("ACTION_PARAM")?.trim('"')?.replace("\\\"", "\"") ?: "{}") }.getOrDefault(JSONObject())
        android.util.Log.i(PleosBridge.TAG, "GLEO $action $p")
        when (action) {
            "DELEGATE_TASK" -> {
                val task = p.optString("task")
                val kind = p.optString("kind").ifBlank { brain.kindOf(task).orEmpty() }
                if (task.isBlank() || kind !in setOf("proposal", "edit", "bugfix")) { callback(action, "asking", "Say what to make or fix: a proposal page, an edit to it, or a bug fix."); return }
                // idempotency: Gleo's requestId, or the same words within the same two minutes
                val rid = p.optString("requestId").ifBlank { "gleo-${task.hashCode()}-${System.currentTimeMillis() / 120_000}" }
                brain.delegate(kind, task, rid) { j, err ->
                    if (j == null) callback(action, "fail", err)
                    else callback(action, "success", if (j.deduped) "Already running as job ${j.id}." else "Job ${j.id} started on the driver's compute.", j.id)
                }
            }
            "SHOW_RESULT" -> { val (j, why) = brain.showResult(p.optString("jobId").ifBlank { null }); if (j != null) callback(action, "success", "Result of job ${j.id} is on screen.", j.id) else callback(action, "fail", why) }
            "APPROVE_DEPLOY" -> brain.approveDeploy(p.optString("jobId").ifBlank { null }) { j, why -> if (j != null) callback(action, "success", "Deployed job ${j.id}.", j.id) else callback(action, "fail", why) }
            "BRIEF_MY_PROMISES" -> { brain.briefOnDeparture(); callback(action, "success", "Briefed ${app.store.open().size} open promises.") }
            "ADD_PROMISE" -> {
                val who = p.optString("counterpart"); val what = p.optString("content")
                if (who.isBlank() || what.isBlank()) callback(action, "asking", "The counterpart and the promise content are required.")
                else { app.store.add(who, what, p.optString("dueDateTime"), "Gleo"); callback(action, "success", "The promise was saved.") }
            }
            else -> { brain.onNear(); callback(action, "success", "The arrival update was sent.") }
        }
    }

    /** Gleo Interface callback. jobId lets Gleo refer to the same piece of work later (SHOW_RESULT / APPROVE_DEPLOY). */
    private fun callback(action: String, status: String, msg: String, jobId: String? = null) {
        val r = JSONObject().put("functionName", action).put("status", status).put("message", msg).put("mode", "silent")
        jobId?.let { r.put("jobId", it) }
        android.util.Log.i(PleosBridge.TAG, "GLEO callback $r")
        sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").apply {
            putExtra("packageName", packageName)
            putExtra("response", r.toString())
        })
    }

    companion object { val GLEO_ACTIONS = setOf("DELEGATE_TASK", "SHOW_RESULT", "APPROVE_DEPLOY", "BRIEF_MY_PROMISES", "ADD_PROMISE", "SEND_ARRIVAL_UPDATE") }
}

@Composable
private fun Screen(app: MateApp, brain: CompanionBrain) {
    val car by app.pleos.car.collectAsState()
    val sdk by app.pleos.sdk.collectAsState()
    val line by brain.line.collectAsState()
    val heard by app.pleos.heard.collectAsState()
    val speaking by app.pleos.speaking.collectAsState()
    val listening by app.pleos.listening.collectAsState()
    val thinking by brain.thinking.collectAsState()
    val reelP by brain.reel.progress.collectAsState()
    val level by app.pleos.level.collectAsState()
    val items by app.store.items.collectAsState()
    val tasks by brain.tasks.collectAsState()
    val playing by brain.playing.collectAsState()
    val jobs by brain.jobs.collectAsState()
    val viewing by brain.viewing.collectAsState()
    val driving = car.moving     // speed > 0: one line only. speed 0 (any gear): cards, results on request, approvals
    val working = reelP in 0f..0.999f
    val mode = when {
        listening -> OrbMode.LISTENING
        speaking -> OrbMode.SPEAKING
        thinking -> OrbMode.THINKING
        working -> OrbMode.WORKING
        else -> OrbMode.IDLE
    }

    // one spring drives the whole layout between "driving" (orb centre stage) and "parked" (orb left, cards right)
    val p by animateFloatAsState(if (driving) 0f else 1f, spring(dampingRatio = 0.86f, stiffness = 60f), label = "park")

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        val W = maxWidth; val H = maxHeight
        val orbBox = lerpDp(H * 0.62f, H * 0.56f, p)
        val cx = lerpDp(W / 2, W * 0.245f, p)
        val cy = lerpDp(H * 0.42f, H * 0.40f, p)
        val textW = lerpDp(W * 0.72f, W * 0.40f, p)

        // soft daylight behind the orb
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(cx.toPx(), cy.toPx())
            drawRect(Brush.radialGradient(listOf(Paper, Paper.copy(alpha = 0.6f), Color.Transparent), center = c, radius = size.width * 0.42f))
        }

        // the orb
        Box(Modifier.offset(cx - orbBox / 2, cy - orbBox / 2).size(orbBox)) {
            ThinkingOrb(mode, (level / 10.0).toFloat(), calm = driving, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(orbBox * 0.6f).clip(CircleShape).clickable { app.pleos.listen() })
        }

        // state word under the orb (tiny, like the library's label)
        val stateWord = when (mode) {
            OrbMode.LISTENING -> "듣는 중"; OrbMode.SPEAKING -> "말하는 중"; OrbMode.THINKING -> "생각하는 중"
            OrbMode.WORKING -> "작업 중"; OrbMode.IDLE -> ""
        }

        // the one line
        Box(Modifier.offset(cx - textW / 2, cy + orbBox * 0.43f).width(textW)) {
            val shown = if (listening && heard.isNotBlank()) "“$heard…" else line.ifBlank { if (driving) "말로 맡기세요.\n일은 내 컴퓨터가 합니다." else "말로 맡기면, 내 컴퓨터가 일합니다." }
            AnimatedContent(shown to (listening && heard.isNotBlank()), transitionSpec = {
                (fadeIn(tween(600, delayMillis = 100)) + slideInVertically(spring(dampingRatio = 0.9f, stiffness = 120f)) { it / 5 })
                    .togetherWith(fadeOut(tween(240)))
            }, label = "line") { (l, isHeard) ->
                val main = l.substringBefore('\n'); val sub = l.substringAfter('\n', "")
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(main, size = if (driving) 48 else 36, weight = if (isHeard) FontWeight.Normal else FontWeight.Bold,
                        color = if (isHeard) InkSoft else Ink, maxLines = 2, center = true, track = -0.6f)
                    if (sub.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(sub, size = if (driving) 32 else 26, weight = FontWeight.Normal, color = Mute, maxLines = 2, center = true, track = -0.3f)
                    }
                }
            }
        }

        // glanceable background-work pill (driving): one line, no interaction
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); now = System.currentTimeMillis() } }
        val bg = tasks.lastOrNull { it.state == "WORKING" } ?: tasks.maxByOrNull { it.at }?.takeIf { now - it.at < 8000 }
        if (bg != null && p < 0.5f) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp).alpha(1f - p * 2)) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Paper).border(1.dp, Line, RoundedCornerShape(50))
                    .padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (bg.state == "WORKING") Gleo else Ink))
                    Spacer(Modifier.width(14.dp))
                    val label = when (bg.kind) { "REEL" -> "릴스"; "MEETING" -> "회의 요약"; else -> "충전 경유지" }
                    val st = when {
                        bg.kind == "REEL" && bg.state == "WORKING" -> "편집 중 ${(reelP.coerceAtLeast(0f) * 100).toInt()}%"
                        bg.state == "WORKING" -> "정리 중"
                        bg.state == "DRAFT" -> "초안 준비됨"
                        else -> "완료"
                    }
                    Text("$label · $st", 26, FontWeight.SemiBold, Ink, maxLines = 1)
                }
            }
        }

        // top: quiet status, no chrome
        Row(Modifier.fillMaxWidth().padding(horizontal = 64.dp, vertical = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (driving) Gleo else Faint))
            Spacer(Modifier.width(14.dp))
            Text(if (driving) "주행 중 · ${car.speedKmh?.toInt()?.let { "$it km/h · " } ?: ""}한 줄만 표시" else if (car.gear == "P") "주차 중 · P" else "정차 중 · ${car.gear}", 26, FontWeight.SemiBold, if (driving) Ink else Mute)
            if (stateWord.isNotBlank()) { Spacer(Modifier.width(18.dp)); Text(stateWord, 22, FontWeight.SemiBold, Mute, maxLines = 1) }
            Spacer(Modifier.weight(1f))
            car.socPct?.let { soc ->
                Text("$soc%", 26, FontWeight.Bold, if (soc <= 25) Color(0xFFB4462B) else Mute, maxLines = 1)
                Spacer(Modifier.width(26.dp))
            }
            car.destination?.let { d ->
                Text(car.waypoint?.let { "$d · 충전소" } ?: d, 26, FontWeight.SemiBold, Mute, maxLines = 1)
                val arrived = !driving && (car.remainM ?: 99999) <= 400
                val eta = if (arrived) "도착" else car.remainSec?.let { if (it < 90) "곧 도착" else "${it / 60}분" }
                val km = if (arrived) null else car.remainM?.let { String.format("%.1f km", it / 1000.0) }
                listOfNotNull(eta, km).takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.width(18.dp))
                    Text(it.joinToString("  ·  "), 26, FontWeight.Bold, Ink, maxLines = 1)
                }
            }
        }

        // parked: cards, only when the car is in P
        val open = items.filter { it.status != Promise.Status.CLOSED }
        val closed = items.filter { it.status == Promise.Status.CLOSED }
        Box(Modifier.offset(x = W * 0.47f, y = 108.dp).width(W * 0.53f - 56.dp).height(H - 150.dp)
            .graphicsLayer { alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f); translationX = (1f - p) * 80f }) {
            if (p > 0.02f && jobs.isNotEmpty()) MatePanel(jobs, onShow = { brain.showResult(it) }, onApprove = { brain.approveDeploy(it) })
            else if (p > 0.02f) ParkedCards(tasks, open, closed, onTask = { id -> if (id == "reel") brain.playing.value = true else brain.confirmTask(id) }, onTap = { brain.tapCard(it) })
        }

        // the reel, playing (parked only)
        if (playing && !driving) {
            val f = tasks.firstOrNull { it.kind == "REEL" }?.file
            if (f != null) Box(Modifier.fillMaxSize().background(Ink).clickable { brain.playing.value = false }) {
                val vh = H - 150.dp
                Text("오늘의 드라이브 릴스 · 화면을 누르면 닫혀요", 22, FontWeight.SemiBold, Paper.copy(alpha = 0.75f), maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp))
                Box(Modifier.align(Alignment.TopCenter).padding(top = 40.dp).height(vh).width(vh * 9f / 16f).clip(RoundedCornerShape(28.dp)).background(Color.Black)) {
                    ReelPlayer(f)
                }
            }
        }

        // a result, open because the driver asked for it. It exists only while the car is stopped.
        val shown = jobs.firstOrNull { it.id == viewing }
        if (shown != null && !driving) ResultView(shown, brain.client.base, brain.scroll, onClose = { brain.viewing.value = null }, onApprove = { brain.approveDeploy(shown.id) })

    }
}

private fun lerpDp(a: Dp, b: Dp, t: Float): Dp = a + (b - a) * t

/** TextureView + MediaPlayer: composes inside the Pleos TaskView like any other view (SurfaceView can punch a hole). */
@Composable
private fun ReelPlayer(path: String) {
    val player = remember(path) { android.media.MediaPlayer() }
    DisposableEffect(path) { onDispose { runCatching { player.release() } } }
    androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
        android.view.TextureView(c).apply {
            surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    runCatching {
                        player.setSurface(android.view.Surface(st))
                        player.setDataSource(path); player.isLooping = true
                        player.setOnPreparedListener { it.start(); android.util.Log.i(PleosBridge.TAG, "REEL playing ${it.duration} ms") }
                        player.prepareAsync()
                    }.onFailure { android.util.Log.e(PleosBridge.TAG, "REEL play", it) }
                }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            }
        }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun ParkedCards(tasks: List<CompanionBrain.TaskCard>, open: List<Promise>, closed: List<Promise>, onTask: (String) -> Unit, onTap: (String) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        if (tasks.isNotEmpty()) {
            Label("맡기신 일", tasks.count { it.state != "APPROVED" && it.state != "DONE" })
            Spacer(Modifier.height(14.dp))
            tasks.forEach { t -> TaskRow(t) { onTask(t.id) } }
            Spacer(Modifier.height(16.dp))
        }
        Label("남은 약속", open.size)
        Spacer(Modifier.height(14.dp))
        if (open.isEmpty()) Text("모두 닫혔어요.", 24, FontWeight.SemiBold, Mute)
        if (tasks.isNotEmpty()) open.take(3).forEach { pr ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pr.counterpart, 22, FontWeight.SemiBold, Gleo, maxLines = 1)
                Spacer(Modifier.width(14.dp))
                Text(pr.content, 22, FontWeight.SemiBold, Ink, maxLines = 1, modifier = Modifier.weight(1f))
                Text("보내기", 22, FontWeight.Bold, Ink, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ink, RoundedCornerShape(50)).clickable { onTap(pr.id) }.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
        if (tasks.isEmpty()) open.take(3).forEach { pr ->
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.padding(horizontal = 30.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 20.dp)) {
                        Text(pr.counterpart, 22, FontWeight.SemiBold, Gleo, maxLines = 1)
                        Spacer(Modifier.height(6.dp))
                        Text(pr.content, 30, FontWeight.Bold, Ink, maxLines = 1, track = -0.5f)
                        Spacer(Modifier.height(6.dp))
                        Text(listOf(pr.due.takeLast(5).takeIf { pr.due.isNotBlank() }?.let { "오늘 $it" }, pr.source).filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  "),
                            20, FontWeight.Normal, Mute, maxLines = 1)
                    }
                    Pill("보내기") { onTap(pr.id) }
                }
            }
        }
        if (closed.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Label("닫힌 약속", closed.size)
            Spacer(Modifier.height(10.dp))
            closed.takeLast(if (tasks.isEmpty()) 3 else 1).reversed().forEach { pr ->
                Row(Modifier.padding(bottom = 10.dp)) {
                    Text("✓", 22, FontWeight.Bold, Faint, maxLines = 1)
                    Spacer(Modifier.width(16.dp))
                    Text("${pr.counterpart} · ${pr.evidence}", 20, FontWeight.Normal, Mute, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: CompanionBrain.TaskCard, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(horizontal = 30.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.kind == "REEL" && t.file.isNotBlank()) {
                val thumb = remember(t.file) {
                    runCatching {
                        val r = android.media.MediaMetadataRetriever(); r.setDataSource(t.file)
                        r.getFrameAtTime(3_500_000)?.asImageBitmap().also { r.release() }
                    }.getOrNull()
                }
                if (thumb != null) {
                    Image(thumb, null, Modifier.size(width = 72.dp, height = 128.dp).clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Spacer(Modifier.width(22.dp))
                }
            }
            Column(Modifier.weight(1f).padding(end = 20.dp)) {
                val tag = when (t.kind) { "MEETING" -> "회의 요약 · 팀 채널 초안"; "REEL" -> "주행 중 자동 편집"; else -> "배터리 · 경유지" }
                Text(tag, 22, FontWeight.SemiBold, Gleo, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Text(t.title, 30, FontWeight.Bold, Ink, maxLines = 1, track = -0.5f)
                Spacer(Modifier.height(6.dp))
                val body = if (t.kind == "MEETING") t.body.lines().drop(1).filter { it.startsWith("·") }.take(3).joinToString("\n") else t.body
                Text(body, 20, FontWeight.Normal, Mute, maxLines = 3)
            }
            when {
                t.kind == "MEETING" && t.state == "DRAFT" -> Pill("확인 후 보내기", onAction)
                t.kind == "MEETING" && t.state == "APPROVED" -> Text("발송함에 넣음", 22, FontWeight.SemiBold, Mute, maxLines = 1)
                t.kind == "REEL" && t.state == "READY" -> Pill("재생", onAction)
                t.state == "WORKING" -> Text("작업 중", 22, FontWeight.SemiBold, Mute, maxLines = 1)
                else -> Text("완료", 22, FontWeight.SemiBold, Mute, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(InkSoft).clickable { onClick() }.padding(horizontal = 32.dp, vertical = 18.dp)) {
        Text(label, 24, FontWeight.Bold, Paper, maxLines = 1)
    }
}

@Composable
private fun Label(s: String, n: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(s, 24, FontWeight.SemiBold, Ink, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Text("$n", 24, FontWeight.Bold, Gleo, maxLines = 1)
    }
}

@Composable
private fun Card(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.clip(shape).background(Paper).border(1.dp, Line, shape)) { content() }
}

@Composable
private fun SdkLine(s: SdkStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Pleos SDK", 16, FontWeight.Bold, Mute, maxLines = 1)
        listOf("Vehicle" to s.vehicle, "NaviHelper" to s.navi, "FusedLocation" to s.location, "STT" to s.stt, "TTS" to s.tts, "UxRestrictions" to s.uxr)
            .forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (v.startsWith("OK")) Ink.copy(alpha = 0.7f) else Line))
                    Spacer(Modifier.width(8.dp))
                    Text(k, 16, FontWeight.SemiBold, Mute, maxLines = 1)
                }
            }
    }
}

@Composable
private fun Text(s: String, size: Int, weight: FontWeight, color: Color, maxLines: Int = 2, family: FontFamily = Kr, center: Boolean = false, track: Float = 0f, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(s, style = TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, color = color, lineHeight = (size * 1.32).sp,
        letterSpacing = track.sp,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        lineBreak = androidx.compose.ui.text.style.LineBreak(androidx.compose.ui.text.style.LineBreak.Strategy.Balanced, androidx.compose.ui.text.style.LineBreak.Strictness.Strict, androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = if (center) modifier.fillMaxWidth() else modifier)
}

// ---------- Mate: delegated work (stopped only) ----------

private fun kindLabel(j: Job) = when (j.kind) { "proposal" -> "제안 페이지"; "edit" -> "말로 수정"; "bugfix" -> "코드 수정"; else -> j.kind }

@Composable
private fun MatePanel(jobs: List<Job>, onShow: (String) -> Unit, onApprove: (String) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Label("맡긴 일", jobs.size)
        Spacer(Modifier.height(12.dp))
        jobs.takeLast(3).forEach { j ->
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(Modifier.padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("${kindLabel(j)} · 내 클라우드 컴퓨터", 20, FontWeight.SemiBold, Gleo, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Text(j.statusLine.ifBlank { j.title }, 27, FontWeight.Bold, Ink, maxLines = 1, track = -0.4f)
                        j.summary.firstOrNull()?.let { Spacer(Modifier.height(4.dp)); Text(it, 19, FontWeight.Normal, Mute, maxLines = 1) }
                    }
                    when {
                        j.active -> Text("작업 중", 22, FontWeight.SemiBold, Mute, maxLines = 1)
                        j.waitingApproval -> { Ghost("보기") { onShow(j.id) }; Spacer(Modifier.width(10.dp)); Pill("배포 승인") { onApprove(j.id) } }
                        j.state == "DEPLOYED" -> { Text(if (j.deployBy == "phone") "폰에서 승인" else "차에서 승인", 20, FontWeight.SemiBold, Good, maxLines = 1); Spacer(Modifier.width(12.dp)); Ghost("보기") { onShow(j.id) } }
                        j.done -> Ghost("보기") { onShow(j.id) }
                        else -> Text("실패", 22, FontWeight.SemiBold, Mute, maxLines = 1)
                    }
                }
            }
        }
        PhoneCard(jobs.last())
    }
}

/** "폰으로 이어 보기": the same job on the phone (page served by the driver's compute), for after the driver gets out. */
@Composable
private fun PhoneCard(j: Job, compact: Boolean = false) {
    if (j.mobileUrl.isBlank()) return
    val qr = remember(j.mobileUrl) {
        runCatching {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(j.mobileUrl, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
            val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m.get(x, y)) 0xFF131417.toInt() else 0xFFFFFFFF.toInt())
            bmp.asImageBitmap()
        }.getOrNull()
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (qr != null) Image(qr, "QR", Modifier.size(if (compact) 112.dp else 124.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text("폰으로 이어 보기", 26, FontWeight.Bold, Ink, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text("내려서도 같은 작업을 폰에서 확인하고 승인해요.", 19, FontWeight.Normal, Mute, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(j.mobileUrl.removePrefix("https://").removePrefix("http://"), 16, FontWeight.SemiBold, Gleo, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Ghost(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Line).clickable { onClick() }.padding(horizontal = 28.dp, vertical = 16.dp)) {
        Text(label, 22, FontWeight.Bold, Ink, maxLines = 1)
    }
}

@Composable
private fun ResultView(j: Job, base: String, scroll: HashMap<String, Int>, onClose: () -> Unit, onApprove: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).clickable(enabled = false) {}.padding(horizontal = 44.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(j.statusLine.ifBlank { j.title }, 32, FontWeight.Bold, Ink, maxLines = 1, track = -0.5f)
                Text("${j.engine} · ${j.compute} · 작업 ${j.id}", 17, FontWeight.Normal, Mute, maxLines = 1)
            }
            Ghost("닫기", onClose)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.weight(1f)) {
            when (j.kind) {
                "edit" -> {
                    Pane("수정 전", base + j.beforeUrl, "${j.id}-before", scroll, Modifier.weight(1f))
                    Spacer(Modifier.width(20.dp))
                    Pane("수정 후 · 말로 고친 결과", base + j.resultUrl, "${j.id}-after", scroll, Modifier.weight(1f))
                }
                "bugfix" -> {
                    Pane(if (j.state == "DEPLOYED") "배포본" else "미리보기 · 아직 배포 전", base + (j.deployUrl.ifBlank { j.resultUrl }), "${j.id}-${j.state}", scroll, Modifier.weight(1.35f))
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("에이전트가 고친 내용", 20, FontWeight.SemiBold, Gleo, maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
                                j.summary.take(3).forEach { Text(it, 18, FontWeight.SemiBold, if (it.startsWith("+")) Good else if (it.startsWith("−") || it.startsWith("-")) Color(0xFFD9381E) else Ink, maxLines = 2); Spacer(Modifier.height(6.dp)) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (j.waitingApproval) {
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(InkSoft).clickable { onApprove() }.padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                                Text("배포 승인", 28, FontWeight.Bold, Paper, maxLines = 1, center = true)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("정차해 있을 때만 승인할 수 있어요. 승인 전에는 아무것도 배포되지 않습니다.", 17, FontWeight.Normal, Mute, maxLines = 2)
                        } else {
                            Text("배포됨 · ${if (j.deployBy == "phone") "폰" else "차"}에서 승인", 26, FontWeight.Bold, Good, maxLines = 1)
                        }
                        Spacer(Modifier.height(12.dp))
                        PhoneCard(j, compact = true)
                    }
                }
                else -> Pane(null, base + j.resultUrl, j.id, scroll, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("차가 움직이면 이 화면은 닫히고, 보던 위치는 기억해 둡니다.", 17, FontWeight.Normal, Faint, maxLines = 1)
    }
}

@Composable
private fun Pane(label: String?, url: String, key: String, scroll: HashMap<String, Int>, modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        if (label != null) { Text(label, 20, FontWeight.SemiBold, Gleo, maxLines = 1); Spacer(Modifier.height(8.dp)) }
        val shape = RoundedCornerShape(22.dp)
        Box(Modifier.fillMaxSize().clip(shape).background(Paper).border(1.dp, Line, shape)) { Page(url, key, scroll) }
    }
}

/** The result page itself (served by the driver's compute). Keeps its scroll position across close / reopen. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Page(url: String, key: String, scroll: HashMap<String, Int>) {
    androidx.compose.runtime.key(url) {
        androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
            android.webkit.WebView(c).apply {
                val saved = scroll[key] ?: 0
                var ready = false
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(v: android.webkit.WebView, u: String?) {
                        v.postDelayed({ if (saved > 0) { v.scrollTo(0, saved); android.util.Log.i(PleosBridge.TAG, "RESULT scroll restored $key y=$saved") }; ready = true }, 350)
                    }
                }
                setOnScrollChangeListener { _, _, y, _, _ -> if (ready) scroll[key] = y }
                android.util.Log.i(PleosBridge.TAG, "RESULT open $url")
                loadUrl(url)
            }
        }, onRelease = { android.util.Log.i(PleosBridge.TAG, "RESULT closed $key, scroll saved y=${scroll[key] ?: 0}"); it.destroy() }, modifier = Modifier.fillMaxSize())
    }
}
