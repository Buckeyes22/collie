package com.lateapex.collie.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import androidx.core.view.isVisible
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedImageLoader
import com.lateapex.collie.data.ScopedImageLease
import com.lateapex.collie.data.ScopedImageResult
import com.lateapex.collie.data.ScopedReadContext

/** A transcript image card whose actor is tied to the captured Collie read context. */
internal class AuthenticatedImageView(
    context: Context,
    private val loader: ScopedImageLoader?,
    private val readContext: ScopedReadContext?,
    private val reference: String?,
    private val description: String,
    private val mimeType: String?,
) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description
        visibility = View.GONE
    }
    private val status = TextView(context).apply {
        text = context.getString(R.string.history_image_loading)
        textSize = 13f
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private var request: AutoCloseable? = null
    private var retirement: AutoCloseable? = null
    private var bitmapLease: ScopedImageLease? = null
    private var viewer: AutoCloseable? = null
    private var started = false
    private var attachmentGeneration = 0L
    @Volatile private var retired = false

    init {
        setBackgroundResource(R.drawable.bg_history_tool)
        minimumHeight = dp(72)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, dp(208)))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isFocusable = true
        contentDescription = context.getString(R.string.history_image_card, description)
        setOnClickListener {
            if (loader?.isCurrent(readContext) == true && !retired) {
                bitmapLease?.retain()?.let { lease ->
                    viewer?.close()
                    viewer = ImageViewer.show(context, lease, description, loader, readContext)
                }
            }
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (started || retired) return
        started = true
        val requestGeneration = ++attachmentGeneration
        val activeLoader = loader
        if (activeLoader == null) {
            showRefused(context.getString(R.string.history_image_unavailable))
            return
        }
        if (!activeLoader.isCurrent(readContext)) {
            // A card may be attached after its captured page actor already retired. It missed
            // the loader's synchronous retirement event, so make the stale state explicit here.
            showRefused(context.getString(R.string.history_image_access_changed))
            return
        }
        retirement = activeLoader.onRetired {
            // Block stale clicks as soon as authority retires, even if the main thread is busy.
            retired = true
            val clear = Runnable {
                request?.close()
                request = null
                clearImage()
                showRefused(context.getString(R.string.history_image_access_changed))
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) clear.run()
            else android.os.Handler(android.os.Looper.getMainLooper()).post(clear)
        }
        request = activeLoader.load(readContext, reference, dp(320), dp(208), { result ->
            if (!activeLoader.isCurrent(readContext)) {
                (result as? ScopedImageResult.Ready)?.lease?.close()
                if (!retired && requestGeneration == attachmentGeneration && isAttachedToWindow) {
                    showRefused(context.getString(R.string.history_image_access_changed))
                }
                return@load
            }
            if (retired || requestGeneration != attachmentGeneration || !isAttachedToWindow) {
                (result as? ScopedImageResult.Ready)?.lease?.close()
                return@load
            }
            when (result) {
                is ScopedImageResult.Ready -> {
                    clearImage()
                    bitmapLease = result.lease
                    image.setImageBitmap(result.lease.bitmap)
                    image.isVisible = true
                    status.isVisible = false
                    isClickable = true
                    contentDescription = context.getString(R.string.history_image_open, description)
                }
                is ScopedImageResult.Refused -> showRefused(result.message)
                ScopedImageResult.Unsupported -> showRefused(context.getString(R.string.history_image_unsupported))
            }
        }, expectedMimeType = mimeType)
    }

    override fun onDetachedFromWindow() {
        attachmentGeneration++
        viewer?.close()
        viewer = null
        request?.close()
        request = null
        clearImage()
        retirement?.close()
        retirement = null
        started = false
        super.onDetachedFromWindow()
    }

    private fun showRefused(message: String) {
        image.isVisible = false
        status.text = message
        status.contentDescription = message
        status.isVisible = true
        isClickable = false
        contentDescription = context.getString(R.string.history_image_card, "$description. $message")
    }

    private fun clearImage() {
        image.setImageDrawable(null)
        bitmapLease?.close()
        bitmapLease = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Full-screen raster viewer with pinch zoom; bitmap memory remains owned by the scoped loader. */
private object ImageViewer {
    fun show(context: Context, lease: ScopedImageLease, description: String, loader: ScopedImageLoader, readContext: ScopedReadContext?): AutoCloseable {
        if (!loader.isCurrent(readContext)) { lease.close(); return AutoCloseable { } }
        val bitmap = lease.bitmap
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val matrix = Matrix()
        var lastX = 0f
        var lastY = 0f
        val view = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.MATRIX
            contentDescription = description
            setBackgroundColor(android.graphics.Color.BLACK)
            val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor.coerceIn(0.75f, 1.35f)
                    matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    imageMatrix = matrix
                    return true
                }
            })
            setOnTouchListener { target, event ->
                detector.onTouchEvent(event)
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
                    android.view.MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                        imageMatrix = matrix
                        lastX = event.x
                        lastY = event.y
                    }
                    android.view.MotionEvent.ACTION_UP -> target.performClick()
                }
                true
            }
        }
        val close = Button(context).apply { text = context.getString(R.string.history_image_close); setOnClickListener { dialog.dismiss() } }
        val zoomOut = Button(context).apply { text = context.getString(R.string.history_image_zoom_out) }
        val zoomIn = Button(context).apply { text = context.getString(R.string.history_image_zoom_in) }
        zoomOut.setOnClickListener { matrix.postScale(0.8f, 0.8f, view.width / 2f, view.height / 2f); view.imageMatrix = matrix }
        zoomIn.setOnClickListener { matrix.postScale(1.25f, 1.25f, view.width / 2f, view.height / 2f); view.imageMatrix = matrix }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(zoomOut, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(zoomIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        var retirement: AutoCloseable? = null
        dialog.setOnDismissListener {
            view.setImageDrawable(null)
            retirement?.close()
            retirement = null
            lease.close()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view.post {
            if (!dialog.isShowing || !loader.isCurrent(readContext) || bitmap.isRecycled) return@post
            val source = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            val target = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            matrix.setRectToRect(source, target, Matrix.ScaleToFit.CENTER)
            view.imageMatrix = matrix
        }
        retirement = loader.onRetired {
            // Dialog dismissal may deliver OnDismiss asynchronously. Clear the drawn bitmap and
            // release its lease in this retirement task; ScopedImageLease.close is idempotent, so
            // the eventual OnDismiss remains a safe second cleanup path.
            val dismiss = Runnable {
                view.setImageDrawable(null)
                lease.close()
                if (dialog.isShowing) dialog.dismiss()
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) dismiss.run()
            else android.os.Handler(android.os.Looper.getMainLooper()).post(dismiss)
        }
        if (!loader.isCurrent(readContext)) dialog.dismiss()
        // A history/pane card detaches during Activity rotation or destruction. Dismiss the
        // window with its owning card so its separate bitmap lease cannot outlive that surface.
        return AutoCloseable {
            val dismiss = Runnable { if (dialog.isShowing) dialog.dismiss() }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) dismiss.run()
            else android.os.Handler(android.os.Looper.getMainLooper()).post(dismiss)
        }
    }
}
