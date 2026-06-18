package app.recall

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val Bg = Color(0xFF0E0E12)
private val CardBg = Color(0xFF16161D)
private val FieldBg = Color(0xFF1C1C24)
private val Accent = Color(0xFF7C5CFF)
private val Muted = Color(0xFF8A8A99)

fun detectSource(url: String): String = when {
    url.contains("youtube.com", true) || url.contains("youtu.be", true) -> "youtube"
    url.contains("instagram.com", true) -> "instagram"
    url.contains("tiktok.com", true) -> "tiktok"
    else -> "other"
}

private fun firstUrl(text: String?): String? =
    text?.let { Regex("https?://\\S+").find(it)?.value }

class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUrl = sharedFrom(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg, primary = Accent)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    AppRoot(sharedUrl) { sharedUrl = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedFrom(intent)?.let { sharedUrl = it }
    }

    private fun sharedFrom(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
        return null
    }
}

private enum class Tab { Library, Add, Settings }

@Composable
fun AppRoot(sharedUrl: String?, onSharedConsumed: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.Library) }
    var entries by remember { mutableStateOf(listOf<Entry>()) }
    var query by remember { mutableStateOf("") }
    var filterTopic by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Entry?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var prefillUrl by remember { mutableStateOf<String?>(null) }

    // A shared reel jumps straight to the Add tab, prefilled.
    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            prefillUrl = sharedUrl
            tab = Tab.Add
            onSharedConsumed()
        }
    }

    LaunchedEffect(query, filterTopic, refresh, tab) {
        if (tab == Tab.Library) {
            entries = withContext(Dispatchers.IO) {
                when {
                    query.isNotBlank() -> Repo.search(query.trim())
                    filterTopic != null -> Repo.byTopic(filterTopic!!)
                    else -> Repo.all()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 4.dp)) {
            Text("Recall", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("your video memory", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Library -> LibraryScreen(entries, query, { query = it }, filterTopic, { filterTopic = it }) { selected = it }
                Tab.Add -> AddScreen(prefillUrl, { prefillUrl = null }) { refresh++; tab = Tab.Library }
                Tab.Settings -> SettingsScreen()
            }
        }

        BottomBar(tab) { tab = it }
    }

    selected?.let { entry ->
        DetailOverlay(
            entry = entry,
            onClose = { selected = null },
            onChanged = { selected = null; refresh++ },
        )
    }
}

@Composable
private fun BottomBar(active: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Bg).padding(vertical = 6.dp),
    ) {
        for (t in Tab.values()) {
            val label = if (t == Tab.Add) "+ Add" else t.name
            Box(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (t == active) Accent else Muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    entries: List<Entry>,
    query: String,
    onQuery: (String) -> Unit,
    filterTopic: String?,
    onTopic: (String?) -> Unit,
    onOpen: (Entry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Field(
            value = query,
            onValueChange = onQuery,
            placeholder = "Search titles, summaries, transcripts…",
            modifier = Modifier.padding(horizontal = 14.dp).padding(top = 8.dp).fillMaxWidth(),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("All", filterTopic == null) { onTopic(null) }
            for (t in Gemini.TOPICS) Chip(t, filterTopic == t) { onTopic(t) }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing saved yet.\nTap “+ Add”, paste a reel link, and Recall transcribes + summarizes it.",
                    color = Muted, fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                items(entries) { e ->
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp)
                            .background(CardBg, RoundedCornerShape(14.dp))
                            .clickable { onOpen(e) }.padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.source.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(e.topic, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            if (e.starred == 1) {
                                Spacer(Modifier.weight(1f))
                                Text("★", color = Color(0xFFFFCC4D))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(e.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text(e.summary, color = Color(0xFFB0B0BC), fontSize = 13.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddScreen(prefillUrl: String?, onPrefillConsumed: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(prefillUrl) {
        if (prefillUrl != null) {
            url = prefillUrl
            onPrefillConsumed()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Add a video", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Paste an Instagram reel or YouTube link. yt-dlp grabs the audio on your phone and Gemini transcribes + summarizes it.",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))

        Field(url, { url = it }, "Reel / video link", Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { clipboard.getText()?.let { url = it.text.trim() } },
                colors = ButtonDefaults.buttonColors(containerColor = FieldBg),
                modifier = Modifier.weight(1f),
            ) { Text("Paste", color = Color(0xFFDDDDE6)) }
            Button(
                onClick = {
                    error = null
                    val link = url.trim()
                    val key = Prefs.geminiKey(ctx)
                    when {
                        link.isBlank() -> error = "Paste a link first."
                        key.isBlank() -> error = "Add your Gemini key in Settings first."
                        else -> scope.launch {
                            try {
                                if (Repo.getByUrl(link) != null) {
                                    error = "You already saved this one."
                                    return@launch
                                }
                                stage = "Starting…"
                                val a = if (detectSource(link) == "youtube") {
                                    stage = "Analyzing with Gemini…"
                                    withContext(Dispatchers.IO) { Gemini.analyzeYoutube(key, link) }
                                } else {
                                    stage = "Downloading audio…"
                                    val audio = withContext(Dispatchers.IO) { Downloader.downloadAudioMp3(ctx, link) }
                                    stage = "Encoding…"
                                    val b64 = withContext(Dispatchers.IO) {
                                        Base64.encodeToString(audio.file.readBytes(), Base64.NO_WRAP)
                                    }
                                    stage = "Analyzing with Gemini…"
                                    val res = withContext(Dispatchers.IO) {
                                        Gemini.analyzeInlineAudio(key, b64, audio.mimeType)
                                    }
                                    audio.file.delete()
                                    res
                                }
                                stage = "Saving…"
                                withContext(Dispatchers.IO) {
                                    Repo.save(
                                        Entry(
                                            id = UUID.randomUUID().toString(),
                                            title = a.title,
                                            summary = a.summary,
                                            transcript = a.transcript,
                                            url = link,
                                            source = detectSource(link),
                                            topic = a.topic,
                                            subtags = a.subtags.joinToString(", "),
                                            language = a.language,
                                            hasSpeech = if (a.hasSpeech) 1 else 0,
                                            createdAt = System.currentTimeMillis(),
                                            starred = 0,
                                        ),
                                    )
                                }
                                stage = null
                                url = ""
                                onSaved()
                            } catch (e: Throwable) {
                                stage = null
                                error = e.message ?: "Something went wrong."
                            }
                        }
                    }
                },
                enabled = stage == null,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.weight(2f),
            ) { Text(if (stage != null) "Working…" else "Analyze & Save", color = Color.White) }
        }

        stage?.let {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(it, color = Color(0xFFCCCCD6), fontSize = 14.sp)
            }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp)
        }
    }
}

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.geminiKey(ctx)) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Gemini API key", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Recall uses your own free Gemini key. It's stored only on this phone and only ever sent to Google.",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        Field(key, { key = it; saved = false }, "Paste your key (AIza…)", Modifier.fillMaxWidth(), password = true)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { Prefs.setGeminiKey(ctx, key); saved = true },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saved) "Saved ✓" else "Save key", color = Color.White) }
        Spacer(Modifier.height(16.dp))
        Text(
            "Get a free key at aistudio.google.com/apikey",
            color = Accent, fontSize = 13.sp,
            modifier = Modifier.clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            },
        )
    }
}

