package com.gigaget.engine

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.WebView
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock

@InvokeArg
class UrlArgs {
    lateinit var url: String
}

@InvokeArg
class StartArgs {
    lateinit var id: String
    lateinit var url: String
    var quality: String = "best"
    var location: String = "downloads"
    // Sent by the shared UI for desktop folders; Android ignores it but must accept it.
    var dir: String = ""
}

@InvokeArg
class IdArgs {
    lateinit var id: String
}

@InvokeArg
class PathArgs {
    lateinit var path: String
}

@TauriPlugin
class GigaEnginePlugin(private val activity: Activity) : Plugin(activity) {

    private val tag = "GigaGet"
    private val pool = Executors.newCachedThreadPool()

    /** CI starts the app with `--ez giga_selftest true` to run one real download on an emulator. */
    override fun load(webView: WebView) {
        try {
            if (activity.intent?.getBooleanExtra("giga_selftest", false) == true) {
                for (delay in longArrayOf(8000, 16000, 24000)) {
                    webView.postDelayed({
                        webView.evaluateJavascript("window.ggSelfTest && window.ggSelfTest()", null)
                    }, delay)
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "selftest hook skipped", t)
        }
    }
    private val initLock = Any()
    @Volatile private var initialized = false

    // Reading info or downloading takes the read side; refreshing yt-dlp takes the write side, so the
    // yt-dlp files are never replaced while a link is being read or a file is downloading.
    private val engineLock = ReentrantReadWriteLock()
    private fun <T> withEngine(block: () -> T): T {
        val l = engineLock.readLock()
        l.lock()
        try { return block() } finally { l.unlock() }
    }

    /** Sites change often, so refresh yt-dlp quietly, but only when nothing is using it. */
    private fun scheduleUpdate(app: android.app.Application) {
        pool.execute {
            try {
                Thread.sleep(30_000)
                for (attempt in 1..10) {
                    val w = engineLock.writeLock()
                    if (w.tryLock()) {
                        try { YoutubeDL.getInstance().updateYoutubeDL(app) } catch (t: Throwable) { Log.w(tag, "yt-dlp update skipped", t) } finally { w.unlock() }
                        return@execute
                    }
                    Thread.sleep(60_000)
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * The bundled yt-dlp + FFmpeg are unpacked on first use, never at app launch, so a problem in the
     * engine can only show up as an error message on screen and can never stop the app from opening.
     * Returns null when the engine is ready, otherwise a readable reason.
     */
    private fun ensureEngine(): String? = synchronized(initLock) {
        if (initialized) return null
        try {
            val app = activity.application
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            initialized = true
            scheduleUpdate(app)
            null
        } catch (t: Throwable) {
            Log.e(tag, "engine failed to start", t)
            "engine could not start (" + t.javaClass.simpleName + ": " + (t.message ?: "no details") + ")"
        }
    }

    @Command
    fun probe(invoke: Invoke) {
        val args = try { invoke.parseArgs(UrlArgs::class.java) } catch (t: Throwable) { invoke.reject(cleanError(t.message)); return }
        pool.execute {
            try {
                val problem = ensureEngine()
                if (problem != null) { invoke.reject(problem); return@execute }

                val req = YoutubeDLRequest(args.url)
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                val info = withEngine { YoutubeDL.getInstance().getInfo(req) }

                var maxH = 0
                info.formats?.forEach { if (it.height > maxH) maxH = it.height }
                val q = JSArray()
                q.put("best")
                for (h in intArrayOf(2160, 1440, 1080, 720, 480, 360)) if (maxH >= h) q.put(h.toString())
                q.put("audio")

                val out = JSObject()
                out.put("title", info.title ?: "")
                out.put("thumbnail", info.thumbnail ?: "")
                out.put("duration", info.duration)
                out.put("uploader", info.uploader ?: "")
                out.put("qualities", q)
                invoke.resolve(out)
            } catch (t: Throwable) {
                Log.e(tag, "probe failed", t)
                invoke.reject(cleanError(t.message))
            }
        }
    }

    @Command
    fun start(invoke: Invoke) {
        val args = try { invoke.parseArgs(StartArgs::class.java) } catch (t: Throwable) { invoke.reject(cleanError(t.message)); return }
        // Return at once; progress arrives through the "progress" event.
        invoke.resolve(JSObject().put("id", args.id))

        pool.execute {
            val problem = ensureEngine()
            if (problem != null) {
                emit(args.id, "error", error = problem)
                return@execute
            }
            val base = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
            val tmp = File(base, "tmp-${args.id}")
            tmp.mkdirs()
            try {
                val req = YoutubeDLRequest(args.url)
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                req.addOption("--no-mtime")
                req.addOption("--windows-filenames")
                req.addOption("--retries", "10")
                req.addOption("--fragment-retries", "10")
                req.addOption("--concurrent-fragments", "8")
                req.addOption("-o", tmp.absolutePath + "/%(title).120B [%(id)s].%(ext)s")

                when (args.quality) {
                    "audio" -> {
                        req.addOption("-f", "bestaudio/best")
                        req.addOption("-x")
                        req.addOption("--audio-format", "mp3")
                        req.addOption("--audio-quality", "0")
                    }
                    "best" -> {
                        req.addOption("-f", "bv*+ba/b")
                        req.addOption("-S", "res,vcodec:h264,acodec:aac")
                        req.addOption("--merge-output-format", "mp4")
                    }
                    else -> {
                        val h = args.quality
                        req.addOption("-f", "bv*[height<=$h]+ba/b[height<=$h]/b")
                        req.addOption("-S", "res,vcodec:h264,acodec:aac")
                        req.addOption("--merge-output-format", "mp4")
                    }
                }

                var last = 0L
                withEngine {
                    YoutubeDL.getInstance().execute(req, args.id) { progress, eta, _ ->
                        val now = System.currentTimeMillis()
                        if (now - last > 250) {
                            last = now
                            emit(args.id, "downloading", percent = progress.toDouble().coerceAtLeast(0.0), eta = formatEta(eta))
                        }
                    }
                }

                emit(args.id, "processing", percent = 100.0)
                val result = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.maxByOrNull { it.lastModified() }
                    ?: throw IllegalStateException("no file was produced")
                val finalRef = saveToPublic(result, args.location)
                emit(args.id, "done", percent = 100.0, file = finalRef)
            } catch (t: Throwable) {
                Log.e(tag, "download failed", t)
                // Cancelling kills the process; that is reported by the UI itself.
                if (t.message?.contains("interrupted", true) != true) emit(args.id, "error", error = cleanError(t.message))
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    @Command
    fun cancel(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(IdArgs::class.java)
            if (initialized) YoutubeDL.getInstance().destroyProcessById(args.id)
        } catch (t: Throwable) {
            Log.w(tag, "cancel", t)
        }
        invoke.resolve()
    }

    @Command
    fun info(invoke: Invoke) {
        val out = JSObject()
        out.put("dir", "Downloads/GigaGet")
        out.put("engine", "yt-dlp (built in)")
        invoke.resolve(out)
    }

    @Command
    fun open_file(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(PathArgs::class.java)
            val uri = if (args.path.startsWith("content://")) Uri.parse(args.path) else Uri.fromFile(File(args.path))
            val ext = MimeTypeMap.getFileExtensionFromUrl(args.path)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(i)
            invoke.resolve()
        } catch (t: Throwable) {
            invoke.reject(t.message ?: "cannot open file")
        }
    }

    /**
     * Copy the finished file into public storage so it shows up in Files, Gallery and music apps.
     * location "downloads" -> Downloads/GigaGet
     * location "media"     -> Movies/GigaGet for video, Music/GigaGet for audio (anything else: Downloads)
     */
    private fun saveToPublic(src: File, location: String): String {
        if (Build.VERSION.SDK_INT < 29) return src.absolutePath
        val ext = src.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val (collection, folder) = when {
            location == "media" && mime.startsWith("video/") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_MOVIES + "/GigaGet")
            location == "media" && mime.startsWith("audio/") ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_MUSIC + "/GigaGet")
            else ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_DOWNLOADS + "/GigaGet")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("cannot create file in $folder")
        resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    private fun emit(
        id: String,
        status: String,
        percent: Double = 0.0,
        eta: String = "",
        file: String? = null,
        error: String? = null,
    ) {
        val o = JSObject()
        o.put("id", id)
        o.put("status", status)
        o.put("percent", percent)
        o.put("speed", "")
        o.put("eta", eta)
        if (file != null) o.put("file", file)
        if (error != null) o.put("error", error)
        trigger("progress", o)
    }

    private fun formatEta(sec: Long): String =
        if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)

    private fun cleanError(msg: String?): String =
        (msg ?: "download failed").lines().lastOrNull { it.isNotBlank() }?.removePrefix("ERROR:")?.trim() ?: "download failed"
}
