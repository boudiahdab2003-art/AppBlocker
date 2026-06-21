package com.appblocker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.appblocker.ui.AppPickerScreen
import com.appblocker.ui.theme.AppBlockerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppBlockerTheme {
                AppPickerScreen()
            }
        }
    }
}
