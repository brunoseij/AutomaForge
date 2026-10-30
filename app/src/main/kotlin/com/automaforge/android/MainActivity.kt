package com.automaforge.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.automaforge.android.presentation.AutomaTheme
import com.automaforge.android.presentation.main.MainScreen
import com.automaforge.android.presentation.main.MainViewModel

/** Platform entry point only; screen state and actions are owned by the ViewModel. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val factory = (application as AutomaForgeApplication).graph.viewModels()
        val vm = ViewModelProvider(this, factory)[MainViewModel::class.java]
        setContent { AutomaTheme { MainS'creen(vm, factory) } }
    }
}
