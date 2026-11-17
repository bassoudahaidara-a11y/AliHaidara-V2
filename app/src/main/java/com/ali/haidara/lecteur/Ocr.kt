package com.ali.haidara.lecteur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Lecture du texte sur une photo ou un PDF, sans internet (ML Kit, modèle inclus). */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private suspend fun recognize(image: InputImage): String =
        suspendCancellableCoroutine { c ->
            recognizer.process(image)
                .addOnSuccessListener { if (c.isActive) c.resume(it.text) }
                .addOnFailureListener { if (c.isActive) c.resume("") }
        }

    suspend fun fromImage(ctx: Context, uri: Uri): String =
        recognize(InputImage.fromFilePath(ctx, uri))

    suspend fun fromPdf(ctx: Context, uri: Uri, maxPages: Int = 30): String =
        withContext(Dispatchers.IO) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext ""
            val sb = StringBuilder()
            PdfRenderer(pfd).use { renderer ->
                val n = minOf(renderer.pageCount, maxPages)
                for (i in 0 until n) {
                    renderer.openPage(i).use { page ->
                        val w = 1400
                        val h = (w.toFloat() * page.height / page.width).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val t = recognize(InputImage.fromBitmap(bmp, 0))
                        bmp.recycle()
                        if (t.isNotBlank()) sb.append(t).append("\n\n")
                    }
                }
            }
            sb.toString()
        }
}
