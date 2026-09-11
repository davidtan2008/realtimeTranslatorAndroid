package com.duiyi.android.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duiyi.android.designsystem.Tokens
import com.duiyi.android.domain.Copy
import com.duiyi.android.features.shared.LanguageSection

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(viewModel.title(), color = Tokens.textPrimary, fontSize = 28.sp)
        Spacer(Modifier.height(12.dp))
        Text(viewModel.body(), color = Tokens.textSecondary, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        if (state.step == OnboardingStep.Language) {
            LanguageSection(Copy.iReadSection(state.language), state.language, viewModel.catalog, viewModel::selectLanguage)
            LanguageSection(Copy.theyReadSection(state.language), state.peerLanguage, viewModel.catalog, viewModel::selectPeer)
        }
        if (state.step == OnboardingStep.Assets) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
                color = Tokens.listen,
                trackColor = Tokens.sheetChip,
            )
            Spacer(Modifier.height(12.dp))
            viewModel.capabilityHint()?.let {
                Text(it, color = Tokens.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
            }
        }
        state.error?.let {
            Text(it, color = Tokens.danger, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = viewModel::primaryAction,
            enabled = !state.working || state.step == OnboardingStep.Assets,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Tokens.listen, contentColor = Color(0xFF1C1915)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(viewModel.primaryLabel(), fontSize = 16.sp)
        }
    }
}
