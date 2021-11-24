package com.ayham.libcamcurrency

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
class CamTools(var activity: AppCompatActivity,
               var context: android.content.Context,
               var viewFinder: PreviewView,
               var overlay: SurfaceView,
               var convertedOutputText: TextView,
               var convertedInputText: TextView,
               var lifeCycleOwner: LifecycleOwner,
               var lifeCycle: Lifecycle,
               var camera: Camera?,
               var cameraExecutor: ExecutorService,
               var overlay_help: String) {
    public var is_multiply: Boolean = true
    public var value: Double = 1.0

    val sourceText =
        SmoothedMutableLiveData<String>(SMOOTHING_DURATION)
    var imageCropPercentages = MutableLiveData<Pair<Int, Int>>()
        .apply { value = Pair(DESIRED_HEIGHT_CROP_PERCENT, DESIRED_WIDTH_CROP_PERCENT) }

    private var imageAnalyzer: ImageAnalysis? = null

    fun draw_overlay() {
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

        val overlayText = overlay_help
        val textBounds = Rect()
        textPaint.getTextBounds(overlayText, 0, overlayText.length, textBounds)
        val textX = (surfaceWidth - textBounds.width()) / 2f
        val textY = rectTop - textBounds.height() - 15f // put text below rect and 15f padding
        canvas.drawText(overlay_help, textX, textY, textPaint)
        holder.unlockCanvasAndPost(canvas)
    }

    @SuppressLint("SetTextI18n")
    public fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

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
            val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scale = camera!!.cameraInfo.zoomState.value!!.zoomRatio * detector.scaleFactor
                    camera!!.cameraControl.setZoomRatio(scale)
                    return true
                }
            }
            val scaleGestureDetector = ScaleGestureDetector(context, listener)
            viewFinder.setOnTouchListener { _, event ->
                scaleGestureDetector.onTouchEvent(event)
                return@setOnTouchListener true
            }

            try {
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
                            cameraExecutor, TextAnalyzer(
                                context,
                                lifeCycle,
                                this.sourceText,
                                this.imageCropPercentages
                            )
                        )
                    }
            } catch (e: Exception) {
                return@Runnable
            }
        this.sourceText.observe(lifeCycleOwner, Observer { image_text ->
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
            this.imageCropPercentages.observe(lifeCycleOwner,
                Observer { drawOverlay(overlay.holder, it.first, it.second) })

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    lifeCycleOwner, cameraSelector, preview, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            context, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        fun aspectRatio(width: Int, height: Int): Int {
            val previewRatio = ln(max(width, height).toDouble() / min(width, height))
            if (abs(previewRatio - ln(RATIO_4_3_VALUE))
                <= abs(previewRatio - ln(RATIO_16_9_VALUE))
            )
                return AspectRatio.RATIO_4_3
            return AspectRatio.RATIO_16_9
        }

        public const val TAG = "LIBCamCurrency"
        public const val REQUEST_CODE_PERMISSIONS = 10
        public val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        // We only need to analyze the part of the image that has text, so we set crop percentages
        // to avoid analyze the entire image from the live camera feed.
        public const val DESIRED_WIDTH_CROP_PERCENT = 45
        public const val DESIRED_HEIGHT_CROP_PERCENT = 74

        public const val RATIO_4_3_VALUE = 4.0 / 3.0
        public val RATIO_16_9_VALUE = 16.0 / 9.0

        // amount of time to wait for detected text to settle (ms)
        public const val SMOOTHING_DURATION = 200L
    }
}
