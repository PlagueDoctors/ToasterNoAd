package com.toaster.noad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.toaster.noad.core.navigation.NoAdApp
import com.toaster.noad.ui.theme.NoAdTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoAdTheme {
                NoAdApp()
            }
        }
    }
}
