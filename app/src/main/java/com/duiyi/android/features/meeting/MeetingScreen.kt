package com.duiyi.android.features.meeting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun MeetingScreen(
    viewModel: MeetingViewModel,
    onHome: () -> Unit,
    onHistory: () -> Unit,
    onModels: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = state.displayLanguage
    val context = LocalContext.current
    KeepAwake(viewModel.keepsScreenAwake)
    PauseOnStop { viewModel.pauseForBackground() }
    BackHandler(enabled = state.hasActiveTask) { viewModel.requestLeave() }
    val listState = rememberLazyListState()
    LaunchedEffect(state.entries.size, state.pane.live?.sourceText) {
        val last = state.entries.size
        if (last > 0) listState.animateScrollToItem(last)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ModuleChrome(Copy.modeMeeting(language), language, onHome, onHistory)
        TaskBar(
            language = language,
            hasTask = state.hasActiveTask,
            startedAt = state.taskStartedAt,
            onNewTask = viewModel::requestNewTask,
            onEndTask = viewModel::requestEndTask,
        )
        val error = if (state.lengthLimited) Copy.meetingTooLong(language) else state.error?.userMessage(language)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Tokens.userPane)
                .padding(16.dp),
        ) {
            if (error != null) {
                item {
                    Text(
                        error,
                        color = Tokens.danger,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .then(
                                SpeechRecognizerFactory.fixIntent(state.error, context)?.let { intent ->
                                    Modifier.clickable { runCatching { context.startActivity(intent) } }
                                } ?: Modifier,
                            ),
                    )
                }
            }
            if (state.entries.isEmpty() && state.pane.live == null) {
                item {
                    Text(
                        if (state.session == SessionState.Listening) Copy.listeningHint(language) else Copy.tapToStart(language),
                        color = Tokens.textSecondary,
                        fontSize = 15.sp,
                    )
                }
            }
            items(state.entries, key = { it.id }) { entry ->
                Text(entry.sourceText, color = Tokens.textPrimary, fontSize = state.fontSize.liveSp.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            state.pane.live?.let { live ->
                item {
                    Text(live.sourceText, color = Tokens.listen, fontSize = state.fontSize.liveSp.sp)
                }
            }
            state.pane.speakingHint?.let { hint ->
                item { Text(hint, color = Tokens.textSecondary, fontSize = 13.sp) }
            }
        }
        ListeningControl(
            session = state.session,
            label = viewModel.controlLabel(),
            enabled = state.session != SessionState.Preparing && !state.lengthLimited,
            onTap = viewModel::toggleListening,
            onLongPress = { viewModel.openSettings(true) },
            onSwap = { },
            swapLabel = "",
        )
    }

    SettingsSheet(state.settingsOpen, language, { viewModel.openSettings(false) }) {
        LanguageSection(Copy.recordTitle(language), state.sourceLanguage, viewModel.catalog, viewModel::setSourceLanguage)
        FontSizeRow(language, state.fontSize, viewModel::setFontSize)
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
