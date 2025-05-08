package net.simplifiedcoding.mlkitsample.qrscanner

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
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
    private lateinit var qrOverlayView: QrOverlayView // Added

    private val cameraXViewModel by viewModels<CameraXViewModel>() // Corrected delegate usage

    private val mainHandler = Handler(Looper.getMainLooper()) // For UI updates
    private var lastScanTime = 0L
    private val scanClearDelay = 2000L // Clear overlay after 2s of no scans


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        qrOverlayView = binding.qrOverlayView // Initialize overlay view

        cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        cameraXViewModel.processCameraProvider.observe(this) { provider -> // Corrected viewModel access
            processCameraProvider = provider
            bindCameraPreview()
            bindInputAnalyser()
        }

        // Start a task to clear the overlay if no QR codes are detected for a while
        startOverlayCleanupTask()
    }

    private fun bindCameraPreview() {
        cameraPreview = Preview.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            .build()
        cameraPreview.setSurfaceProvider(binding.previewView.surfaceProvider)
        try {
            processCameraProvider.unbindAll() // Unbind use cases before rebinding
            processCameraProvider.bindToLifecycle(this, cameraSelector, cameraPreview)
        } catch (illegalStateException: IllegalStateException) {
            Log.e(TAG, illegalStateException.message ?: "IllegalStateException")
        } catch (illegalArgumentException: IllegalArgumentException) {
            Log.e(TAG, illegalArgumentException.message ?: "IllegalArgumentException")
        }
    }

    private fun bindInputAnalyser() {
        val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE) // Only QR codes
                .build()
        )
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            // Optimize for smoother preview, analysis might be slightly delayed
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val cameraExecutor = Executors.newSingleThreadExecutor()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(barcodeScanner, imageProxy)
        }

        try {
            // Important: Bind imageAnalysis as well
            processCameraProvider.bindToLifecycle(this, cameraSelector, cameraPreview, imageAnalysis)
        } catch (illegalStateException: IllegalStateException) {
            Log.e(TAG, illegalStateException.message ?: "IllegalStateException")
        } catch (illegalArgumentException: IllegalArgumentException) {
            Log.e(TAG, illegalArgumentException.message ?: "IllegalArgumentException")
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(
        barcodeScanner: BarcodeScanner,
        imageProxy: ImageProxy
    ) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val inputImage =
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                lastScanTime = System.currentTimeMillis() // Update last scan time
                val scannedQrResults = mutableListOf<QrScanResult>()

                if (barcodes.isNotEmpty()) {
                    for (barcode in barcodes) {
                        barcode.boundingBox?.let { boundingBox ->
                            val transformedBoundingBox = transformBoundingBox(
                                boundingBox,
                                // Pass imageProxy dimensions for correct scaling
                                Size(imageProxy.width, imageProxy.height),
                                binding.previewView
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

                    // You can still update the TextViews if you want, for debugging
                    // showBarcodeInfo(barcodes.first())
                } else {
                    // No barcodes detected in this frame, but don't clear immediately
                    // The cleanup task will handle it.
                }
            }
            .addOnFailureListener {
                Log.e(TAG, it.message ?: it.toString())
            }.addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun transformBoundingBox(
        originalBox: Rect,
        imageSize: Size, // Actual size of the image being processed
        previewView: androidx.camera.view.PreviewView
    ): RectF {
        val previewWidth = previewView.width.toFloat()
        val previewHeight = previewView.height.toFloat()

        // Determine the scale factors
        // This depends on PreviewView.ScaleType. Usually FIT_CENTER or FILL_CENTER
        // For simplicity, assuming FIT_CENTER behavior for scaling.
        // A more robust solution would consider the actual scale type and image rotation relative to display.

        val imageWidth = imageSize.width.toFloat()
        val imageHeight = imageSize.height.toFloat()

        // Adjust for rotation. If image rotation is 90 or 270, swap width and height for scaling.
        val (rotatedImageWidth, rotatedImageHeight) = if (previewView.display.rotation % 180 != 0) {
            Pair(imageHeight, imageWidth) // If display is landscape, and image is portrait (or vice-versa)
        } else {
            Pair(imageWidth, imageHeight)
        }


        val scaleX = previewWidth / rotatedImageWidth
        val scaleY = previewHeight / rotatedImageHeight

        // Choose the smaller scale factor to ensure the image fits (FIT_CENTER logic)
        // If your PreviewView uses FILL_CENTER, you might need Math.max here,
        // and then handle cropping/offsetting.
        val scale = min(scaleX, scaleY)

        // Calculate the offset to center the scaled image within the PreviewView
        val offsetX = (previewWidth - (rotatedImageWidth * scale)) / 2f
        val offsetY = (previewHeight - (rotatedImageHeight * scale)) / 2f

        // Apply scaling and offset
        // The bounding box from ML Kit is for the unrotated image.
        // We need to map these coordinates correctly.

        // This is a simplified transformation. A full solution would handle the PreviewView's
        // coordinate mapping more precisely, considering `PreviewView.getOutputTransformMatrix()`.
        // However, for many common cases, this direct scaling can work.

        val newLeft = originalBox.left * scale + offsetX
        val newTop = originalBox.top * scale + offsetY
        val newRight = originalBox.right * scale + offsetX
        val newBottom = originalBox.bottom * scale + offsetY

        return RectF(newLeft, newTop, newRight, newBottom)
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

    // Kept for compatibility if you still want to update TextViews
    private fun showBarcodeInfo(barcode: Barcode) {
        val (typeText, contentText) = getBarcodeDisplayText(barcode)
        binding.textViewQrType.text = typeText
        binding.textViewQrContent.text = contentText
    }


    private fun startOverlayCleanupTask() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() - lastScanTime > scanClearDelay) {
                    qrOverlayView.clearScans()
                }
                mainHandler.postDelayed(this, scanClearDelay / 2) // Check periodically
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null) // Clean up handler
        // CameraX resources are lifecycle-aware, usually no need for manual shutdown here
        // if ProcessCameraProvider is correctly bound to the lifecycle.
    }


    companion object {
        private val TAG = ScannerActivity::class.simpleName // Corrected to be val

        fun startScanner(context: Context) {
            Intent(context, ScannerActivity::class.java).also {
                context.startActivity(it)
            }
        }
    }
}