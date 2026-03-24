package com.voltron.dash

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.voltron.dash.ble.FarDriverData
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        DashboardRenderer.draw(canvas, width, height, data)
    }
}