@Composable
private fun DetailOverlay(entry: Entry, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.source.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(entry.topic, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(entry.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            if (entry.language.isNotBlank() && entry.language != "none") {
                Text(entry.language, color = Muted, fontSize = 12.sp)
            }

            Section("Summary")
            Text(entry.summary, color = Color(0xFFDDDDE6), fontSize = 15.sp)

            if (entry.subtags.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("#" + entry.subtags.split(",").joinToString("  #") { it.trim() }, color = Accent, fontSize = 13.sp)
            }

            if (entry.transcript.isNotBlank()) {
                Section("Transcript")
                Text(entry.transcript, color = Color(0xFFAAAAB6), fontSize = 14.sp)
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Open original →", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)))
                },
            )

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { withContext(Dispatchers.IO) { Repo.setStar(entry.id, if (entry.starred == 1) 0 else 1) }; onChanged() } },
                    colors = ButtonDefaults.buttonColors(containerColor = FieldBg),
                ) { Text(if (entry.starred == 1) "★ Unstar" else "☆ Star", color = Color(0xFFDDDDE6)) }
                Button(
                    onClick = { scope.launch { withContext(Dispatchers.IO) { Repo.delete(entry.id) }; onChanged() } },
                    colors = ButtonDefaults.buttonColors(containerColor = FieldBg),
                ) { Text("Delete", color = Color(0xFFFF6B6B)) }
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("Close", color = Color.White) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title.uppercase(), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) Accent else FieldBg, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (active) Color.White else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Muted) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = modifier,
    )
}
