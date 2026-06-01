package com.aubreymoore.crb_damage
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aubreymoore.crb_damage.Constants.LABELS_PATH
import com.aubreymoore.crb_damage.Constants.MODEL_PATH
import com.aubreymoore.palm_highres_capture.R
import com.aubreymoore.palm_highres_capture.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
var liveDetectorEnabled = true
var deadDetectorEnabled = true
var vcutDetectorEnabled = true
var confidence_threshold: Double = 0.5
var show_conf = true

private const val LOG_INTERVAL_MS = 3000L

class MainActivity : AppCompatActivity(), Detector.DetectorListener {

    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var locationHelper: LocationHelper
    private lateinit var detectionLogger: DetectionLogger
    private var lastLogTime = 0L

    @Volatile
    private var lastBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                toast(it)
            }
        }

        locationHelper = LocationHelper(this)
        detectionLogger = DetectionLogger(this)
        if (allPermissionsGranted()) {
            startCamera()
            locationHelper.startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        bindListeners()
    }

    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit { detector?.restart(isGpu = isChecked) }
                buttonView.setBackgroundColor(
                    if (isChecked) ContextCompat.getColor(baseContext, R.color.orange)
                    else ContextCompat.getColor(baseContext, R.color.gray)
                )
            }
        }

        binding.apply {
            btnDetectLive.setOnCheckedChangeListener { buttonView, isChecked ->
                liveDetectorEnabled = isChecked
                buttonView.setBackgroundColor(
                    if (isChecked) ContextCompat.getColor(baseContext, R.color.green)
                    else ContextCompat.getColor(baseContext, R.color.gray)
                )
            }
        }

        binding.apply {
            btnDetectDead.setOnCheckedChangeListener { buttonView, isChecked ->
                deadDetectorEnabled = isChecked
                buttonView.setBackgroundColor(
                    if (isChecked) ContextCompat.getColor(baseContext, R.color.black)
                    else ContextCompat.getColor(baseContext, R.color.gray)
                )
            }
        }

        binding.apply {
            btnDetectVcut.setOnCheckedChangeListener { buttonView, isChecked ->
                vcutDetectorEnabled = isChecked
                buttonView.setBackgroundColor(
                    if (isChecked) ContextCompat.getColor(baseContext, R.color.red)
                    else ContextCompat.getColor(baseContext, R.color.gray)
                )
            }
        }

        binding.apply {
            btnDecrement.setOnClickListener {
                if (confidence_threshold > 0.05) {
                    confidence_threshold -= 0.05
                    tvConfidence.text = String.format(Locale.US, "Confidence\nthreshold\n%.2f", confidence_threshold)
                }
            }
        }
        binding.apply {
            btnIncrement.setOnClickListener {
                if (confidence_threshold < 1.0) {
                    confidence_threshold += 0.05
                    tvConfidence.text = String.format(Locale.US, "Confidence\nthreshold\n%.2f", confidence_threshold)
                }
            }
        }

        binding.apply {
            btnShowConf.setOnCheckedChangeListener { buttonView, isChecked ->
                show_conf = isChecked
                buttonView.setBackgroundColor(
                    if (isChecked) ContextCompat.getColor(baseContext, R.color.black)
                    else ContextCompat.getColor(baseContext, R.color.gray)
                )
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        // High-quality capture for SAM3 analysis
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
            )

            lastBitmap = rotatedBitmap
            detector?.detect(rotatedBitmap, confidence_threshold.toFloat())
        }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer, imageCapture)
            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            it[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            locationHelper.startLocationUpdates()
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun captureHighResPhoto(boundingBoxes: List<BoundingBox>) {
        Log.d(TAG, "captureHighResPhoto() called with ${boundingBoxes.size} boxes")

        val capture = imageCapture
        if (capture == null) {
            Log.e(TAG, "imageCapture is NULL - cannot take photo")
            return
        }
        Log.d(TAG, "imageCapture is ready")

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        // Try external storage first, fallback to app files
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CRB-detections")
        val dirCreated = dir.mkdirs()
        Log.d(TAG, "Directory: ${dir.absolutePath}, mkdirs() success: $dirCreated, exists: ${dir.exists()}")

        val photoFile = File(dir, "CRB_$timestamp.jpg")
        Log.d(TAG, "Photo will be saved to: ${photoFile.absolutePath}")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        Log.d(TAG, "Calling takePicture()...")
        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture FAILED: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture SUCCESS: ${photoFile.absolutePath}")
                    Log.d(TAG, "File size: ${photoFile.length()} bytes")

                    val lat = locationHelper.getLatitude()
                    val lon = locationHelper.getLongitude()
                    Log.d(TAG, "GPS coordinates: $lat, $lon")

                    detectionLogger.logHighResDetection(
                        photoFile = photoFile,
                        boundingBoxes = boundingBoxes,
                        latitude = lat,
                        longitude = lon,
                        timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    )
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
            locationHelper.startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).toTypedArray()
    }
    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        val now = System.currentTimeMillis()

        if (boundingBoxes.isNotEmpty() && (now - lastLogTime) > LOG_INTERVAL_MS) {
            lastLogTime = now
            captureHighResPhoto(boundingBoxes)
        }
        runOnUiThread {
            binding.inferenceTime.text = "inference time: ${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }
}