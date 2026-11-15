package app.heyarca.drive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.*

/**
 * The assistant's presence: a dotted 3D "thinking orb" in Hyundai navy.
 *
 * Geometry is a Kotlin port of the MIT-licensed `thinking-orbs` engine (Jakub Antalik,
 * github.com/Jakubantalik/Libraries.dev, packages/thinking-orbs): rotated, depth-shaded,
 * z-sorted dots, depth carried by dot size and ink weight alone. Each assistant state maps
 * to one of the library's modes:
 *   IDLE      -> wave    ("listening" geometry at low amplitude) a slowly breathing dotted sphere
 *   LISTENING -> wave    a waveform rolls through the rings, amplitude follows the mic RMS
 *   SPEAKING  -> wave    faster, amplitude follows the voice
 *   THINKING  -> globe   ("searching")   a scan meridian sweeps the sphere
 *   WORKING   -> orbits  ("working")     particles on tilted orbits (background jobs)
 */
enum class OrbMode { IDLE, LISTENING, THINKING, WORKING, SPEAKING }

/** Hyundai navy (#002C5F) is the ink; far dots fade toward the paper. */
private const val TINT_R = 0f
private const val TINT_G = 44f
private const val TINT_B = 95f

@Composable
fun ThinkingOrb(mode: OrbMode, level: Float, calm: Boolean, modifier: Modifier = Modifier) {
    // one clock per mode, advanced at that mode's own speed, so speed changes never jump
    val clocks = remember { FloatArray(OrbMode.values().size) }
    var tick by remember { mutableIntStateOf(0) }
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "lvl")
    val pace = if (calm) 0.8f else 1f

    // cross-fade between modes (old mode fades out while the new one fades in)
    var shown by remember { mutableStateOf(mode) }
    var previous by remember { mutableStateOf<OrbMode?>(null) }
    val mix = remember { Animatable(1f) }
    LaunchedEffect(mode) {
        if (mode != shown) {
            previous = shown; shown = mode
            mix.snapTo(0f); mix.animateTo(1f, tween(650))
            previous = null
        }
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                if (last != 0L) {
                    val dt = ((n - last) / 1e9f).coerceAtMost(0.05f) * pace
                    OrbMode.values().forEach { m -> clocks[m.ordinal] += dt * speedOf(m) }
                }
                last = n; tick++
            }
        }
    }

    val buf = remember { OrbBuf() }
    Canvas(modifier) {
        tick.let { }   // read the frame tick so the canvas redraws every frame
        val p = previous
        if (p != null) drawMode(buf, p, clocks[p.ordinal], lvl, 1f - mix.value)
        drawMode(buf, shown, clocks[shown.ordinal], lvl, if (p != null) mix.value else 1f)
    }
}

/** Seconds of geometry time per real second. Library presets are tuned for tiny spinners; a hero orb in a car runs calmer. */
private fun speedOf(m: OrbMode) = when (m) {
    OrbMode.IDLE -> 0.9f
    OrbMode.SPEAKING -> 1.7f
    OrbMode.LISTENING -> 2.0f
    OrbMode.THINKING -> 0.95f
    OrbMode.WORKING -> 1.1f
}

private fun DrawScope.drawMode(b: OrbBuf, m: OrbMode, t: Float, level: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    val s = size.minDimension
    b.clear()
    when (m) {
        OrbMode.IDLE -> OrbGeo.wave(b, s, t, amp = 0.32f)
        OrbMode.SPEAKING -> OrbGeo.wave(b, s, t, amp = 0.5f + 0.3f * level)
        OrbMode.LISTENING -> OrbGeo.wave(b, s, t, amp = 0.6f + 0.3f * level)
        OrbMode.THINKING -> OrbGeo.globe(b, s, t)
        OrbMode.WORKING -> OrbGeo.orbits(b, s, t)
    }
    val ox = (size.width - s) / 2f; val oy = (size.height - s) / 2f
    val n = b.sortByDepth()
    for (k in 0 until n) {
        val i = b.order(k)
        val w = b.w[i].coerceIn(0f, 1f)
        val a = (b.a[i] * alpha).coerceIn(0f, 1f)
        if (a < 0.02f) continue
        val c = Color(
            (TINT_R + (255f - TINT_R) * w) / 255f,
            (TINT_G + (255f - TINT_G) * w) / 255f,
            (TINT_B + (255f - TINT_B) * w) / 255f, a,
        )
        drawCircle(c, b.r[i], Offset(ox + b.x[i], oy + b.y[i]))
    }
}

