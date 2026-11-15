package app.mate.gleo

import android.content.Context
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Publish a finished file to shared storage (Movies/Mate, Documents/Mate) so the car's gallery / file apps see it. */
object Publish {
    fun toShared(ctx: Context, f: File, mime: String, movies: Boolean): String? = runCatching {
        val r = ctx.contentResolver
        val coll = if (movies) android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                   else android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val rel = (if (movies) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_DOCUMENTS) + "/Mate"
        // replace an older copy with the same name
        r.delete(coll, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(f.name, "$rel%"))
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
        }
        val uri = r.insert(coll, v) ?: return null
        r.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        Log.i(PleosBridge.TAG, "PUBLISHED $rel/${f.name} (${f.length()} bytes) $uri")
        "$rel/${f.name}"
    }.onFailure { Log.w(PleosBridge.TAG, "publish ${f.name}", it) }.getOrNull()
}

/* ------------------------------------------------------------------------------------------
 * Moment 1 — "오늘 회의 요약해서 팀에 보내놔"
 * Reads today's meeting transcript (in production: the meeting recorder's transcript; in this
 * build: the file in files/meetings/, seeded from assets), extracts decisions and action items
 * with owners and due dates, writes a summary file and a team-channel DRAFT. Nothing leaves the
 * car until the driver confirms it while parked.
 * ------------------------------------------------------------------------------------------ */
data class MeetingSummary(
    val title: String,
    val decisions: List<String>,
    val actions: List<Triple<String, String, String>>,   // owner, task, due
    val draft: String,
    val file: File,
)

object MeetingDelegate {
    private val DECISION = Regex("(하기로|결정|확정|가기로|으로 가자|로 가죠|합의)")
    private val ACTION = Regex("(까지|맡아|담당|보내|준비|정리해|공유해|올려|챙겨)")
    private val DUE = Regex("(오늘|내일|모레|이번 주 [월화수목금토일]요일|다음 주 [월화수목금토일]요일|이번 주|다음 주|이번 달 말|[월화수목금토일]요일)(\\s*(오전|오후))?(\\s*\\d{1,2}시)?")
    private val ASSIGN = Regex("(제가|저는|님이|씨가|맡아|담당)")

    fun latestTranscript(ctx: Context): File? {
        val dir = File(ctx.getExternalFilesDir(null), "meetings").apply { mkdirs() }
        if (dir.listFiles().isNullOrEmpty()) runCatching {
            ctx.assets.list("meetings")?.forEach { n -> ctx.assets.open("meetings/$n").use { i -> File(dir, n).outputStream().use { i.copyTo(it) } } }
        }
        return dir.listFiles { f -> f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() }
    }

