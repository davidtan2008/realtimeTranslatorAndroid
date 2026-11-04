package com.duiyi.android.features.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
fun ConversationScreen(
    viewModel: ConversationViewModel,
    onHome: () -> Unit,
    onHistory: () -> Unit,
    onModels: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = state.userLanguage
    val context = LocalContext.current
    fun listenIdle(forLanguage: com.duiyi.android.domain.AppLanguage) =
        if (state.session == SessionState.Listening) {
            Copy.listeningHint(forLanguage)
        } else {
            null
        }
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
        ModuleChrome(Copy.modeFaceToFace(language), language, onHome, onHistory)
        TaskBar(
            language = language,
            hasTask = state.hasActiveTask,
            startedAt = state.taskStartedAt,
            onNewTask = viewModel::requestNewTask,
            onEndTask = viewModel::requestEndTask,
        )
        CaptionPane(
            language = state.peerLanguage,
            pane = state.peerPane,
            fontSize = state.fontSize,
            inverted = true,
            idle = listenIdle(state.peerLanguage) ?: Copy.peerIdlePrompt(state.peerLanguage),
            error = null,
            accessibilityLabel = Copy.peerCaptionsA11y(language),
            modifier = Modifier.weight(1f),
        )
        ListeningControl(
            session = state.session,
            label = viewModel.controlLabel(),
            enabled = state.session != SessionState.Preparing,
            onTap = viewModel::toggleListening,
            onLongPress = { viewModel.openSettings(true) },
            onSwap = viewModel::swapLanguages,
            swapLabel = Copy.swapLanguages(language),
        )
        CaptionPane(
            language = state.userLanguage,
            pane = state.userPane,
            fontSize = state.fontSize,
            inverted = false,
            idle = listenIdle(language) ?: Copy.idlePrompt(language),
            error = state.error?.userMessage(language),
            accessibilityLabel = Copy.userCaptionsA11y(language),
            modifier = Modifier.weight(1f),
            onErrorClick = SpeechRecognizerFactory.fixIntent(state.error, context)?.let { intent ->
                { runCatching { context.startActivity(intent) } }
            },
        )
    }

    SettingsSheet(state.settingsOpen, language, { viewModel.openSettings(false) }) {
        LanguageSection(Copy.iReadSection(language), state.userLanguage, viewModel.catalog, viewModel::setUserLanguage)
        LanguageSection(Copy.theyReadSection(language), state.peerLanguage, viewModel.catalog, viewModel::setPeerLanguage)
        FontSizeRow(language, state.fontSize, viewModel::setFontSize)
        Row(Modifier.fillMaxWidth()) {
            androidx.compose.material3.Text(
                if (state.autoStartListening) Copy.autoStartOn(language) else Copy.autoStartOff(language),
                color = Tokens.listen,
                modifier = Modifier.clickable { viewModel.setAutoStart(!state.autoStartListening) },
            )
        }
        Row(Modifier.fillMaxWidth()) {
            androidx.compose.material3.Text(
                if (state.incrementalTranslation) {
                    Copy.incrementalOn(language)
                } else {
                    Copy.incrementalOff(language)
                },
                color = if (state.incrementalTranslation) Tokens.listen else Tokens.textSecondary,
                modifier = Modifier.clickable { viewModel.setIncremental(!state.incrementalTranslation) },
            )
        }
        androidx.compose.material3.Text(
            Copy.incrementalHint(language),
            color = Tokens.textSecondary,
        )
        androidx.compose.material3.Text(
            Copy.clearCaptions(language),
            color = Tokens.textSecondary,
            modifier = Modifier.clickable(onClick = viewModel::clearCaptions),
        )
        androidx.compose.material3.Text(
            Copy.summarize(language),
            color = Tokens.listen,
            modifier = Modifier.clickable(onClick = viewModel::requestSummary),
        )
        androidx.compose.material3.Text(Copy.takeTurns(language), color = Tokens.textSecondary)
        androidx.compose.material3.Text(Copy.tableHint(language), color = Tokens.textSecondary)
        ModelsManagerEntry(language) {
            viewModel.openSettings(false)
            onModels()
        }
    }
    SummarySheet(state.summaryOpen, language, state.summaryAvailability, state.summaryMessage, state.summarizing) {
        viewModel.dismissSummary()
    }
    ConfirmDialog(state.confirmEnd, Copy.endTaskConfirm(language), Copy.endTask(language), Copy.cancel(language), true, { viewModel.confirmEnd(true) }, { viewModel.confirmEnd(false) })
    ConfirmDialog(state.confirmReplace, Copy.replaceTaskConfirm(language), Copy.newTask(language), Copy.cancel(language), true, { viewModel.confirmReplace(true) }, { viewModel.confirmReplace(false) })
    ConfirmDialog(state.confirmLeave, Copy.endTaskConfirm(language), Copy.endTask(language), Copy.cancel(language), true, { viewModel.confirmLeave(true, onHome) }, { viewModel.confirmLeave(false) {} })
}
