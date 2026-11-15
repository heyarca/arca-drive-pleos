package app.heyarca.drive

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.launch
import kotlin.math.sin

/** What the orb is doing. Driven by real SDK state: STT recognition, (thinking), TTS playback, outbox sends. */
enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING }

// Hyundai Motor CI colours (hyundai.com/kr/ko/info/ci/ci-color)
private val HyundaiBlue = Color(0xFF002C5F)
private val ActiveBlue = Color(0xFF00AAD2)
private val SkyBlue = Color(0xFFAACAE6)

/**
 * A navy "thinking" orb.
 *  - IDLE: deep Hyundai Blue sphere, slow breathing (5 s), slow inner current.
 *  - LISTENING: a sky-blue halo that follows the mic RMS from SpeechToText.
 *  - THINKING: (between the driver's last word and the answer) the inner current speeds up,
 *    a soft light band sweeps around the rim and the body pulses ~1.6 Hz.
 *  - SPEAKING: the body swells with the TTS RMS.
 *  - sent: [sentCount] increments -> one soft ring rolls outward and fades (no flashing).
 * API 33+ uses an AGSL RuntimeShader; older platforms fall back to layered radial gradients.
 */
@Composable
fun LivingOrb(mode: OrbMode, level: Float, sentCount: Int, calm: Boolean, modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n -> if (last != 0L) t += ((n - last) / 1e9f).coerceAtMost(0.05f); last = n }
        }
    }
    val listen by animateFloatAsState(if (mode == OrbMode.LISTENING) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessVeryLow), label = "listen")
    val think by animateFloatAsState(if (mode == OrbMode.THINKING) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow), label = "think")
    val speak by animateFloatAsState(if (mode == OrbMode.SPEAKING) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessVeryLow), label = "speak")
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "lvl")
    val synth = if (mode == OrbMode.SPEAKING) (0.35f + 0.25f * sin(t * 5.1f) + 0.2f * sin(t * 8.3f + 1.3f)).coerceIn(0f, 1f) else 0f
    val energy = maxOf(lvl, synth * speak)
    val pace = if (calm) 0.7f else 1f

    val ring = remember { Animatable(1f) }
    var seen by remember { mutableIntStateOf(sentCount) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sentCount) {
        if (sentCount > seen) scope.launch { ring.snapTo(0f); ring.animateTo(1f, tween(2200, easing = FastOutSlowInEasing)) }
        seen = sentCount
    }

    Box(modifier) {
        if (Build.VERSION.SDK_INT >= 33) ShaderOrb(t * pace, t, energy, listen, think, speak) else FallbackOrb(t * pace, t, energy, listen, think)
        Canvas(Modifier.fillMaxSize()) { drawRing(ring.value) }
    }
}

private fun DrawScope.drawRing(p: Float) {
    if (p >= 1f) return
    val base = size.minDimension * 0.28f
    val r = base + (size.minDimension * 0.5f - base) * p
    val a = (1f - p) * (1f - p) * 0.55f
    val w = 0.10f + 0.12f * p
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent,
            (1f - w).coerceIn(0f, 1f) to Color.Transparent,
            (1f - w / 2f) to SkyBlue.copy(alpha = a),
            1f to Color.Transparent,
            center = center, radius = r,
        ),
        radius = r,
    )
}

