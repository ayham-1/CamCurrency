package com.ayham.camcurrency

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.common.internal.ConnectionErrorMessages.getErrorMessage
import com.google.android.gms.tasks.Task
import androidx.lifecycle.Observer
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService

import com.ayham.camcurrency.TextAnalyzer
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val sourceText = SmoothedMutableLiveData<String>(SMOOTHING_DURATION)
    private var imageCropPercentages = MutableLiveData<Pair<Int, Int>>()
        .apply { value = Pair(DESIRED_HEIGHT_CROP_PERCENT, DESIRED_WIDTH_CROP_PERCENT) }
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null

    private var is_multiply: Boolean = true
    private var value: Double = 1.0

    private lateinit var overlay: SurfaceView
    private lateinit var convertedOutputText: TextView
    private lateinit var convertedInputText: TextView
    private var camera: Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        this.overlay = findViewById<SurfaceView>(R.id.overlay)
        this.convertedOutputText = findViewById<TextView>(R.id.convertedOutputText)
        this.convertedInputText = findViewById<TextView>(R.id.convertedInputText)

        // Populate SHPINNE
        val spinner: Spinner = findViewById(R.id.operatorSpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.operatorSpinner,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            // Specify the layout to use when the list of choices appears
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            // Apply the adapter to the spinner
            spinner.adapter = adapter
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Initialize our backgroudn executor
        this.cameraExecutor = Executors.newSingleThreadExecutor()

        // Draw overlay
        overlay.apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSPARENT)
            holder.addCallback(object: SurfaceHolder.Callback {
                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    holder?.let { drawOverlay(it, DESIRED_HEIGHT_CROP_PERCENT, DESIRED_WIDTH_CROP_PERCENT) }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                }

                override fun surfaceCreated(holder: SurfaceHolder) {
                    holder?.let { drawOverlay(it, DESIRED_HEIGHT_CROP_PERCENT, DESIRED_WIDTH_CROP_PERCENT) }
                }
            })
        }
    }

    private fun drawOverlay(holder: SurfaceHolder,
                            heightCropPercent: Int,
                            widthCropPercent: Int) {
        val canvas = holder.lockCanvas()
        val bgPaint = Paint().apply {
            alpha = 140
        }
        canvas.drawPaint(bgPaint)
        val rectPaint = Paint()
        rectPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        rectPaint.style = Paint.Style.FILL
        rectPaint.color = Color.WHITE
        val outlinePaint = Paint()
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.color = Color.WHITE
        outlinePaint.strokeWidth = 4f
        val surfaceWidth = holder.surfaceFrame.width()
        val surfaceHeight = holder.surfaceFrame.height()

        val cornerRadius = 25f
        // Set rect centered in frame
        val rectTop = surfaceHeight * heightCropPercent / 2 / 100f
        val rectLeft = surfaceWidth * widthCropPercent / 2 / 100f
        val rectRight = surfaceWidth * (1 - widthCropPercent / 2 / 100f)
        val rectBottom = surfaceHeight * (1 - heightCropPercent / 2 / 100f)
        val rect = RectF(rectLeft, rectTop, rectRight, rectBottom)
        canvas.drawRoundRect(
            rect, cornerRadius, cornerRadius, rectPaint
        )
        canvas.drawRoundRect(
            rect, cornerRadius, cornerRadius, outlinePaint
        )
        val textPaint = Paint()
        textPaint.color = Color.WHITE
        textPaint.textSize = 45F

        val overlayText = getString(R.string.overlay_help)
        val textBounds = Rect()
        textPaint.getTextBounds(overlayText, 0, overlayText.length, textBounds)
        val textX = (surfaceWidth - textBounds.width()) / 2f
        val textY = rectTop - textBounds.height() - 15f // put text below rect and 15f padding
        canvas.drawText(getString(R.string.overlay_help), textX, textY, textPaint)
        holder.unlockCanvasAndPost(canvas)
    }

    fun onSetBtn(view: View) {
        // Read SHPINNE value
        val operatorSpinner = findViewById<Spinner>(R.id.operatorSpinner)
        this.is_multiply = operatorSpinner.getSelectedItem().toString() == "Multiply";

        // Read EditText Value
        val valueNumberDecimal = findViewById<EditText>(R.id.valueNumberDecimal)
        val temp = valueNumberDecimal.getText().toString().toDoubleOrNull()
        if (temp == null)
            Toast.makeText(this, "Enter a decimal number!",
                Toast.LENGTH_SHORT).show()
        else this.value = temp
    }

    @SuppressLint("SetTextI18n")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val viewFinder = findViewById<PreviewView>(R.id.viewFinder);

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            // Pinch to zoom
            val cameraPreview = findViewById<SurfaceView>(R.id.overlay)
            val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scale = camera!!.cameraInfo.zoomState.value!!.zoomRatio * detector.scaleFactor
                    camera!!.cameraControl.setZoomRatio(scale)
                    return true
                }
            }
            val scaleGestureDetector = ScaleGestureDetector(this, listener)
            cameraPreview.setOnTouchListener { _, event ->
                scaleGestureDetector.onTouchEvent(event)
                return@setOnTouchListener true
            }

            val rotation = viewFinder.display.rotation
            val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
            Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")
            val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
            Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")
            imageAnalyzer = ImageAnalysis.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        cameraExecutor
                        , TextAnalyzer(
                            this,
                            lifecycle,
                            this.sourceText,
                            this.imageCropPercentages
                        )
                    )
                }
            this.sourceText.observe(this, Observer { image_text ->
                var numerals = 0.0
                var non_converted_numerals = 0.0
                try {
                    var text = image_text.filter { it.isDigit() || it.equals('.') || it.equals(',') || it.equals('o') || it.equals('O') || it.equals(' ') }
                    numerals = text.replace(',', '.')
                        .replace(' ', '.')
                        .replace('o', '0')
                        .replace('0', '0')
                        .toDouble()
                    non_converted_numerals = numerals

                    // Convert numerals to other currency
                    if (this.is_multiply) numerals *= this.value
                    else numerals /= this.value

                    // Show text to user
                    this.convertedOutputText.text = String.format("%.2f", numerals)
                    this.convertedInputText.text = String.format("%.2f", non_converted_numerals)
                } catch (e: Exception) { return@Observer }
            })
            this.imageCropPercentages.observe(this,
                Observer { drawOverlay(overlay.holder, it.first, it.second) })

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                this.camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = ln(max(width, height).toDouble() / min(width, height))
        if (abs(previewRatio - ln(RATIO_4_3_VALUE))
            <= abs(previewRatio - ln(RATIO_16_9_VALUE)))
                return AspectRatio.RATIO_4_3
        return AspectRatio.RATIO_16_9
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CamCurrency"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        // We only need to analyze the part of the image that has text, so we set crop percentages
        // to avoid analyze the entire image from the live camera feed.
        const val DESIRED_WIDTH_CROP_PERCENT = 45
        const val DESIRED_HEIGHT_CROP_PERCENT = 74

        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        // amount of time to wait for detected text to settle (ms)
        private const val SMOOTHING_DURATION = 200L
    }
}