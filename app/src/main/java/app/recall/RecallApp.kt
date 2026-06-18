package app.recall

import android.app.Application
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
