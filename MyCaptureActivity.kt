package com.scancode.myapp

import com.journeyapps.barcodescanner.CaptureActivity
import android.content.pm.ActivityInfo
import android.os.Bundle

class MyCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 強制直向
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
