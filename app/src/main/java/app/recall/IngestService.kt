package app.recall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Lets the UI react when a background ingest finishes. */
object IngestBus {
    val savedCount = MutableStateFlow(0)
    val active = MutableStateFlow(0)

    // Bumped on every terminal outcome (saved / failed / duplicate) so the UI re-queries.
    val changed = MutableStateFlow(0)
}

/**
 * Processes one shared/added link in the background so the user can leave the app
 * (e.g. go back to Instagram) immediately. Shows a progress notification and a
 * final "Saved" / "Failed" notification.
 */
class IngestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)?.trim()
        if (url.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureChannel()
        startInForeground(startId, "Saving reel", "Starting…")
        IngestBus.active.value = IngestBus.active.value + 1

        scope.launch {
            val keptFile = failedAudioFile(this@IngestService, url)
            var producedFile: File? = null
            try {
                val key = Prefs.geminiKey(this@IngestService)
                if (key.isBlank()) throw RuntimeException("Open Recall and add your Gemini key first.")
                if (Repo.getByUrl(url) != null) {
                    finalNotif(startId, "Already saved", "You saved this reel before.")
                    return@launch
                }

                val model = Prefs.geminiModel(this@IngestService)
                val caption = Downloader.fetchCaption(url)

                val analysis = if (detectSource(url) == "youtube") {
                    progress(startId, "Analyzing with Gemini…")
                    Gemini.analyzeYoutube(key, model, url, caption)
                } else {
                    val audioFile: File
                    if (keptFile.exists() && keptFile.length() > 0) {
                        progress(startId, "Retrying — using saved download…")
                        audioFile = keptFile
                    } else {
                        progress(startId, "Downloading audio…")
                        audioFile = Downloader.downloadAudioMp3(this@IngestService, url).file
                    }
                    producedFile = audioFile
                    progress(startId, "Transcribing + summarizing…")
                    val b64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
                    Gemini.analyzeInlineAudio(key, model, b64, "audio/mp3", caption)
                }

                Repo.save(
                    Entry(
                        id = UUID.randomUUID().toString(),
                        title = analysis.title,
                        summary = analysis.summary,
                        transcript = analysis.transcript,
                        caption = caption,
                        url = url,
                        source = detectSource(url),
                        topic = analysis.topic,
                        subtags = analysis.subtags.joinToString(", "),
                        language = analysis.language,
                        hasSpeech = if (analysis.hasSpeech) 1 else 0,
                        createdAt = System.currentTimeMillis(),
                        starred = 0,
                    ),
                )
                IngestBus.savedCount.value = IngestBus.savedCount.value + 1
                Repo.deleteFailureByUrl(url)
                keptFile.delete()
                producedFile?.let { if (it.absolutePath != keptFile.absolutePath) it.delete() }
                finalNotif(startId, "Saved to Recall ✓", analysis.title)
            } catch (e: Throwable) {
                val msg = e.message ?: "Something went wrong."
                // Keep the downloaded audio so a retry reuses it instead of re-downloading.
                var media = ""
                try {
                    val pf = producedFile
                    if (pf != null && pf.exists() && pf.length() > 0) {
                        if (pf.absolutePath != keptFile.absolutePath) {
                            keptFile.parentFile?.mkdirs()
                            pf.copyTo(keptFile, overwrite = true)
                        }
                        media = keptFile.absolutePath
                    }
                } catch (_: Throwable) {
                }
                Repo.addFailure(stableId(url), url, msg, media)
                finalNotif(startId, "Couldn't save — added to queue", "$msg\nOpen Recall to retry it.")
            } finally {
                IngestBus.changed.value = IngestBus.changed.value + 1
                IngestBus.active.value = (IngestBus.active.value - 1).coerceAtLeast(0)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun startInForeground(id: Int, title: String, text: String) {
        val notif = buildNotif(title, text, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notif)
        }
    }

    private fun progress(id: Int, text: String) {
        nm().notify(id, buildNotif("Saving reel", text, ongoing = true))
    }

    // Use a distinct id so the result survives after the ongoing notification clears.
    private fun finalNotif(id: Int, title: String, text: String) {
        nm().notify(id + 1_000_000, buildNotif(title, text, ongoing = false))
    }

    private fun buildNotif(title: String, text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm().createNotificationChannel(
                NotificationChannel(CHANNEL, "Recall processing", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val CHANNEL = "recall_ingest"

        fun start(ctx: Context, url: String) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, IngestService::class.java).putExtra(EXTRA_URL, url),
            )
        }

        // Stable per-URL id so a re-failure reuses the same record/file (no orphans).
        fun stableId(url: String): String = Integer.toHexString(url.hashCode())

        // Where a failed download is parked for retry (outside the wiped-each-run dl/ folder).
        fun failedAudioFile(ctx: Context, url: String): File =
            File(File(ctx.cacheDir, "failed").apply { mkdirs() }, "${stableId(url)}.mp3")
    }
}
