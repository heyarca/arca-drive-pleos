package app.heyarca.drive

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

private val Ink = Color(0xFF0E0B09)        // warm black
private val Ink2 = Color(0xFF171210)
private val Line = Color(0xFF2A211C)
private val Ember = Color(0xFFE5622A)      // burnt orange
private val EmberHi = Color(0xFFFF8A4C)
private val Bone = Color(0xFFF3ECE4)
private val Mute = Color(0xFF8E827A)
private val Faint = Color(0xFF5E534C)

private val Kr = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)
private val En = FontFamily(Font(R.font.satoshi_bold, FontWeight.Bold), Font(R.font.satoshi_black, FontWeight.Black))

class MainActivity : ComponentActivity() {
    private lateinit var brain: CompanionBrain
    private lateinit var app: ArcaDriveApp

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ArcaDriveApp
        brain = app.brain
        handleGleo(intent)
        setContent { Screen(app, brain) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleGleo(intent) }

    /** Gleo Interface: Gleo AI -> startActivity(action, ACTION_PARAM json) -> callback broadcast. */
    private fun handleGleo(i: Intent?) {
        val action = i?.action ?: return
        if (action !in setOf("BRIEF_MY_PROMISES", "ADD_PROMISE", "SEND_ARRIVAL_UPDATE")) return
        val p = runCatching { JSONObject(i.getStringExtra("ACTION_PARAM")?.trim('"')?.replace("\\\"", "\"") ?: "{}") }.getOrDefault(JSONObject())
        val (status, msg) = when (action) {
            "BRIEF_MY_PROMISES" -> { brain.briefOnDeparture(); "success" to "Briefed ${app.store.open().size} open promises." }
            "ADD_PROMISE" -> {
                val who = p.optString("counterpart"); val what = p.optString("content")
                if (who.isBlank() || what.isBlank()) "asking" to "The counterpart and the promise content are required."
                else { app.store.add(who, what, p.optString("dueDateTime"), "Gleo"); "success" to "The promise was saved." }
            }
            else -> { brain.onNear(); "success" to "The arrival update was sent." }
        }
        sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").apply {
            putExtra("packageName", packageName)
            putExtra("response", JSONObject().put("functionName", action).put("status", status).put("message", msg).put("mode", "silent").toString())
        })
    }
}

@Composable
private fun Screen(app: ArcaDriveApp, brain: CompanionBrain) {
    val car by app.pleos.car.collectAsState()
    val sdk by app.pleos.sdk.collectAsState()
    val line by brain.line.collectAsState()
    val heard by app.pleos.heard.collectAsState()
    val speaking by app.pleos.speaking.collectAsState()
    val listening by app.pleos.listening.collectAsState()
    val level by app.pleos.level.collectAsState()
    val items by app.store.items.collectAsState()
    val outbox by app.store.outbox.collectAsState()
    val driving = car.gear != "P" || car.restricted
    val mode = when { listening -> OrbMode.LISTENING; speaking -> OrbMode.SPEAKING; else -> OrbMode.IDLE }

    // one spring drives the whole layout between "driving" (orb centre stage) and "parked" (orb left, cards right)
    val p by animateFloatAsState(if (driving) 0f else 1f, spring(dampingRatio = 0.86f, stiffness = 60f), label = "park")

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val W = maxWidth; val H = maxHeight
        val orbBox = lerpDp(H * 0.66f, H * 0.72f, p)
        val cx = lerpDp(W / 2, W * 0.255f, p)
        val cy = lerpDp(H * 0.43f, H * 0.47f, p)
        val textW = lerpDp(W * 0.72f, W * 0.42f, p)

        // ambient light that sits behind the orb and follows it
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(cx.toPx(), cy.toPx())
            drawRect(Brush.radialGradient(listOf(Ember.copy(alpha = 0.13f), Ember.copy(alpha = 0.04f), Color.Transparent), center = c, radius = size.width * 0.55f))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), startY = size.height * 0.6f, endY = size.height))
        }

        // the orb
        Box(Modifier.offset(cx - orbBox / 2, cy - orbBox / 2).size(orbBox)) {
            LivingOrb(mode, (level / 10.0).toFloat(), outbox.size, calm = driving, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(orbBox * 0.5f).clip(CircleShape).clickable { app.pleos.listen() })
        }

        // the one line
        Box(Modifier.offset(cx - textW / 2, cy + orbBox * 0.40f).width(textW)) {
            val shown = if (listening && heard.isNotBlank()) "“$heard…" else line.ifBlank { if (driving) "말로만. 손은 핸들에." else "" }
            AnimatedContent(shown to (listening && heard.isNotBlank()), transitionSpec = {
                (fadeIn(tween(700, delayMillis = 120)) + slideInVertically(spring(dampingRatio = 0.9f, stiffness = 120f)) { it / 5 })
                    .togetherWith(fadeOut(tween(260)))
            }, label = "line") { (l, isHeard) ->
                // first line = what ARCA says; an optional second line (a quoted draft) is quieter
                val main = l.substringBefore('\n'); val sub = l.substringAfter('\n', "")
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(main, size = if (driving) 50 else 38, weight = if (isHeard) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (isHeard) Bone.copy(alpha = 0.78f) else Bone, maxLines = 2, center = true, track = -0.6f)
                    if (sub.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(sub, size = if (driving) 36 else 28, weight = FontWeight.Normal, color = Bone.copy(alpha = 0.62f), maxLines = 2, center = true, track = -0.3f)
                    }
                }
            }
        }

        // top: quiet status, no chrome
        Row(Modifier.fillMaxWidth().padding(horizontal = 64.dp, vertical = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (driving) Ember else Faint))
            Spacer(Modifier.width(14.dp))
            Text(if (driving) "주행 중 · 음성으로만" else "주차 중", 26, FontWeight.SemiBold, if (driving) Bone.copy(alpha = 0.85f) else Mute)
            Spacer(Modifier.weight(1f))
            car.destination?.let { d ->
                Text(d, 26, FontWeight.SemiBold, Mute)
                val eta = car.remainSec?.let { if (it < 90) "곧 도착" else "${it / 60}분" }
                val km = car.remainM?.let { String.format("%.1f km", it / 1000.0) }
                listOfNotNull(eta, km).takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.width(18.dp))
                    Text(it.joinToString("  ·  "), 26, FontWeight.Bold, Bone.copy(alpha = 0.9f), family = En)
                }
            }
        }

        // parked: elegant cards, only when the car is in P
        val open = items.filter { it.status != Promise.Status.CLOSED }
        val closed = items.filter { it.status == Promise.Status.CLOSED }
        Box(Modifier.offset(x = W * 0.505f, y = 110.dp).width(W * 0.495f - 64.dp).height(H - 200.dp)
            .graphicsLayer { alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f); translationX = (1f - p) * 80f }) {
            if (p > 0.02f) ParkedCards(open, closed) { brain.tapCard(it) }
        }

        // footer: which car SDKs are live (parked only, very quiet)
        if (p > 0.02f) Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 64.dp, vertical = 30.dp).alpha(p)) { SdkLine(sdk) }
    }
}