private const val ORB_AGSL = """
uniform float2 res;
uniform float t;
uniform float tr;
uniform float energy;
uniform float listen;
uniform float think;
uniform float speak;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}
float blob(float2 s, float2 c, float rad) {
    float2 d = s - c;
    return exp(-dot(d, d) / (rad * rad));
}

// Deep Hyundai Blue glass sphere with cool light currents (Active Blue / Sky Blue) inside.
// THINKING: faster current, a light band sweeping around the rim, a quick pulse.
half4 main(float2 fc) {
    float s = 0.5 * min(res.x, res.y);
    float2 uv = (fc - 0.5 * res) / s;
    float r = length(uv);

    float pulse = 0.5 + 0.5 * sin(tr * 10.0);
    float R = 0.50 * (1.0 + 0.035 * sin(t * 1.25) + 0.07 * energy + 0.02 * speak + 0.03 * think * pulse);

    float d0 = max(r - R, 0.0);
    float g = exp(-d0 * 10.0) * (0.42 + 0.25 * energy + 0.18 * think * pulse) + exp(-d0 * 3.5) * (0.14 + 0.12 * energy + 0.06 * think);
    float halo = exp(-d0 * 5.0) * listen * (0.22 + 0.30 * energy);
    float edge = smoothstep(1.0, 0.72, r);
    g *= edge; halo *= edge;
    float3 outCol = float3(0.02, 0.20, 0.42) * g + float3(0.55, 0.75, 0.92) * halo;
    float outA = clamp(g + halo, 0.0, 1.0);

    float2 p = uv / R;
    float d = length(p);
    if (d >= 1.02) return half4(half3(outCol), half(outA));

    float z = sqrt(clamp(1.0 - d * d, 0.0, 1.0));
    float2 q = p * (1.0 + 0.35 * (1.0 - z));
    float sp = 0.06 + 0.10 * speak + 0.05 * energy + 0.30 * think;
    float2 w = float2(fbm(q * 1.4 + float2(t * sp, 0.0)), fbm(q * 1.4 + float2(3.1, 3.1 - t * sp)));
    float2 sw = p + (0.18 + 0.10 * think) * (w - 0.5);

    float sp2 = 0.22 + 0.25 * speak + 0.55 * think;
    float b1 = blob(sw, float2(0.40 * sin(t * sp2 * 0.9), 0.36 * cos(t * sp2 * 0.7)), 0.70);
    float b2 = blob(sw, float2(0.42 * sin(t * sp2 * 0.6 + 2.1), 0.38 * sin(t * sp2 * 0.8 + 0.4)), 0.66);
    float b3 = blob(sw, float2(0.38 * cos(t * sp2 * 0.5 + 4.0), 0.40 * sin(t * sp2 * 0.55 + 3.0)), 0.62);

    float k = 0.85 + 0.5 * energy + 0.35 * think * pulse;
    // body: Hyundai Blue #002C5F, a touch lighter towards the centre
    float3 col = float3(0.0, 0.172, 0.373) * (0.62 + 0.28 * z);
    col += k * (float3(0.0, 0.05, 0.09) * b1 + float3(0.04, 0.07, 0.11) * b2 + float3(0.0, 0.03, 0.07) * b3);

    // thinking: a light band orbiting the rim
    float ang = atan(p.y, p.x);
    float sweep = pow(0.5 + 0.5 * cos(ang - tr * 3.2), 10.0);
    float band = smoothstep(0.55, 0.95, d) * sweep * think;
    col += float3(0.45, 0.70, 0.90) * band * 0.6;

    float fres = pow(1.0 - z, 3.0);
    col += float3(0.10, 0.26, 0.42) * fres * (1.0 + 0.6 * listen + 0.5 * think);
    col += float3(0.12, 0.18, 0.26) * pow(z, 3.0) * (0.25 + 0.9 * energy + 0.4 * think * pulse);
    col /= max(max(col.r, max(col.g, col.b)), 1.0);

    float a = smoothstep(1.02, 0.90, d);
    float3 c = col * a + outCol * (1.0 - a);
    return half4(half3(c), half(a + outA * (1.0 - a)));
}
"""

@RequiresApi(33)
@Composable
private fun ShaderOrb(t: Float, tr: Float, energy: Float, listen: Float, think: Float, speak: Float) {
    val shader = remember { RuntimeShader(ORB_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(Modifier.fillMaxSize()) {
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("t", t)
        shader.setFloatUniform("tr", tr)
        shader.setFloatUniform("energy", energy)
        shader.setFloatUniform("listen", listen)
        shader.setFloatUniform("think", think)
        shader.setFloatUniform("speak", speak)
        drawRect(brush)
    }
}

@Composable
private fun FallbackOrb(t: Float, tr: Float, energy: Float, listen: Float, think: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val s = size.minDimension / 2
        val pulse = 0.5f + 0.5f * sin(tr * 10f)
        val R = s * 0.5f * (1f + 0.035f * sin(t * 1.25f) + 0.07f * energy + 0.03f * think * pulse)
        drawCircle(Brush.radialGradient(listOf(ActiveBlue.copy(alpha = 0.22f + 0.2f * energy + 0.15f * listen), ActiveBlue.copy(alpha = 0.05f), Color.Transparent), center, s), s)
        drawCircle(HyundaiBlue, R)
        val sp = 0.22f + 0.55f * think
        listOf(Triple(ActiveBlue, 0f, 0.70f), Triple(SkyBlue, 2.1f, 0.66f), Triple(Color(0xFF1E5A96), 4.0f, 0.62f)).forEach { (c, ph, rad) ->
            val o = center + Offset(R * 0.4f * sin(t * sp * 0.8f + ph), R * 0.38f * sin(t * sp * 0.7f + ph + 0.5f))
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.35f + 0.2f * energy), Color.Transparent), o, R * rad), R, center)
        }
        drawCircle(Brush.radialGradient(0.7f to Color.Transparent, 1f to SkyBlue.copy(alpha = 0.35f + 0.2f * listen), center = center, radius = R), R)
        if (think > 0.01f) {
            val start = (tr * 3.2f * 57.2958f) % 360f
            drawArc(SkyBlue.copy(alpha = 0.6f * think), start, 70f, false, topLeft = center - Offset(R * 0.92f, R * 0.92f),
                size = androidx.compose.ui.geometry.Size(R * 1.84f, R * 1.84f), style = Stroke(width = R * 0.05f, cap = StrokeCap.Round))
        }
    }
}
