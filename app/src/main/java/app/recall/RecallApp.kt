package app.recall

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

class RecallApp : Application() {
    @Volatile
    var ytdlReady = false
    @Volatile
    var ytdlError: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Repo.init(this)
        // init() unpacks the bundled Python + ffmpeg — must run off the main thread.
        thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                ytdlReady = true
                // The bundled yt-dlp binary goes stale fast (IG/YT break extractors constantly),
                // so self-update it in the background, throttled to roughly once a day.
                Ytdlp.autoUpdate(this)
            } catch (e: Throwable) {
                ytdlError = e.message ?: e.toString()
            }
        }
    }

    companion object {
        lateinit var instance: RecallApp
            private set
    }
}

/** Runtime updates for the embedded yt-dlp binary (the library itself is already current). */
object Ytdlp {
    // Freshest extractor fixes for Instagram / YouTube; stable lags behind by weeks.
    private val CHANNEL = YoutubeDL.UpdateChannel.NIGHTLY
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000 // once a day

    fun version(ctx: Context): String? =
        if (RecallApp.instance.ytdlReady) runCatching { YoutubeDL.getInstance().version(ctx) }.getOrNull() else null

    /** Background, throttled, failure-safe — a dead network must never break ingest. */
    fun autoUpdate(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - Prefs.ytdlpUpdatedAt(ctx) < INTERVAL_MS) return
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, CHANNEL)
            Prefs.setYtdlpUpdatedAt(ctx, now)
        }
    }

    /** Manual update from Settings. Returns a short human-readable result. Call off the main thread. */
    fun updateNow(ctx: Context): String {
        if (!RecallApp.instance.ytdlReady) return "Engine still starting — try again in a moment."
        return runCatching {
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, CHANNEL)
            Prefs.setYtdlpUpdatedAt(ctx, System.currentTimeMillis())
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Updated to the latest ✓"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date ✓"
                else -> "Done"
            }
        }.getOrElse { "Update failed: ${it.message ?: "unknown error"}" }
    }
}
