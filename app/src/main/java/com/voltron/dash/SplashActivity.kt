package com.voltron.dash

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(SplashView(this))

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000)
    }

    private class SplashView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val logoBitmap = BitmapFactory.decodeResource(resources, R.drawable.logo_voltron)

        override fun onDraw(canvas: Canvas) {
            // Dark background
            canvas.drawColor(0xFF0A1628.toInt())

            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            // Draw logo centered (max 60% of screen width)
            logoBitmap?.let { bmp ->
                val maxW = w * 0.60f
                val scale = maxW / bmp.width
                val logoW = bmp.width * scale
                val logoH = bmp.height * scale
                val logoX = cx - logoW / 2f
                val logoY = cy - logoH / 2f - h * 0.05f

                val src = android.graphics.Rect(0, 0, bmp.width, bmp.height)
                val dst = android.graphics.RectF(logoX, logoY, logoX + logoW, logoY + logoH)
                canvas.drawBitmap(bmp, src, dst, paint)
            }

            // App name below logo
            textPaint.color = 0xFF4DB8FF.toInt()
            textPaint.textSize = Math.min(36f, h * 0.04f)
            canvas.drawText("VOLTRON DASH", cx, cy + h * 0.12f, textPaint)

            // Tagline
            textPaint.color = 0xFF7A92BE.toInt()
            textPaint.textSize = Math.min(18f, h * 0.022f)
            textPaint.typeface = Typeface.MONOSPACE
            canvas.drawText("Electric Vehicle Dashboard", cx, cy + h * 0.17f, textPaint)
        }
    }
}
