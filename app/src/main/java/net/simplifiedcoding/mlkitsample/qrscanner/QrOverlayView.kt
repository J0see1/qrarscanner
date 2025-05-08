package net.simplifiedcoding.mlkitsample.qrscanner

import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class QrScanResult(
    val boundingBox: RectF,
    val displayValue: String,
    val valueType: String,
    val timestamp: Long = System.currentTimeMillis()
)

class QrOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val activeScans = mutableListOf<QrScanResult>()

    private val highlightPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.argb(210, 30, 30, 30) // Sedikit lebih gelap
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Gunakan TextPaint untuk StaticLayout
    private val contentTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f // PERBESAR UKURAN TEKS KONTEN
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL) // Normal agar lebih mudah dibaca panjang
        setShadowLayer(2f, 1f, 1f, Color.DKGRAY)
    }

    private val typeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN // Warna berbeda untuk tipe agar menonjol
        textSize = 38f // PERBESAR UKURAN TEKS TIPE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val padding = 32f // PERBESAR PADDING
    private val cornerRadius = 16f // Perbesar radius sudut
    private val staticLayoutSpacingMultiplier = 1.1f // Spasi antar baris untuk StaticLayout
    private val staticLayoutSpacingAdd = 4.0f      // Spasi tambahan antar baris untuk StaticLayout

    init {
        setWillNotDraw(false)
    }

    fun updateScans(newScans: List<QrScanResult>) {
        synchronized(activeScans) {
            activeScans.clear()
            activeScans.addAll(newScans)
        }
        invalidate()
    }

    fun clearScans() {
        synchronized(activeScans) {
            activeScans.clear()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val currentScansSnapshot: List<QrScanResult>
        synchronized(activeScans) {
            currentScansSnapshot = ArrayList(activeScans)
        }

        for (scanResult in currentScansSnapshot) {
            canvas.drawRoundRect(scanResult.boundingBox, cornerRadius, cornerRadius, highlightPaint)

            // Hitung posisi dan ukuran kotak info menggunakan data yang ada
            val (infoBoxRect, contentStaticLayout) = calculateInfoBoxDetails(
                canvas,
                scanResult.boundingBox,
                scanResult.displayValue,
                scanResult.valueType
            )

            // Gambar latar belakang kotak info
            canvas.drawRoundRect(infoBoxRect, cornerRadius, cornerRadius, textBackgroundPaint)

            // --- Menggambar Teks di dalam Info Box ---
            val typeTextX = infoBoxRect.left + padding
            val typeTextYBaseline = infoBoxRect.top + padding - typeTextPaint.ascent()
            canvas.drawText(scanResult.valueType, typeTextX, typeTextYBaseline, typeTextPaint)

            // Menggambar teks konten menggunakan StaticLayout
            if (contentStaticLayout != null) {
                val contentTextStartY = typeTextYBaseline + typeTextPaint.descent() + (padding / 2) // Posisi Y untuk StaticLayout

                canvas.save()
                canvas.translate(typeTextX, contentTextStartY) // Pindahkan kanvas ke posisi awal StaticLayout
                contentStaticLayout.draw(canvas)
                canvas.restore()
            }
        }
    }

    private fun calculateInfoBoxDetails(
        canvas: Canvas,
        qrBounds: RectF,
        text: String, // displayValue
        typeText: String
    ): Pair<RectF, StaticLayout?> {

        val typeTextHeight = typeTextPaint.descent() - typeTextPaint.ascent()
        val measuredTypeTextWidth = typeTextPaint.measureText(typeText)

        // Tentukan lebar maksimal untuk konten di dalam info box
        // Minimal selebar teks tipe, atau 2.5x lebar QR, atau 50% lebar canvas. Maksimal 90% lebar canvas.
        val contentTargetWidth = min(
            maxOf(measuredTypeTextWidth, qrBounds.width() * 2.5f, canvas.width * 0.5f),
            canvas.width * 0.9f // Tidak lebih dari 90% lebar canvas
        )

        var contentStaticLayout: StaticLayout? = null
        var staticLayoutHeight = 0f

        if (text.isNotEmpty()) {
            contentStaticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(text, 0, text.length, contentTextPaint, contentTargetWidth.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(staticLayoutSpacingAdd, staticLayoutSpacingMultiplier)
                    .setIncludePad(false) // Biasanya false lebih baik untuk perhitungan tinggi yang akurat
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(
                    text, contentTextPaint, contentTargetWidth.toInt(),
                    Layout.Alignment.ALIGN_NORMAL, staticLayoutSpacingMultiplier, staticLayoutSpacingAdd, false
                )
            }
            staticLayoutHeight = contentStaticLayout.height.toFloat()
        }

        val totalTextStackHeight = typeTextHeight + (if (contentStaticLayout != null) (padding / 2 + staticLayoutHeight) else 0f)
        val boxHeight = totalTextStackHeight + padding * 2 // Padding atas dan bawah untuk keseluruhan kotak
        val boxWidth = contentTargetWidth + padding * 2 // Lebar kotak berdasarkan konten maksimal + padding kiri kanan

        var top = qrBounds.bottom + padding / 2
        var left = qrBounds.centerX() - boxWidth / 2 // Pusatkan kotak info relatif terhadap QR

        val potentialRect = RectF(left, top, left + boxWidth, top + boxHeight)

        // Penyesuaian posisi agar tetap di dalam batas kanvas
        if (potentialRect.right > canvas.width - (padding / 2)) { // Beri sedikit margin dari tepi
            potentialRect.offset((canvas.width - (padding / 2)) - potentialRect.right, 0f)
        }
        if (potentialRect.left < (padding / 2)) {
            potentialRect.offset((padding / 2) - potentialRect.left, 0f)
        }
        if (potentialRect.bottom > canvas.height - (padding / 2)) {
            potentialRect.offset(0f, qrBounds.top - potentialRect.height() - (padding / 2) - qrBounds.height())
        }
        if (potentialRect.top < (padding / 2)) {
            potentialRect.offset(0f, (padding / 2) - potentialRect.top)
        }
        return Pair(potentialRect, contentStaticLayout)
    }
}