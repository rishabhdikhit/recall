package app.recall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
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
    private var sharedImages by mutableStateOf<List<Uri>?>(null)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        sharedUrl = sharedUrlFrom(intent)
        sharedImages = sharedImagesFrom(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg, primary = Accent)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    AppRoot(
                        sharedUrl = sharedUrl,
                        sharedImages = sharedImages,
                        onSharedConsumed = { sharedUrl = null },
                        onImagesConsumed = { sharedImages = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUrlFrom(intent)?.let { sharedUrl = it }
        sharedImagesFrom(intent)?.let { sharedImages = it }
    }

    private fun sharedUrlFrom(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun sharedImagesFrom(intent: Intent?): List<Uri>? {
        if (intent == null || intent.type?.startsWith("image/") != true) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList()
            else -> null
        }
    }
}

private enum class Tab { Library, Screenshots, Add, Settings }

@Composable
fun AppRoot(
    sharedUrl: String?,
    sharedImages: List<Uri>?,
    onSharedConsumed: () -> Unit,
    onImagesConsumed: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.Library) }
    var entries by remember { mutableStateOf(listOf<Entry>()) }
    var query by remember { mutableStateOf("") }
    var filterTopic by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Entry?>(null) }
    var failures by remember { mutableStateOf(listOf<Failure>()) }
    var refresh by remember { mutableStateOf(0) }
    var prefillUrl by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val changed by IngestBus.changed.collectAsState()
    val activeCount by IngestBus.active.collectAsState()
    LaunchedEffect(changed) { refresh++ }

    // A shared reel: kick off the background save and let the user leave immediately.
    LaunchedEffect(sharedUrl) {
        val u = sharedUrl ?: return@LaunchedEffect
        onSharedConsumed()
        if (Prefs.geminiKey(ctx).isBlank()) {
            prefillUrl = u
            tab = Tab.Add
            Toast.makeText(ctx, "Add your Gemini key first", Toast.LENGTH_LONG).show()
        } else {
            IngestService.start(ctx, u)
            tab = Tab.Library
            Toast.makeText(ctx, "Saving in background — you can switch back to Instagram", Toast.LENGTH_LONG).show()
        }
    }

    // Shared screenshot(s): copy them into a capture folder and process in the background.
    LaunchedEffect(sharedImages) {
        val imgs = sharedImages ?: return@LaunchedEffect
        onImagesConsumed()
        if (Prefs.geminiKey(ctx).isBlank()) {
            tab = Tab.Settings
            Toast.makeText(ctx, "Add your Gemini key first", Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        val captureId = withContext(Dispatchers.IO) {
            val id = java.util.UUID.randomUUID().toString()
            val dir = IngestService.captureDir(ctx, id)
            imgs.forEachIndexed { i, uri ->
                runCatching {
                    val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
                    val ext = if (mime.contains("png")) "png" else "jpg"
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        java.io.File(dir, "img_%03d.%s".format(i, ext)).outputStream().use { input.copyTo(it) }
                    }
                }
            }
            id
        }
        IngestService.startScreenshot(ctx, captureId)
        tab = Tab.Screenshots
        Toast.makeText(ctx, "Reading your screenshot(s) in the background…", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(query, filterTopic, refresh, tab) {
        if (tab == Tab.Library || tab == Tab.Screenshots) {
            val ss = tab == Tab.Screenshots
            withContext(Dispatchers.IO) {
                val es = when {
                    query.isNotBlank() -> Repo.search(query.trim(), ss)
                    filterTopic != null -> Repo.byTopic(filterTopic!!, ss)
                    else -> Repo.all(ss)
                }
                val fs = Repo.failures().filter { it.url.startsWith(IngestService.SCREENSHOT_PREFIX) == ss }
                entries = es
                failures = fs
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 4.dp)) {
            Text("Recall", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (tab == Tab.Screenshots) "your screenshot memory" else "your video memory",
                color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (activeCount > 0) {
            Text(
                "● Saving $activeCount in background…",
                color = Accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 6.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Library, Tab.Screenshots -> LibraryScreen(
                    entries = entries,
                    failures = failures,
                    screenshots = tab == Tab.Screenshots,
                    query = query,
                    onQuery = { query = it },
                    filterTopic = filterTopic,
                    onTopic = { filterTopic = it },
                    onOpen = { selected = it },
                    onRetry = { f ->
                        // Leave the record + cached media; the service reuses it and clears it on success.
                        IngestService.start(ctx, f.url)
                        refresh++
                    },
                    onDismiss = { f ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (f.mediaPath.isNotBlank()) runCatching { java.io.File(f.mediaPath).deleteRecursively() }
                                Repo.deleteFailure(f.id)
                            }
                            refresh++
                        }
                    },
                )
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
            val label = when (t) {
                Tab.Add -> "+ Add"
                Tab.Screenshots -> "Shots"
                else -> t.name
            }
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
    failures: List<Failure>,
    screenshots: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    filterTopic: String?,
    onTopic: (String?) -> Unit,
    onOpen: (Entry) -> Unit,
    onRetry: (Failure) -> Unit,
    onDismiss: (Failure) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 14.dp).padding(top = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search titles, summaries, transcripts…",
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (entries.isNotEmpty()) scope.launch {
                        val f = withContext(Dispatchers.IO) { Pdf.export(ctx, entries, filterTopic ?: "Recall Library") }
                        Pdf.share(ctx, f)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = FieldBg),
            ) { Text("PDF", color = Accent, fontWeight = FontWeight.Bold) }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("All", filterTopic == null) { onTopic(null) }
            for (t in Gemini.TOPICS) Chip(t, filterTopic == t) { onTopic(t) }
        }

        if (entries.isEmpty() && failures.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (screenshots) {
                        "No screenshots yet.\nShare a screenshot (or several) to Recall and it reads the text for you."
                    } else {
                        "Nothing saved yet.\nTap “+ Add”, paste a reel link, and Recall transcribes + summarizes it."
                    },
                    color = Muted, fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                if (failures.isNotEmpty()) {
                    item {
                        Text(
                            "NEEDS ATTENTION",
                            color = Color(0xFFFF6B6B), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    items(failures) { f -> FailureCard(f, onRetry, onDismiss) }
                    item { Spacer(Modifier.height(10.dp)) }
                }
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
private fun FailureCard(f: Failure, onRetry: (Failure) -> Unit, onDismiss: (Failure) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .background(Color(0xFF241A1A), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            if (f.url.startsWith(IngestService.SCREENSHOT_PREFIX)) "Screenshot capture" else f.url,
            color = Color(0xFFE0C0C0), fontSize = 12.sp, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(f.error, color = Color(0xFFFF8A8A), fontSize = 12.sp, maxLines = 3)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onRetry(f) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("Retry", color = Color.White) }
            Button(
                onClick = { onDismiss(f) },
                colors = ButtonDefaults.buttonColors(containerColor = FieldBg),
            ) { Text("Dismiss", color = Color(0xFFDDDDE6)) }
        }
    }
}

@Composable
private fun AddScreen(prefillUrl: String?, onPrefillConsumed: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

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
            "Paste an Instagram reel or YouTube link — or just Share a reel from Instagram into Recall. It saves in the background, so you can keep scrolling.",
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
                    when {
                        link.isBlank() -> error = "Paste a link first."
                        Prefs.geminiKey(ctx).isBlank() -> error = "Add your Gemini key in Settings first."
                        else -> {
                            IngestService.start(ctx, link)
                            url = ""
                            Toast.makeText(ctx, "Saving in background — check your notifications", Toast.LENGTH_LONG).show()
                            onSaved()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.weight(2f),
            ) { Text("Save", color = Color.White) }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Public reels only. Instagram occasionally blocks a fetch — if one fails you'll get a notification, just try another.",
            color = Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.geminiKey(ctx)) }
    var saved by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(Prefs.geminiModel(ctx)) }

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

        Spacer(Modifier.height(30.dp))
        Text("Gemini model", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Default is great for most posts. Pro / newer models are sharper but use your free quota faster.",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Gemini.MODELS.forEach { (label, id) ->
            val isSelected = model == id
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(if (isSelected) Accent.copy(alpha = 0.18f) else FieldBg, RoundedCornerShape(10.dp))
                    .clickable { model = id; Prefs.setGeminiModel(ctx, id) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label + if (id == Gemini.DEFAULT_MODEL) "  (default)" else "",
                        color = if (isSelected) Accent else Color(0xFFDDDDE6),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(id, color = Muted, fontSize = 11.sp)
                }
                if (isSelected) Text("✓", color = Accent, fontWeight = FontWeight.Bold)
            }
        }
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

            if (entry.caption.isNotBlank()) {
                Section("Caption")
                Text(entry.caption, color = Color(0xFFBBBBC8), fontSize = 14.sp)
            }

            if (entry.transcript.isNotBlank()) {
                Section("Transcript")
                Text(entry.transcript, color = Color(0xFFAAAAB6), fontSize = 14.sp)
            }

            if (entry.source != "screenshot") {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Open original →", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url))) }
                    },
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { withContext(Dispatchers.IO) { Repo.setStar(entry.id, if (entry.starred == 1) 0 else 1) }; onChanged() } },
                    colors = ButtonDefaults.buttonColors(containerColor = FieldBg),
                ) { Text(if (entry.starred == 1) "★ Unstar" else "☆ Star", color = Color(0xFFDDDDE6)) }
                Button(
                    onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { Pdf.export(ctx, listOf(entry), entry.title) }
                            Pdf.share(ctx, f)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FieldBg),
                ) { Text("PDF", color = Color(0xFFDDDDE6)) }
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
