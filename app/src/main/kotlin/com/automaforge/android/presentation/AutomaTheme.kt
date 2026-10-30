package com.automaforge.android.presentation

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun AutomaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Color(0xFFDDEA9D),
                secondary = Color(0xFF6FA5EE),
                background = Color(0xFF101923),
                surface = Color(0xFF17253B),
            ),
        content = content,
    )
}