    fun summarize(ctx: Context, transcript: File): MeetingSummary {
        val lines = transcript.readLines().map { it.trim() }.filter { it.isNotBlank() }
        val title = lines.firstOrNull { it.startsWith("#") }?.removePrefix("#")?.trim() ?: transcript.nameWithoutExtension
        val people = lines.mapNotNull { Regex("^\\[[^]]*]\\s*([^:]+):").find(it)?.groupValues?.get(1)?.trim() }.distinct()
        val said = lines.mapNotNull { l -> Regex("^\\[[^]]*]\\s*([^:]+):\\s*(.+)$").find(l)?.let { it.groupValues[1].trim() to it.groupValues[2].trim() } }

        val decisions = said.filter { (_, s) -> DECISION.containsMatchIn(s) && !s.endsWith("?") }
            .map { (_, s) -> s.split(Regex("(?<=[.!])\\s+")).first { DECISION.containsMatchIn(it) } }
            .map { tidy(it).replace(Regex("^(좋아요|네|아|그럼|동의합니다)[.,]?\\s*"), "")
                .replace("빼죠", "제외").replace(Regex("(하기로 하죠|하죠|가죠|가자)$"), "") }
            .distinct().take(3)

        val actions = said.filter { (_, s) -> ACTION.containsMatchIn(s) && ASSIGN.containsMatchIn(s) && !s.endsWith("?") && !DECISION.containsMatchIn(s.substringBefore(",")) }
            .map { (who, s0) ->
                val s = s0.split(Regex("(?<=[.!])\\s+")).first { ACTION.containsMatchIn(it) }
                // "제가" -> the speaker; "OO 매니저님이" -> that person
                val named = people.firstOrNull { p -> p != who && s.contains(p.substringBefore(" ")) }
                val owner = (named ?: who).substringBefore(" ")
                val due = DUE.find(s)?.value?.trim().orEmpty()
                val task = tidy(s).replace(Regex("^(제가|저는|그럼|네,?|그러면)\\s*"), "")
                    .replace(Regex(Regex.escape(owner) + "\\s*\\S*님이\\s*"), "").replace("제가 ", "")
                Triple(owner, task, due)
            }.distinctBy { it.second }.take(4)

        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("M/d"))
        val draft = buildString {
            append("[$date $title 요약]\n")
            append("결정\n"); decisions.forEach { append("· $it\n") }
            append("할 일\n"); actions.forEach { (o, t, d) -> append("· $o — $t${if (d.isNotBlank() && !t.contains(d)) " ($d)" else ""}\n") }
            append("(차에서 음성으로 요청해 정리한 초안입니다)")
        }
        val out = File(ctx.getExternalFilesDir(null), "out").apply { mkdirs() }
        val f = File(out, "meeting_summary_${LocalDate.now()}.md")
        f.writeText("# $title\n\n" + draft + "\n\n원문: ${transcript.name} (${lines.size}줄, 참석 ${people.size}명)\n")
        Log.i(PleosBridge.TAG, "MEETING summary decisions=${decisions.size} actions=${actions.size} -> ${f.absolutePath}")
        Publish.toShared(ctx, f, "text/markdown", movies = false)
        return MeetingSummary(title, decisions, actions, draft, f)
    }

    private fun tidy(s: String) = s.trim().trimEnd('.', '!', ' ').replace(Regex("\\s+"), " ")
}

/* ------------------------------------------------------------------------------------------
 * Moment 2 — "오늘 찍은 걸로 릴스 하나 만들어줘"
 * A real H.264 encode (MediaCodec + MediaMuxer), in the background while the car moves:
 * photos from files/drive_cam/ (in production: the built-in cam; in this build: pushed / seeded
 * photos) -> 9:16 reel with slow Ken Burns moves, cross-fades, a title card built from the trip
 * the car actually reports (NaviHelper destination + distance), and an end card.
 * ------------------------------------------------------------------------------------------ */
class ReelMaker(private val ctx: Context) {
    val progress = MutableStateFlow(-1f)     // -1 idle, 0..1 encoding, 1 done
    val output = MutableStateFlow<File?>(null)
    @Volatile var running = false; private set

    fun photos(): List<File> {
        val dir = File(ctx.getExternalFilesDir(null), "drive_cam").apply { mkdirs() }
        if (dir.listFiles().isNullOrEmpty()) runCatching {
            ctx.assets.list("drive_cam")?.forEach { n -> ctx.assets.open("drive_cam/$n").use { i -> File(dir, n).outputStream().use { i.copyTo(it) } } }
        }
        return dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.sortedBy { it.name }.orEmpty()
    }

    fun start(title: String, sub: String, onDone: (File, Int, Long) -> Unit) {
        if (running) return
        running = true; progress.value = 0f
        Thread {
            val t0 = System.currentTimeMillis()
            runCatching {
                val src = photos()
                val f = encode(src, title, sub)
                Publish.toShared(ctx, f, "video/mp4", movies = true)
                output.value = f; progress.value = 1f
                Log.i(PleosBridge.TAG, "REEL done ${f.absolutePath} ${f.length()} bytes, ${src.size} photos, ${System.currentTimeMillis() - t0} ms")
                onDone(f, src.size, System.currentTimeMillis() - t0)
            }.onFailure { Log.e(PleosBridge.TAG, "REEL failed", it); progress.value = -1f }
            running = false
        }.apply { name = "reel"; priority = Thread.MIN_PRIORITY }.start()
    }