/** Flat, allocation-free dot buffer (one frame). */
class OrbBuf(cap: Int = 6000) {
    val x = FloatArray(cap); val y = FloatArray(cap); val z = FloatArray(cap)
    val r = FloatArray(cap); val w = FloatArray(cap); val a = FloatArray(cap)
    private val keys = LongArray(cap)
    var n = 0; private set
    fun clear() { n = 0 }
    fun add(px: Float, py: Float, pz: Float, pr: Float, white: Float, alpha: Float = 1f, rMin: Float = 0.3f) {
        if (n >= x.size || alpha < 0.02f) return
        x[n] = px; y[n] = py; z[n] = pz; r[n] = max(rMin, pr); w[n] = white; a[n] = alpha; n++
    }
    /** z-sort far -> near (painter's order) with a primitive sort: quantised depth in the high bits, index in the low bits. */
    fun sortByDepth(): Int {
        for (i in 0 until n) keys[i] = ((z[i] * 64f).toLong() + (1L shl 30) shl 20) or i.toLong()
        java.util.Arrays.sort(keys, 0, n)
        return n
    }
    fun order(k: Int) = (keys[k] and 0xFFFFF).toInt()
}

/** Geometry (pure math over size, t). Faithful to thinking-orbs/src/engine. */
object OrbGeo {
    private fun hashD(a: Double, b: Double): Double { val h = sin(a * 12.9898 + b * 78.233) * 43758.5453; return h - floor(h) }
    private fun angleDelta(a: Double, b: Double) = atan2(sin(a - b), cos(a - b))
    private fun rScale(size: Float) = (size / 300f).pow(0.6f)

    private class Proj(yaw: Double, tilt: Double, val cx: Double, val cy: Double, val scale: Double) {
        val st = sin(tilt); val ct = cos(tilt); val sy = sin(yaw); val cyw = cos(yaw)
        var px = 0.0; var py = 0.0; var pz = 0.0
        fun p(x: Double, y: Double, z: Double) {
            val x1 = x * cyw + z * sy
            val z1 = -x * sy + z * cyw
            val y1 = y * ct - z1 * st
            val z2 = y * st + z1 * ct
            px = cx + x1 * scale; py = cy - y1 * scale; pz = z2
        }
    }

    private fun fib(i: Int, n: Int, out: DoubleArray) {
        val golden = PI * (3 - sqrt(5.0))
        val y = 1 - (2 * (i + 0.5)) / n
        val rad = sqrt(1 - y * y)
        val a = i * golden
        out[0] = rad * cos(a); out[1] = y; out[2] = rad * sin(a)
    }

