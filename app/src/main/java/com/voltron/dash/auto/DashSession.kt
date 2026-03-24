package com.voltron.dash.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class DashSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return DashScreen(carContext)
    }
}
