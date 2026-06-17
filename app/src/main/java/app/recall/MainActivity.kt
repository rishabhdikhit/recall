package app.recall

import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PipelineScreen()
                }
            }
        }
    }
}

private fun isYoutube(url: String): Boolean =
    url.contains("youtube.com", true) || url.contains("youtu.be", true)

@Composable
fun PipelineScreen() {
    val ctx = LocalContext.current
    var apiKey by remember { mutableStateOf(Prefs.geminiKey(ctx)) }
    var url by remember { mutableStateOf("") }
    var initStatus by remember { mutableStateOf("Initializing yt-dlp…") }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val app = RecallApp.instance
        while (!app.ytdlReady && app.ytdlError == null) delay(300)
        initStatus = app.ytdlError?.let { "yt-dlp init FAILED: $it" } ?: "yt-dlp ready ✓"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Recall", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(2.dp))
        Text(initStatus, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(18.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; Prefs.setGeminiKey(ctx, it) },
            label = { Text("Gemini API key (stored on device)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Instagram reel or YouTube link") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                busy = true
                result = null
                status = "Starting…"
                val link = url.trim()
                val key = apiKey.trim()
                scope.launch {
                    try {
                        val analysis = withContext(Dispatchers.IO) {
                            if (isYoutube(link)) {
                                status = "Analyzing with Gemini…"
                                Gemini.analyzeYoutube(key, link)
                            } else {
                                status = "Downloading audio…"
                                val audio = Downloader.downloadAudioMp3(ctx, link)
                                status = "Encoding…"
                                val b64 = Base64.encodeToString(audio.file.readBytes(), Base64.NO_WRAP)
                                status = "Analyzing with Gemini…"
                                val a = Gemini.analyzeInlineAudio(key, b64, audio.mimeType)
                                audio.file.delete()
                                a
                            }
                        }
                        status = null
                        result = buildString {
                            appendLine("TITLE: ${analysis.title}")
                            appendLine("TOPIC: ${analysis.topic}")
                            appendLine("LANGUAGE: ${analysis.language}  hasSpeech=${analysis.hasSpeech}")
                            appendLine("TAGS: ${analysis.subtags.joinToString(", ")}")
                            appendLine()
                            appendLine("SUMMARY:")
                            appendLine(analysis.summary)
                            appendLine()
                            appendLine("TRANSCRIPT:")
                            append(analysis.transcript)
                        }
                    } catch (e: Throwable) {
                        status = null
                        result = "ERROR: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && url.isNotBlank() && apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Working…" else "Analyze & show")
        }

        status?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        result?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
