package app.recall

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

data class DownloadedAudio(val file: File, val mimeType: String)

object Downloader {
    /**
     * Downloads the best audio track for a non-YouTube URL (Instagram/TikTok/etc.)
     * and transcodes it to mp3 via the bundled ffmpeg — a format Gemini accepts,
     * and which avoids any video/audio DASH-merge issues.
     */
    fun downloadAudioMp3(context: Context, url: String): DownloadedAudio {
        val dir = File(context.cacheDir, "dl").apply {
            deleteRecursively()
            mkdirs()
        }
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", "ba/b")
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--no-playlist")
            addOption("-o", "${dir.absolutePath}/audio.%(ext)s")
        }
        YoutubeDL.getInstance().execute(request)

        val file = dir.listFiles()?.firstOrNull { it.length() > 0 }
            ?: throw RuntimeException("Download produced no audio file")
        return DownloadedAudio(file, "audio/mp3")
    }
}