    private val W = 720; private val H = 1280; private val FPS = 30
    private val NAVY = Color.rgb(0, 44, 95)
    private val SAND = Color.rgb(246, 243, 242)

    private fun encode(src: List<File>, title: String, sub: String): File {
        val dir = File(ctx.getExternalFilesDir(null), "reels").apply { mkdirs() }
        val out = File(dir, "drive_reel_${LocalDate.now()}.mp4")
        val bitmaps = src.take(6).mapNotNull { f ->
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = 1 }
            BitmapFactory.decodeFile(f.absolutePath, o)
        }
        require(bitmaps.isNotEmpty()) { "no photos in drive_cam" }

        val titleSec = 1.8f; val perPhoto = 2.2f; val xfade = 0.45f; val endSec = 1.8f
        val total = titleSec + perPhoto * bitmaps.size + endSec
        val frames = (total * FPS).roundToInt()

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var muxing = false
        val info = MediaCodec.BufferInfo()

        val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val argb = IntArray(W * H)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val tf = runCatching { ctx.resources.getFont(R.font.asta_sans) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val tfR = runCatching { ctx.resources.getFont(R.font.asta_sans) }.getOrNull() ?: Typeface.DEFAULT

        fun drain(end: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        for (i in 0..frames) {
            val t = i.toFloat() / FPS
            if (i < frames) drawFrame(canvas, paint, tf, tfR, bitmaps, t, titleSec, perPhoto, xfade, total, title, sub)
            var inIdx: Int
            do { inIdx = codec.dequeueInputBuffer(10_000); if (inIdx < 0) drain(false) } while (inIdx < 0)
            val pts = i * 1_000_000L / FPS
            if (i == frames) { codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            frame.getPixels(argb, 0, W, 0, 0, W, H)
            val img = codec.getInputImage(inIdx)!!
            writeYuv(argb, img)
            codec.queueInputBuffer(inIdx, 0, W * H * 3 / 2, pts, 0)
            drain(false)
            progress.value = (i.toFloat() / frames).coerceIn(0f, 0.99f)
            Thread.sleep(25)   // background job: leave the CPU to the driving UI (a 14 s reel still finishes in well under a minute)
        }
        drain(true)
        codec.stop(); codec.release()
        if (muxing) muxer.stop()
        muxer.release()
        bitmaps.forEach { it.recycle() }; frame.recycle()
        // a poster frame for the parked card
        return out
    }

    private fun drawFrame(c: Canvas, p: Paint, tf: Typeface, tfR: Typeface, bms: List<Bitmap>, t: Float, titleSec: Float, per: Float, xf: Float, total: Float, title: String, sub: String) {
        c.drawColor(NAVY)
        val n = bms.size
        val pt = t - titleSec
        if (pt > -xf && pt < per * n + xf) {
            val k = (pt / per).toInt().coerceIn(0, n - 1)
            drawPhoto(c, p, bms[k], k, (pt - k * per) / per, 1f)
            // cross-fade into the next photo during the last xf seconds
            val into = pt - (k + 1) * per + xf
            if (k + 1 < n && into > 0) drawPhoto(c, p, bms[k + 1], k + 1, 0f, (into / xf).coerceIn(0f, 1f))
            if (pt < 0) { p.color = NAVY; p.alpha = ((-pt / xf).coerceIn(0f, 1f) * 255).toInt(); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p); p.alpha = 255 }
            // soft bottom gradient + a thin progress line (reel-style, not a slideshow counter)
            p.shader = LinearGradient(0f, H * 0.72f, 0f, H.toFloat(), Color.TRANSPARENT, Color.argb(110, 0, 20, 45), Shader.TileMode.CLAMP)
            c.drawRect(0f, H * 0.72f, W.toFloat(), H.toFloat(), p); p.shader = null
            p.color = Color.WHITE; p.alpha = 200
            c.drawRect(48f, H - 64f, 48f + (W - 96f) * ((pt / (per * n)).coerceIn(0f, 1f)), H - 58f, p)
            p.alpha = 255
        }
        if (t < titleSec + xf) card(c, p, tf, tfR, title, sub, alpha = if (t < titleSec) 1f else 1f - (t - titleSec) / xf, rise = min(1f, t / 0.8f))
        val endStart = total - 1.8f
        if (t > endStart - xf) card(c, p, tf, tfR, "도착했어요", sub, alpha = min(1f, (t - endStart + xf) / xf), rise = 1f)
    }

    private fun drawPhoto(c: Canvas, p: Paint, b: Bitmap, k: Int, u: Float, a: Float) {
        // cover-fit, then a slow zoom (1.06 -> 1.16) with alternating pan
        val s0 = max(W.toFloat() / b.width, H.toFloat() / b.height)
        val z = 1.06f + 0.10f * u.coerceIn(0f, 1.2f)
        val s = s0 * z
        val dw = b.width * s; val dh = b.height * s
        val dir = if (k % 2 == 0) 1 else -1
        val panX = (dw - W) * (0.5f + 0.35f * dir * (u - 0.5f))
        val l = -panX; val tp = -(dh - H) / 2f
        p.alpha = (a * 255).toInt()
        c.drawBitmap(b, null, RectF(l, tp, l + dw, tp + dh), p)
        p.alpha = 255
    }

    private fun card(c: Canvas, p: Paint, tf: Typeface, tfR: Typeface, title: String, sub: String, alpha: Float, rise: Float) {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        p.color = SAND; p.alpha = a; c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        val e = 1f - (1f - rise) * (1f - rise)
        val y = H * 0.46f + (1f - e) * 40f
        p.color = NAVY; p.alpha = a
        // small mark: a still frame of the dotted sphere (filled, not a ring)
        for (li in 0..8) {
            val lat = -Math.PI / 2 + li / 8.0 * Math.PI
            val n = Math.max(1, (Math.abs(Math.cos(lat)) * 18).toInt())
            for (lj in 0 until n) {
                val lon = lj.toDouble() / n * Math.PI * 2 + 0.4
                val x = Math.cos(lat) * Math.cos(lon); val yy = Math.sin(lat); val z = Math.cos(lat) * Math.sin(lon)
                if (z < -0.05) continue
                c.drawCircle(W / 2f + (x * 50).toFloat(), y - 170f - (yy * 50).toFloat(), (1.6 + 2.2 * z).toFloat(), p)
            }
        }
        p.typeface = tf; p.textSize = 66f; p.textAlign = Paint.Align.CENTER
        c.drawText(title, W / 2f, y, p)
        p.typeface = tfR; p.textSize = 32f; p.alpha = (a * 0.72f).toInt()
        sub.split('\n').forEachIndexed { i, line -> c.drawText(line, W / 2f, y + 70f + i * 46f, p) }
        p.textAlign = Paint.Align.LEFT; p.alpha = 255
    }

    /** ARGB -> the codec's YUV420 layout, honouring each plane's row/pixel stride (works for I420 and NV12). */
    private fun writeYuv(argb: IntArray, img: android.media.Image) {
        val pl = img.planes
        val yB = pl[0].buffer; val yRow = pl[0].rowStride; val yPix = pl[0].pixelStride
        val uB = pl[1].buffer; val vB = pl[2].buffer
        val cRow = pl[1].rowStride; val cPix = pl[1].pixelStride
        val row = ByteArray(yRow)
        for (y in 0 until H) {
            val o = y * W
            if (yPix == 1) {
                for (x in 0 until W) {
                    val c = argb[o + x]
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    row[x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                }
                yB.position(y * yRow); yB.put(row, 0, W)
            } else for (x in 0 until W) {
                val c = argb[o + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                yB.put(y * yRow + x * yPix, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            }
        }
        for (y in 0 until H / 2) for (x in 0 until W / 2) {
            val c = argb[(y * 2) * W + x * 2]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
            val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
            val i = y * cRow + x * cPix
            uB.put(i, u); vB.put(i, v)
        }
    }
}
