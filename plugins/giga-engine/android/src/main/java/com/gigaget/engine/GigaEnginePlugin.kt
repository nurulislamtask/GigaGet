package com.gigaget.engine

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

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

    private val pool = Executors.newCachedThreadPool()
    private val ready = CountDownLatch(1)
    @Volatile private var initError: String? = null

    /** Boot the bundled yt-dlp + FFmpeg once at startup, then update yt-dlp quietly. */
    override fun load(webView: WebView) {
        pool.execute {
            try {
                val app = activity.application
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            } catch (e: Exception) {
                initError = e.message ?: "engine failed to start"
            } finally {
                ready.countDown()
            }
            try {
                YoutubeDL.getInstance().updateYoutubeDL(activity.application)
            } catch (_: Exception) {
            }
        }
    }

    private fun awaitReady(invoke: Invoke): Boolean {
        ready.await()
        val err = initError
        if (err != null) {
            invoke.reject(err)
            return false
        }
        return true
    }

    @Command
    fun probe(invoke: Invoke) {
        val args = invoke.parseArgs(UrlArgs::class.java)
        pool.execute {
            if (!awaitReady(invoke)) return@execute
            try {
                val req = YoutubeDLRequest(args.url)
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                val info = YoutubeDL.getInstance().getInfo(req)

                val maxH = info.formats?.maxOfOrNull { it.height } ?: 0
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
            } catch (e: Exception) {
                invoke.reject(cleanError(e.message))
            }
        }
    }

    @Command
    fun start(invoke: Invoke) {
        val args = invoke.parseArgs(StartArgs::class.java)
        // Return at once; progress arrives through the "progress" event.
        invoke.resolve(JSObject().put("id", args.id))

        pool.execute {
            if (initError != null) {
                emit(args.id, "error", error = initError)
                return@execute
            }
            ready.await()
            val tmp = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir, "tmp-${args.id}")
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
                YoutubeDL.getInstance().execute(req, args.id) { progress, eta, _ ->
                    val now = System.currentTimeMillis()
                    if (now - last > 250) {
                        last = now
                        emit(args.id, "downloading", percent = progress.toDouble().coerceAtLeast(0.0), eta = formatEta(eta))
                    }
                }

                emit(args.id, "processing", percent = 100.0)
                val result = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.maxByOrNull { it.lastModified() }
                    ?: throw IllegalStateException("no file was produced")
                val finalRef = saveToPublic(result, args.location)
                emit(args.id, "done", percent = 100.0, file = finalRef)
            } catch (e: Exception) {
                // Cancelling kills the process; that is reported by the UI itself.
                if (e.message?.contains("interrupted", true) != true) emit(args.id, "error", error = cleanError(e.message))
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    @Command
    fun cancel(invoke: Invoke) {
        val args = invoke.parseArgs(IdArgs::class.java)
        try { YoutubeDL.getInstance().destroyProcessById(args.id) } catch (_: Exception) {}
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
        val args = invoke.parseArgs(PathArgs::class.java)
        try {
            val uri = if (args.path.startsWith("content://")) Uri.parse(args.path) else Uri.fromFile(File(args.path))
            val ext = MimeTypeMap.getFileExtensionFromUrl(args.path)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(i)
            invoke.resolve()
        } catch (e: Exception) {
            invoke.reject(e.message ?: "cannot open file")
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
