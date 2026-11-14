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
import kotlin.math.min

private val Ink = Color(0xFF0E0B09)        // warm black
private val Ink2 = Color(0xFF171210)
private val Line = Color(0xFF2A211C)
private val Ember = Color(0xFFE5622A)      // burnt orange
private val EmberHi = Color(0xFFFF8A4C)
private val Bone = Color(0xFFF3ECE4)
private val Mute = Color(0xFF8E827A)

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
    val cards by brain.cards.collectAsState()
    val items by app.store.items.collectAsState()
    val outbox by app.store.outbox.collectAsState()
    val driving = car.gear != "P" || car.restricted

    Box(
        Modifier.fillMaxSize().background(Ink)
            .background(Brush.radialGradient(listOf(Ember.copy(alpha = 0.16f), Color.Transparent), center = Offset(1400f, 1600f), radius = 1400f))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
            TopBar(car, driving)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // Left: the companion. Same layout in P and D; only density changes.
                Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Orb(app, speaking, onTap = { app.pleos.listen() })
                    Spacer(Modifier.height(36.dp))
                    AnimatedContent(line, label = "line") { l ->
                        Text(l.ifBlank { "말로만. 손은 핸들에." }, size = if (driving) 42 else 36, weight = FontWeight.ExtraBold, color = Bone, maxLines = 2, center = true)
                    }
                    Spacer(Modifier.height(14.dp))
                    if (heard.isNotBlank()) Text("들은 말  “$heard”", 22, FontWeight.Normal, Mute, maxLines = 1, center = true)
                }
                Spacer(Modifier.width(40.dp))
                // Right: hidden while driving (glance budget = 0). Opens only in P.
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (driving) DrivingPanel(car, items.count { it.status == Promise.Status.CLOSED }, items.count { it.status != Promise.Status.CLOSED }) else ParkedPanel(cards, items, outbox) { brain.tapCard(it) }
                }
            }
            SdkStrip(sdk)
        }
    }
}

@Composable
private fun TopBar(car: CarState, driving: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("ARCA", 26, FontWeight.Black, Bone, maxLines = 1, family = En)
        Spacer(Modifier.width(12.dp))
        Text("Drive", 24, FontWeight.Bold, Ember, family = En)
        Spacer(Modifier.weight(1f))
        Pill(if (driving) "주행 중 · 음성 전용" else "주차 · 화면 열림", if (driving) Ember else Color(0xFF3B302A))
        Spacer(Modifier.width(12.dp))
        Pill("GEAR ${car.gear}", Color(0xFF241C18))
        car.socPct?.let { Spacer(Modifier.width(12.dp)); Pill("배터리 $it%", Color(0xFF241C18)) }
    }
}

@Composable
private fun Orb(app: ArcaDriveApp, speaking: Boolean, onTap: () -> Unit) {
    val lvl by app.pleos.level.collectAsState()
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val boost = (min(lvl, 12.0) / 12.0).toFloat() * 0.12f + if (speaking) 0.05f else 0f
    Box(Modifier.size(340.dp).clip(CircleShape).clickable { onTap() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Ember.copy(alpha = 0.55f), Color.Transparent), radius = r), r * (breathe + boost))
            drawCircle(Brush.radialGradient(listOf(EmberHi, Ember, Color(0xFF8C2E10)), center = Offset(r * 0.8f, r * 0.75f), radius = r * 0.7f), r * 0.52f * (breathe + boost / 2))
            // two calm eyes, the ARCA companion
            val ey = r * 0.92f
            drawRoundRect(Bone, topLeft = Offset(r * 0.78f, ey), size = androidx.compose.ui.geometry.Size(r * 0.11f, r * 0.15f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.06f))
            drawRoundRect(Bone, topLeft = Offset(r * 1.11f, ey), size = androidx.compose.ui.geometry.Size(r * 0.11f, r * 0.15f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.06f))
        }
    }
}

