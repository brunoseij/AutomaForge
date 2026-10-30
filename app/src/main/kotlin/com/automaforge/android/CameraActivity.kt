package com.automaforge.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.automaforge.android.domain.model.CameraRequest
import com.automaforge.android.presentation.AutomaTheme
import com.automaforge.android.presentation.camera.*

class CameraActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request =
            CameraRequest(
                intent.getStringExtra("host").orEmpty(),
                intent.getStringExtra("room").orEmpty(),
                intent.getStringExtra("role").orEmpty(),
            )
        if (!request.valid()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val graph = (application as AutomaForgeApplication).graph
        val vm = ViewModelProvider(this, graph.viewModels())[CameraViewModel::class.java]
        setContent { AutomaTheme { CameraScreen(request, vm, graph.cameras(), ::finish) } }
    }
}