private fun lerpDp(a: Dp, b: Dp, t: Float): Dp = a + (b - a) * t

@Composable
private fun ParkedCards(open: List<Promise>, closed: List<Promise>, onTap: (String) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Label("남은 약속", open.size)
        Spacer(Modifier.height(16.dp))
        if (open.isEmpty()) Text("모두 닫혔어요.", 26, FontWeight.SemiBold, Mute)
        open.take(3).forEachIndexed { i, pr ->
            Glass(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Row(Modifier.padding(horizontal = 34.dp, vertical = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 20.dp)) {
                        Text(pr.counterpart, 24, FontWeight.SemiBold, EmberHi, maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        Text(pr.content, 34, FontWeight.Bold, Bone, maxLines = 2, track = -0.5f)
                        Spacer(Modifier.height(8.dp))
                        Text(listOf(pr.due.takeLast(5).takeIf { pr.due.isNotBlank() }?.let { "오늘 $it" }, pr.source).filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  "),
                            21, FontWeight.Normal, Mute, maxLines = 1)
                    }
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(EmberHi, Ember)))
                        .clickable { onTap(pr.id) }.padding(horizontal = 36.dp, vertical = 20.dp)) {
                        Text("보내기", 26, FontWeight.Bold, Ink)
                    }
                }
            }
        }
        if (closed.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Label("닫힌 약속", closed.size)
            Spacer(Modifier.height(14.dp))
            closed.takeLast(3).reversed().forEach { pr ->
                Row(Modifier.padding(bottom = 18.dp)) {
                    Text("✓", 24, FontWeight.Bold, Mute, maxLines = 1)
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text(pr.counterpart, 24, FontWeight.SemiBold, Bone.copy(alpha = 0.72f), maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(pr.evidence, 21, FontWeight.Normal, Mute, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(s: String, n: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(s, 26, FontWeight.SemiBold, Bone.copy(alpha = 0.8f), maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text("$n", 26, FontWeight.Bold, EmberHi, maxLines = 1, family = En)
    }
}

@Composable
private fun Glass(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(modifier.clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF241B17), Color(0xFF161110))))
        .border(1.dp, Brush.linearGradient(listOf(Ember.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))), shape)) {
        content()
    }
}

@Composable
private fun SdkLine(s: SdkStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Pleos SDK", 16, FontWeight.Bold, Mute, maxLines = 1, family = En)
        listOf("Vehicle" to s.vehicle, "NaviHelper" to s.navi, "FusedLocation" to s.location, "STT" to s.stt, "TTS" to s.tts, "UxRestrictions" to s.uxr)
            .forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (v.startsWith("OK")) Bone.copy(alpha = 0.55f) else Color(0xFF3A302A)))
                    Spacer(Modifier.width(8.dp))
                    Text(k, 16, FontWeight.Bold, Mute.copy(alpha = 0.8f), maxLines = 1, family = En)
                }
            }
    }
}

@Composable
private fun Text(s: String, size: Int, weight: FontWeight, color: Color, maxLines: Int = 2, family: FontFamily = Kr, center: Boolean = false, track: Float = 0f) {
    androidx.compose.material3.Text(s, style = TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, color = color, lineHeight = (size * 1.32).sp,
        letterSpacing = track.sp,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        lineBreak = androidx.compose.ui.text.style.LineBreak(androidx.compose.ui.text.style.LineBreak.Strategy.Balanced, androidx.compose.ui.text.style.LineBreak.Strictness.Strict, androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = if (center) Modifier.fillMaxWidth() else Modifier)
}
