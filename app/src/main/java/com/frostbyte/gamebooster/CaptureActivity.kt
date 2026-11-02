package com.frostbyte.gamebooster

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.frostbyte.gamebooster.service.CaptureService

/** Invisible activity whose only job is to show Android's own "start capturing?" prompt. Nothing is captured without your tap. */
class CaptureActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "mode"   // "shot" or "rec"
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            CaptureService.begin(this, intent.getStringExtra(EXTRA_MODE) ?: "shot", r.resultCode, data)
        } else {
            Toast.makeText(this, "Capture cancelled", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mpm.createScreenCaptureIntent())
        }
    }
}
