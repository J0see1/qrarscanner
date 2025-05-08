package net.simplifiedcoding.mlkitsample.qrscanner

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper // Pastikan import ini ada
import android.util.Log
import android.util.Size
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import net.simplifiedcoding.mlkitsample.CameraXViewModel
import net.simplifiedcoding.mlkitsample.databinding.ActivityScannerBinding
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private lateinit var cameraSelector: CameraSelector
    private lateinit var processCameraProvider: ProcessCameraProvider
    private lateinit var cameraPreview: Preview
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var qrOverlayView: QrOverlayView

    private val cameraXViewModel: CameraXViewModel by viewModels()

    // --- Bagian untuk Fitur Reset Overlay ---
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastQrCodeDetectedTime: Long = 0L
    private val overlayClearDelayMillis: Long = 2000L // Overlay akan hilang setelah 2 detik tidak ada QR terdeteksi
    private lateinit var overlayCleanupRunnable: Runnable
    // --- Akhir Bagian Fitur Reset Overlay ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        qrOverlayView = binding.qrOverlayView

        cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        cameraXViewModel.processCameraProvider.observe(this) { provider ->
            processCameraProvider = provider
            bindCameraUsesCases()
        }

        // --- Mulai Tugas Pembersihan Overlay ---
        initializeOverlayCleanupTask()
        // --- Akhir Mulai Tugas Pembersihan Overlay ---
    }

    private fun bindCameraUsesCases() {
        binding.previewView.post {
            bindCameraPreview()
            bindInputAnalyser()
            try {
                processCameraProvider.unbindAll()
                processCameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    cameraPreview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
            }
        }
    }

    private fun bindCameraPreview() {
        // ... (implementasi bindCameraPreview Anda)
        cameraPreview = Preview.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            .build()
        cameraPreview.setSurfaceProvider(binding.previewView.surfaceProvider)
    }

    private fun bindInputAnalyser() {
        // ... (implementasi bindInputAnalyser Anda)
        val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val cameraExecutor = Executors.newSingleThreadExecutor() // Sebaiknya didefinisikan sebagai member class jika akan di-shutdown

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(barcodeScanner, imageProxy)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(
        barcodeScanner: BarcodeScanner,
        imageProxy: ImageProxy
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val scannedQrResults = mutableListOf<QrScanResult>()
                    if (barcodes.isNotEmpty()) {
                        // --- Catat Waktu Deteksi Terakhir ---
                        lastQrCodeDetectedTime = System.currentTimeMillis()
                        // --- Akhir Catat Waktu Deteksi Terakhir ---

                        for (barcode in barcodes) {
                            barcode.boundingBox?.let { boundingBox ->
                                val transformedBoundingBox = 坐标转换( // Pastikan fungsi ini sudah ada
                                    boundingBox,
                                    Size(imageProxy.width, imageProxy.height),
                                    binding.previewView,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                val (typeText, contentText) = getBarcodeDisplayText(barcode)
                                scannedQrResults.add(
                                    QrScanResult(
                                        transformedBoundingBox,
                                        contentText,
                                        typeText
                                    )
                                )
                            }
                        }
                        mainHandler.post { qrOverlayView.updateScans(scannedQrResults) }
                    } else {
                        // Tidak ada barcode terdeteksi di frame ini.
                        // Jangan update lastQrCodeDetectedTime.
                        // Mekanisme timeout akan membersihkan overlay jika ini berlanjut.
                        // Jika Anda ingin overlay langsung hilang saat tidak ada QR di frame:
                        // mainHandler.post { qrOverlayView.clearScans() }
                        // Tapi ini bisa menyebabkan kedipan jika deteksi sesaat hilang.
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed: ${e.message}", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    // --- Fungsi untuk Fitur Reset Overlay ---
    private fun initializeOverlayCleanupTask() {
        overlayCleanupRunnable = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() - lastQrCodeDetectedTime > overlayClearDelayMillis) {
                    // Pastikan qrOverlayView sudah diinisialisasi sebelum memanggil metodenya
                    if (::qrOverlayView.isInitialized) {
                        qrOverlayView.clearScans()
                    }
                }
                // Jadwalkan pengecekan berikutnya
                mainHandler.postDelayed(this, overlayClearDelayMillis / 2) // Periksa setiap setengah dari delay
            }
        }
        // Mulai pengecekan periodik
        mainHandler.postDelayed(overlayCleanupRunnable, overlayClearDelayMillis / 2)
    }
    // --- Akhir Fungsi untuk Fitur Reset Overlay ---

    override fun onDestroy() {
        super.onDestroy()
        // --- Hentikan Tugas Pembersihan Overlay ---
        mainHandler.removeCallbacks(overlayCleanupRunnable)
        // --- Akhir Hentikan Tugas Pembersihan Overlay ---
        // Jika cameraExecutor adalah member class, matikan di sini:
        // cameraExecutor.shutdown()
    }

    // Fungsi 坐标转换 dan getBarcodeDisplayText Anda
    private fun 坐标转换(
        originalBox: Rect,
        imageSize: Size,
        previewView: PreviewView,
        imageRotationDegrees: Int
    ): RectF {
        val matrix = Matrix()
        val previewWidth = previewView.width.toFloat()
        val previewHeight = previewView.height.toFloat()
        val (imgWidth, imgHeight) = if (imageRotationDegrees == 90 || imageRotationDegrees == 270) {
            Pair(imageSize.height.toFloat(), imageSize.width.toFloat())
        } else {
            Pair(imageSize.width.toFloat(), imageSize.height.toFloat())
        }
        val scaleFactor = min(previewWidth / imgWidth, previewHeight / imgHeight)
        val dx = (previewWidth - (imgWidth * scaleFactor)) / 2f
        val dy = (previewHeight - (imgHeight * scaleFactor)) / 2f
        matrix.postScale(scaleFactor, scaleFactor)
        matrix.postTranslate(dx, dy)
        val transformedRect = RectF(originalBox)
        matrix.mapRect(transformedRect)
        return transformedRect
    }

    private fun getBarcodeDisplayText(barcode: Barcode): Pair<String, String> {
        val typeText = when (barcode.valueType) {
            Barcode.TYPE_URL -> "URL"
            Barcode.TYPE_CONTACT_INFO -> "Contact"
            Barcode.TYPE_WIFI -> "WiFi"
            Barcode.TYPE_GEO -> "Location"
            Barcode.TYPE_CALENDAR_EVENT -> "Calendar Event"
            Barcode.TYPE_DRIVER_LICENSE -> "License"
            Barcode.TYPE_EMAIL -> "Email"
            Barcode.TYPE_PHONE -> "Phone"
            Barcode.TYPE_SMS -> "SMS"
            Barcode.TYPE_TEXT -> "Text"
            else -> "Other"
        }
        val contentText = barcode.rawValue ?: barcode.displayValue ?: ""
        return Pair(typeText, contentText)
    }

    companion object {
        private val TAG = ScannerActivity::class.java.simpleName
        fun startScanner(context: Context) {
            Intent(context, ScannerActivity::class.java).also {
                context.startActivity(it)
            }
        }
    }
}