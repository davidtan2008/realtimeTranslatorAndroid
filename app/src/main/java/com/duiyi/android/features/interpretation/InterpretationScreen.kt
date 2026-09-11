package com.duiyi.android.features.interpretation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duiyi.android.designsystem.CaptionPane
import com.duiyi.android.designsystem.ListeningControl
import com.duiyi.android.designsystem.ModuleChrome
import com.duiyi.android.designsystem.TaskBar
import com.duiyi.android.designsystem.Tokens
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.SessionState
import com.duiyi.android.features.shared.ConfirmDialog
import com.duiyi.android.features.shared.FontSizeRow
import com.duiyi.android.features.shared.KeepAwake
import com.duiyi.android.features.shared.LanguageSection
import com.duiyi.android.features.shared.ModelsManagerEntry
import com.duiyi.android.features.shared.PauseOnStop
import com.duiyi.android.features.shared.SettingsSheet
import com.duiyi.android.features.shared.SummarySheet
import com.duiyi.android.services.speech.SpeechRecognizerFactory

@Composable
fun InterpretationScreen(
    viewModel: InterpretationViewModel,
    onHome: () -> Unit,
    onHistory: () -> Unit,
    onModels: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = state.targetLanguage
    val context = LocalContext.current
    KeepAwake(viewModel.keepsScreenAwake)
    PauseOnStop { viewModel.pauseForBackground() }
    BackHandler(enabled = state.hasActiveTask) { viewModel.requestLeave() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ModuleChrome(Copy.modeSimultaneous(language), language, onHome, onHistory)
        TaskBar(
            language = language,
            hasTask = state.hasActiveTask,
            startedAt = state.taskStartedAt,
            onNewTask = viewModel::requestNewTask,
            onEndTask = viewModel::requestEndTask,
        )
        CaptionPane(
            language = language,
            pane = state.pane,
            fontSize = state.fontSize,
            inverted = false,
            idle = if (state.session == SessionState.Listening) Copy.listeningHint(language) else Copy.interpretationIdle(language),
            error = state.error?.userMessage(language),
            accessibilityLabel = Copy.userCaptionsA11y(language),
            modifier = Modifier.weight(1f),
            showSource = state.showsSourceLine,
            onErrorClick = SpeechRecognizerFactory.fixIntent(state.error, context)?.let { intent ->
                { runCatching { context.startActivity(intent) } }
            },
        )
        Text(
            viewModel.voiceOutLabel(),
            color = if (state.voiceOutEnabled) Tokens.listen else Tokens.textSecondary,
            modifier = Modifier.clickable { viewModel.setVoiceOut(!state.voiceOutEnabled) },
        )
        if (!state.canSpeakWithoutEcho) {
            Text(Copy.voiceOutMicHint(language), color = Tokens.textSecondary)
        }
        ListeningControl(
            session = state.session,
            label = viewModel.controlLabel(),
            enabled = state.session != SessionState.Preparing,
            onTap = viewModel::toggleListening,
            onLongPress = { viewModel.openSettings(true) },
            onSwap = viewModel::swapDirection,
            swapLabel = Copy.swapDirection(language),
        )
    }

    SettingsSheet(state.settingsOpen, language, { viewModel.openSettings(false) }) {
        LanguageSection(Copy.theySpeak(language), state.sourceLanguage, viewModel.catalog, viewModel::setSourceLanguage)
        LanguageSection(Copy.iListen(language), state.targetLanguage, viewModel.catalog, viewModel::setTargetLanguage)
        FontSizeRow(language, state.fontSize, viewModel::setFontSize)
        Text(
            Copy.showSourceLine(language),
            color = if (state.showsSourceLine) Tokens.listen else Tokens.textSecondary,
            modifier = Modifier.clickable { viewModel.setShowsSource(!state.showsSourceLine) },
        )
        Text(Copy.summarize(language), color = Tokens.listen, modifier = Modifier.clickable(onClick = viewModel::requestSummary))
        ModelsManagerEntry(language) {
            viewModel.openSettings(false)
            onModels()
        }
    }
    SummarySheet(state.summaryOpen, language, state.summaryAvailability, state.summaryMessage, state.summarizing, viewModel::dismissSummary)
    ConfirmDialog(state.confirmEnd, Copy.endTaskConfirm(language), Copy.endTask(language), Copy.cancel(language), true, { viewModel.confirmEnd(true) }, { viewModel.confirmEnd(false) })
    ConfirmDialog(state.confirmReplace, Copy.replaceTaskConfirm(language), Copy.newTask(language), Copy.cancel(language), true, { viewModel.confirmReplace(true) }, { viewModel.confirmReplace(false) })
    ConfirmDialog(state.confirmLeave, Copy.endTaskConfirm(language), Copy.endTask(language), Copy.cancel(language), true, { viewModel.confirmLeave(true, onHome) }, { viewModel.confirmLeave(false) {} })
}