    /** "breathing" (ribbon engine, faceOn): a face-on band whose radius undulates. 11 lanes (64 px preset) x 72 segs. */
    fun ring(b: OrbBuf, size: Float, t: Float, wobMul: Float, lanesIn: Int = 11, segs: Int = 72, rBase: Float = 1.05f, rDepth: Float = 1.63f) {
        val cx = size / 2.0; val cy = size / 2.0
        val R = size / 2.0 * 0.78
        val camTilt = 0.3
        val pt = Proj(0.0, camTilt, cx, cy, 1.0)    // spin 0 (preset)
        val rs = rScale(size)
        val ya = 0.0
        val ta = -camTilt
        val ux = cos(ya); val uy = 0.0; val uz = sin(ya)
        val vx = -uz * sin(ta); val vy = cos(ta); val vz = ux * sin(ta)
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val wobAmp = 0.23 * wobMul
        val baseR = R / (1 + 0.85 * wobAmp)
        val lanes = max(1, lanesIn)
        for (w in 0 until lanes) {
            val laneOff = (w - (lanes - 1) / 2.0) * 0.075
            val edge = abs(w - (lanes - 1) / 2.0) / max(1.0, (lanes - 1) / 2.0)
            for (k in 0 until segs) {
                val a = k.toDouble() / segs * 2 * PI
                val wob = (0.16 * sin(a * 3 - t * 1.7 + w * 0.22) + 0.07 * sin(a * 5 + t * 1.1)) * wobMul
                val radial = 1 + wob
                val x = ux * cos(a) + vx * sin(a) + nx * laneOff
                val y = uy * cos(a) + vy * sin(a) + ny * laneOff
                val z = uz * cos(a) + vz * sin(a) + nz * laneOff
                val l = sqrt(x * x + y * y + z * z)
                val rr = baseR * radial
                pt.p(x / l * rr, y / l * rr, z / l * rr)
                val depth = (pt.pz / R + 1) / 2
                b.add(pt.px.toFloat(), pt.py.toFloat(), pt.pz.toFloat(),
                    ((rBase + rDepth * depth) * (1 - 0.25 * edge) * rs).toFloat(),
                    (0.52 - 0.44 * depth + 0.18 * edge).toFloat(), (0.4 + 0.6 * depth).toFloat())
            }
        }
    }

    /** "listening": a waveform rolls through a lat/long sphere. */
    fun wave(b: OrbBuf, size: Float, t: Float, amp: Float = 1f, rings: Int = 15, lonDensity: Int = 40, rBase: Float = 0.6f, rDepth: Float = 1.7f) {
        val cx = size / 2.0; val cy = size / 2.0
        val R = size / 2.0 * 0.874
        val pt = Proj(t * 0.18, 0.38, cx, cy, 1.0)
        val rs = rScale(size)
        for (ri in 0..rings) {
            val lat = -PI / 2 + ri.toDouble() / rings * PI
            val cosLat = cos(lat); val sinLat = sin(lat)
            val w = (0.62 * sin(t * 2.1 - ri * 0.52) + 0.38 * sin(t * 1.27 + ri * 0.83)) * amp
            val rr = R * (0.88 + 0.08 * w)
            val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = lj.toDouble() / lonCount * 2 * PI
                pt.p(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
                val depth = (pt.pz / R + 1) / 2
                val crest = max(0.0, w)
                b.add(pt.px.toFloat(), pt.py.toFloat(), pt.pz.toFloat(),
                    ((rBase + rDepth * depth) * (1 + 0.4 * crest) * rs).toFloat(),
                    (0.66 - 0.56 * depth - 0.1 * crest).toFloat())
            }
        }
    }

