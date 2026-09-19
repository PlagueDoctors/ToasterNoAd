package com.toaster.noad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.toaster.noad.core.navigation.NoAdApp
import com.toaster.noad.ui.theme.NoAdTheme

/**
 * 应用唯一 Activity（单 Activity + Compose 架构）。
 *
 * 主题偏好从 DataStore 读取，使「设置 → 深色主题」开关真正生效 ——
 * 旧实现中该开关没有任何实际接线。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settingsRepository = NoAdApplication.containerOf(this).settingsRepository

        setContent {
            val darkTheme by settingsRepository.darkTheme.collectAsState(initial = true)
            NoAdTheme(darkTheme = darkTheme) {
                NoAdApp()
            }
        }
    }
}
