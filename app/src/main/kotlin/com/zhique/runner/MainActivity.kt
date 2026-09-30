package com.zhique.runner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zhique.runner.ui.theme.ZqTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ZhiqueApplication
        setContent {
            ZqTheme {
                ZhiqueApp(app.container)
            }
        }
    }
}
