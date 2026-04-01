package com.voltron.dash

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.voltron.dash.ble.FarDriverData
import com.voltron.dash.ble.JkBmsData
import com.voltron.dash.render.DashboardRenderer

class DashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var data: FarDriverData = FarDriverData()
        set(value) {
            field = value
            // invalidate is called by the timer, not here, to avoid flooding
        }

    var bmsData: JkBmsData = JkBmsData()

    var onSettingsTap: (() -> Unit)? = null
    var onResetTap: (() -> Unit)? = null
    var onBmsTap: (() -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        DashboardRenderer.draw(canvas, width, height, data, bmsData)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (DashboardRenderer.gearButtonRect.contains(event.x, event.y)) {
                onSettingsTap?.invoke()
                return true
            }
            if (DashboardRenderer.resetButtonRect.contains(event.x, event.y)) {
                onResetTap?.invoke()
                return true
            }
            if (DashboardRenderer.bmsButtonRect.contains(event.x, event.y)) {
                onBmsTap?.invoke()
                return true
            }
            if (DashboardRenderer.faultClearRect.contains(event.x, event.y)) {
                DashboardRenderer.clearFaults = true
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