    /** "searching": lat/long field, a scan meridian sweeps across (scanMul/dimBase from the 64 px preset). */
    fun globe(b: OrbBuf, size: Float, t: Float, latRings: Int = 17, lonDensity: Int = 44, scanMul: Double = 1.6, dimBase: Double = 0.45) {
        val spin = 0.5
        val cx = size / 2.0; val cy = size / 2.0
        val radius = size / 2.0 * 0.82
        val tilt = 0.4 + 0.06 * sin(t * 0.35)
        val pt = Proj(t * spin, tilt, cx, cy, radius)
        val scan = t * (spin + (1.7 - spin) * scanMul)
        val rs = rScale(size)
        for (li in 0..latRings) {
            val lat = -PI / 2 + li.toDouble() / latRings * PI
            val cosLat = cos(lat); val sinLat = sin(lat)
            val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = lj.toDouble() / lonCount * 2 * PI
                pt.p(cosLat * cos(lon), sinLat, cosLat * sin(lon))
                val z = pt.pz
                val depth = (z + 1) / 2
                val d = angleDelta(lon + t * spin, scan)
                val boost = exp(-(d * d) / 0.18) * max(0.0, z)
                b.add(pt.px.toFloat(), pt.py.toFloat(), z.toFloat(),
                    ((0.6 + 1.7 * depth + 1.0 * boost) * rs).toFloat(),
                    (0.62 - 0.54 * depth).toFloat(),
                    (dimBase + (1 - dimBase) * min(1.0, boost)).toFloat())
            }
        }
    }

    /** "working": the library's orbits mode, tuned for a hero orb — a ghosted dotted sphere as the core,
     *  3 tilted rings drawn as faint dotted paths, and 2 bright particles per ring with fading comet tails. */
    fun orbits(b: OrbBuf, size: Float, t: Float, progress: Float = 0f) {
        // ghost core (globe geometry at low ink)
        val cx = size / 2.0; val cy = size / 2.0
        val R = size / 2.0 * 0.82
        val rs = rScale(size)
        val core = Proj(t * 0.25, 0.4, cx, cy, R * 0.62)
        val latRings = 13; val lonDensity = 30
        for (li in 0..latRings) {
            val lat = -PI / 2 + li.toDouble() / latRings * PI
            val cosLat = cos(lat); val sinLat = sin(lat)
            val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = lj.toDouble() / lonCount * 2 * PI
                core.p(cosLat * cos(lon), sinLat, cosLat * sin(lon))
                val depth = (core.pz + 1) / 2
                b.add(core.px.toFloat(), core.py.toFloat(), (core.pz * 0.6).toFloat(), ((0.5 + 1.2 * depth) * rs).toFloat(), (0.66 - 0.5 * depth).toFloat(), (0.35 + 0.4 * depth).toFloat())
            }
        }
        val pt = Proj(t * 0.12, 0.3, cx, cy, 1.0)
        val tilts = doubleArrayOf(0.35, 1.15, 2.05)
        for ((ri, th) in tilts.withIndex()) {
            val ro = R * (0.86 + 0.06 * ri)
            val phi = 1.05 + 0.25 * ri
            val nx = sin(phi) * cos(th); val ny = cos(phi); val nz = sin(phi) * sin(th)
            var ux = -ny; var uy = nx; val uz = 0.0
            val ul = max(1e-6, sqrt(ux * ux + uy * uy)); ux /= ul; uy /= ul
            val vx = ny * uz - nz * uy; val vy = nz * ux - nx * uz; val vz = nx * uy - ny * ux
            val speed = 0.55 + 0.18 * ri
            val ghostN = 90
            for (k in 0 until ghostN) {
                val a = k.toDouble() / ghostN * 2 * PI
                pt.p((ux * cos(a) + vx * sin(a)) * ro, (uy * cos(a) + vy * sin(a)) * ro, (uz * cos(a) + vz * sin(a)) * ro)
                val depth = (pt.pz / ro + 1) / 2
                b.add(pt.px.toFloat(), pt.py.toFloat(), pt.pz.toFloat(), (0.9 * rs).toFloat(), 0.62f, (0.32 + 0.38 * depth).toFloat())
            }
            for (m in 0 until 2) {
                val head = t * speed + m * PI + ri * 2.1
                for (j in 0 until 18) {          // comet tail: newest first
                    val a = head - j * 0.035
                    pt.p((ux * cos(a) + vx * sin(a)) * ro, (uy * cos(a) + vy * sin(a)) * ro, (uz * cos(a) + vz * sin(a)) * ro)
                    val depth = (pt.pz / ro + 1) / 2
                    val fade = 1.0 - j / 18.0
                    b.add(pt.px.toFloat(), pt.py.toFloat(), (pt.pz + 0.01 * (18 - j)).toFloat(),
                        ((0.9 + 1.2 * depth) * (0.55 + 0.45 * fade) * rs).toFloat(),
                        (0.36 - 0.26 * depth).toFloat(), (fade * fade).toFloat())
                }
            }
        }
    }
}
