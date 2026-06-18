package app.recall

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object Pdf {
    private const val PAGE_W = 595 // A4 @ ~72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 40
    private val CONTENT_W = PAGE_W - 2 * MARGIN

    fun export(context: Context, entries: List<Entry>, heading: String): File {
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas: Canvas = page.canvas
        var y = MARGIN

        fun newPage() {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun paint(size: Float, bold: Boolean, color: Int) = TextPaint().apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isAntiAlias = true
        }

        fun drawWrapped(text: String, tp: TextPaint, gapAfter: Int) {
            if (text.isEmpty()) {
                y += gapAfter
                return
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, CONTENT_W).build()
            for (i in 0 until layout.lineCount) {
                val lineHeight = layout.getLineBottom(i) - layout.getLineTop(i)
                if (y + lineHeight > PAGE_H - MARGIN) newPage()
                val baseline = y + (layout.getLineBaseline(i) - layout.getLineTop(i))
                val x = MARGIN + layout.getLineLeft(i)
                canvas.drawText(text, layout.getLineStart(i), layout.getLineEnd(i), x, baseline.toFloat(), tp)
                y += lineHeight
            }
            y += gapAfter
        }

        drawWrapped(heading, paint(20f, true, 0xFF000000.toInt()), 14)
        for (e in entries) {
            if (y > PAGE_H - MARGIN - 60) newPage()
            drawWrapped(e.title, paint(15f, true, 0xFF000000.toInt()), 2)
            drawWrapped("${e.source} · ${e.topic}", paint(10f, false, 0xFF888888.toInt()), 6)
            drawWrapped(e.summary, paint(12f, false, 0xFF222222.toInt()), 6)
            if (e.caption.isNotBlank()) drawWrapped("Caption: ${e.caption}", paint(10f, false, 0xFF666666.toInt()), 6)
            if (e.transcript.isNotBlank()) drawWrapped(e.transcript, paint(10f, false, 0xFF555555.toInt()), 6)
            drawWrapped(e.url, paint(9f, false, 0xFF1A73E8.toInt()), 18)
        }
        doc.finishPage(page)

        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        // Keep only the most recent export — old PDFs are just transient share artifacts.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "recall_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF"))
    }
}
