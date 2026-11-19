package com.procam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.Locale
import kotlin.math.roundToInt

/** Grade dos terços por cima da imagem. */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var showGrid: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showGrid) return
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, paint)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, paint)
        }
    }
}

/** Histograma de brilho ao vivo. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var values: IntArray? = null
    private val barPaint = Paint().apply { color = Color.argb(210, 255, 255, 255) }
    private val bgPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }

    fun update(bins: IntArray) {
        values = bins
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val b = values ?: return
        val max = (b.maxOrNull() ?: 0).coerceAtLeast(1)
        val bw = w / b.size
        for (i in b.indices) {
            val bh = h * (b[i].toFloat() / max)
            canvas.drawRect(i * bw, h - bh, (i + 1) * bw, h, barPaint)
        }
    }
}

/** Lê o brilho (canal Y) dos quadros da câmera e monta o histograma. */
class HistogramAnalyzer(private val onResult: (IntArray) -> Unit) : ImageAnalysis.Analyzer {
    private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < 150) return
            lastRun = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val limit = buffer.limit()
            val bins = IntArray(64)
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    val idx = y * rowStride + x * pixelStride
                    if (idx < limit) {
                        val v = buffer.get(idx).toInt() and 0xFF
                        bins[v shr 2]++
                    }
                    x += 4
                }
                y += 4
            }
            onResult(bins)
        } finally {
            image.close()
        }
    }
}

// ---------- Formatação e escalas ----------

const val SEEK_MAX = 1000

fun formatShutter(ns: Long): String =
    if (ns >= 400_000_000L) String.format(Locale.US, "%.1fs", ns / 1e9)
    else "1/" + (1e9 / ns.coerceAtLeast(1L)).roundToInt()

fun formatFocus(diopters: Float): String {
    if (diopters <= 0.05f) return "∞"
    val meters = 1f / diopters
    return if (meters < 1f) "${(meters * 100).roundToInt()}cm" else String.format(Locale.US, "%.1fm", meters)
}

fun formatEv(ev: Float): String = String.format(Locale.US, "%+.1f", ev)

/** Posição (0..SEEK_MAX) de um valor numa escala logarítmica. */
fun logPos(value: Double, min: Double, max: Double): Int {
    if (max <= min || min <= 0.0) return 0
    val v = value.coerceIn(min, max)
    return ((Math.log(v / min) / Math.log(max / min)) * SEEK_MAX).roundToInt().coerceIn(0, SEEK_MAX)
}

/** Valor numa escala logarítmica a partir de uma fração 0..1. */
fun logValue(fraction: Double, min: Double, max: Double): Double {
    if (max <= min || min <= 0.0) return min
    return min * Math.exp(fraction * Math.log(max / min))
}
