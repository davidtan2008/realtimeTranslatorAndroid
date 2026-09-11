package com.duiyi.android.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun DuiyiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tokens.listen,
            onPrimary = Color(0xFF1C1915),
            background = Tokens.canvas,
            onBackground = Tokens.textPrimary,
            surface = Tokens.sheet,
            onSurface = Tokens.textPrimary,
            surfaceVariant = Tokens.sheetChip,
            onSurfaceVariant = Tokens.textSecondary,
            error = Tokens.danger,
            onError = Tokens.textPrimary,
        ),
        content = content,
    )
}