@Composable
private fun DrivingPanel(car: CarState, closed: Int, open: Int) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Ink2).border(1.dp, Line, RoundedCornerShape(28.dp)).padding(40.dp),
        verticalArrangement = Arrangement.Center) {
        Text("다음 도착", 22, FontWeight.SemiBold, Mute)
        Spacer(Modifier.height(10.dp))
        Text(car.destination ?: "목적지 없음", 46, FontWeight.ExtraBold, Bone, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        val eta = car.remainSec?.let { if (it < 90) "곧 도착" else "${it / 60}분" } ?: "경로 안내 중"
        val km = car.remainM?.let { String.format("%.1fkm", it / 1000.0) } ?: ""
        Text("$eta  $km", 40, FontWeight.Bold, Ember, family = En)
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$closed", 96, FontWeight.Black, Bone, maxLines = 1, family = En)
            Spacer(Modifier.width(14.dp))
            Text("닫힌 약속", 24, FontWeight.Bold, Mute, maxLines = 1)
            Spacer(Modifier.width(48.dp))
            Text("$open", 96, FontWeight.Black, Ember, maxLines = 1, family = En)
            Spacer(Modifier.width(14.dp))
            Text("남은 약속", 24, FontWeight.Bold, Mute, maxLines = 1)
        }
        Spacer(Modifier.height(34.dp))
        Text("주행 중엔 목록·키보드를 띄우지 않아요. ARCA가 말로 묻고, 말로 닫습니다.", 20, FontWeight.Normal, Mute, maxLines = 2)
    }
}

@Composable
private fun ParkedPanel(cards: List<CompanionBrain.Card>, items: List<Promise>, outbox: List<String>, onTap: (String) -> Unit) {
    val closed = items.filter { it.status == Promise.Status.CLOSED }
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Ink2).border(1.dp, Line, RoundedCornerShape(28.dp)).padding(36.dp)) {
        Text("남은 약속 · 1탭으로 닫기", 26, FontWeight.Bold, Bone)
        Spacer(Modifier.height(14.dp))
        if (cards.isEmpty()) Text("모두 닫혔어요.", 22, FontWeight.Normal, Mute)
        cards.take(3).forEach { c ->
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(22.dp)).background(Ink)
                .border(1.dp, Line, RoundedCornerShape(22.dp)).padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 18.dp)) {
                    Text(c.title, 24, FontWeight.Bold, Bone, maxLines = 2)
                    Text(c.body, 18, FontWeight.Normal, Mute, maxLines = 1)
                }
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Ember).clickable { onTap(c.id) }.padding(horizontal = 26.dp, vertical = 16.dp)) {
                    Text(c.action, 22, FontWeight.Bold, Ink)
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        if (closed.isNotEmpty()) Text("닫힌 약속 ${closed.size} · 증거와 함께", 24, FontWeight.Bold, Ember)
        Spacer(Modifier.height(8.dp))
        closed.takeLast(3).forEach {
            Row { Text("✓", 21, FontWeight.Bold, Ember); Spacer(Modifier.width(10.dp))
                Column { Text(it.counterpart, 21, FontWeight.Bold, Bone); Text(it.evidence, 19, FontWeight.Normal, Bone.copy(alpha = 0.8f), maxLines = 2) } }
            Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun SdkStrip(s: SdkStatus) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("Pleos SDK live", 15, FontWeight.Bold, Ember, maxLines = 1)
        listOf("Vehicle" to s.vehicle, "NaviHelper" to s.navi, "FusedLocation" to s.location, "STT" to s.stt, "TTS" to s.tts, "UxRestrictions" to s.uxr)
            .forEach { (k, v) ->
                val ok = v.startsWith("OK")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) Ember else Color(0xFF5A4E46)))
                    Spacer(Modifier.width(7.dp))
                    Text("$k · $v", 15, FontWeight.Normal, Mute, maxLines = 1)
                }
            }
    }
}

@Composable
private fun Pill(text: String, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(text, 18, FontWeight.Bold, Bone)
    }
}

@Composable
private fun Text(s: String, size: Int, weight: FontWeight, color: Color, maxLines: Int = 2, family: FontFamily = Kr, center: Boolean = false) {
    androidx.compose.material3.Text(s, style = TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, color = color, lineHeight = (size * 1.3).sp,
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
        lineBreak = androidx.compose.ui.text.style.LineBreak(androidx.compose.ui.text.style.LineBreak.Strategy.Balanced, androidx.compose.ui.text.style.LineBreak.Strictness.Strict, androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
