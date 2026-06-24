package app.recall

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home-screen widget: a quick-search button, the latest saved memory, and an error banner when an
 * ingest has failed. Styled to match the Nothing-OS look — true black, mono labels, red signal accent.
 */
class RecallWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Application.onCreate already ran Repo.init in this process; init() is idempotent regardless.
        Repo.init(context)
        val latest = withContext(Dispatchers.IO) { Repo.latest() }
        val errorCount = withContext(Dispatchers.IO) { Repo.failures().size }
        provideContent { WidgetContent(context, latest, errorCount) }
    }

    companion object {
        suspend fun refresh(context: Context) = RecallWidget().updateAll(context)
    }
}

class RecallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecallWidget()
}

// Palette (Ink/Paper/Ash/Signal/GlassStrong) is shared from Theme.kt.

private fun mono(color: Color, size: Int, weight: FontWeight = FontWeight.Bold) = TextStyle(
    color = ColorProvider(color),
    fontSize = size.sp,
    fontWeight = weight,
    fontFamily = FontFamily.Monospace,
)

// Distinct actions/data so each tap target gets its own PendingIntent (no caching collisions).
private fun searchIntent(ctx: Context) = Intent(ctx, MainActivity::class.java).apply {
    action = MainActivity.ACTION_FOCUS_SEARCH
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    putExtra(MainActivity.EXTRA_FOCUS_SEARCH, true)
}

private fun openEntryIntent(ctx: Context, entryId: String) = Intent(ctx, MainActivity::class.java).apply {
    action = MainActivity.ACTION_OPEN_ENTRY
    data = Uri.parse("recall://entry/$entryId")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    putExtra(MainActivity.EXTRA_OPEN_ENTRY, entryId)
}

private fun openAppIntent(ctx: Context) = Intent(ctx, MainActivity::class.java).apply {
    action = Intent.ACTION_MAIN
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

@Composable
private fun WidgetContent(context: Context, latest: Entry?, errorCount: Int) {
    Column(
        GlanceModifier.fillMaxSize().background(Ink).cornerRadius(18.dp).padding(14.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RECALL", style = mono(Paper, 13))
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "⌕ SEARCH",
                style = mono(Signal, 12),
                modifier = GlanceModifier
                    .clickable(actionStartActivity(searchIntent(context)))
                    .background(GlassStrong)
                    .cornerRadius(10.dp)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }

        Spacer(GlanceModifier.height(12.dp))

        if (latest != null) {
            Column(
                GlanceModifier.fillMaxWidth().defaultWeight()
                    .clickable(actionStartActivity(openEntryIntent(context, latest.id))),
            ) {
                Text(latest.topic.uppercase(), style = mono(Ash, 10))
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    latest.title,
                    maxLines = 2,
                    style = TextStyle(color = ColorProvider(Paper), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    latest.summary,
                    maxLines = 3,
                    style = TextStyle(color = ColorProvider(Ash), fontSize = 12.sp),
                )
            }
        } else {
            Text(
                "Nothing saved yet — share a reel to Recall.",
                style = TextStyle(color = ColorProvider(Ash), fontSize = 13.sp),
                modifier = GlanceModifier.defaultWeight()
                    .clickable(actionStartActivity(openAppIntent(context))),
            )
        }

        if (errorCount > 0) {
            Spacer(GlanceModifier.height(8.dp))
            Text(
                "⚠ $errorCount failed · tap to retry",
                style = mono(Signal, 11),
                modifier = GlanceModifier.fillMaxWidth()
                    .clickable(actionStartActivity(openAppIntent(context)))
                    .background(Color(0x33D71921))
                    .cornerRadius(10.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}
