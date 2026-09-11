package com.duiyi.android.features.history

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duiyi.android.designsystem.HistoryRow
import com.duiyi.android.designsystem.Tokens
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.FeatureID
import com.duiyi.android.features.shared.ConfirmDialog
import com.duiyi.android.features.shared.SummarySheet
import com.duiyi.android.features.shared.shareFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = state.language
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Copy.homeButton(language), color = Tokens.listen, modifier = Modifier.clickable(onClick = onBack))
            Text(Copy.historyTitle(language), color = Tokens.textPrimary, fontSize = 16.sp)
            Text("", color = Tokens.canvas)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(Copy.filterAll(language), state.filter == null) { viewModel.setFilter(null) }
            FilterChip(Copy.modeFaceToFace(language), state.filter == FeatureID.FaceToFace) { viewModel.setFilter(FeatureID.FaceToFace) }
            FilterChip(Copy.modeSimultaneous(language), state.filter == FeatureID.Simultaneous) { viewModel.setFilter(FeatureID.Simultaneous) }
            FilterChip(Copy.modeMeeting(language), state.filter == FeatureID.Meeting) { viewModel.setFilter(FeatureID.Meeting) }
        }
        if (state.tasks.isEmpty()) {
            Text(Copy.historyEmpty(language), color = Tokens.textSecondary, modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn {
                items(state.tasks, key = { it.id }) { task ->
                    val dismiss = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.requestDelete(task.id)
                                false
                            } else {
                                false
                            }
                        },
                    )
                    SwipeToDismissBox(
                        state = dismiss,
                        backgroundContent = {
                            Text(Copy.delete(language), color = Tokens.danger, modifier = Modifier.padding(16.dp))
                        },
                    ) {
                        HistoryRow(task, language) { onOpen(task.id.toString()) }
                    }
                }
            }
        }
    }
    ConfirmDialog(
        visible = state.pendingDelete != null,
        title = Copy.deleteTaskConfirm(language),
        confirm = Copy.delete(language),
        cancel = Copy.cancel(language),
        destructive = true,
        onConfirm = { viewModel.confirmDelete(true) },
        onDismiss = { viewModel.confirmDelete(false) },
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Tokens.listen else Tokens.textSecondary,
        fontSize = 13.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = state.language
    val task = state.task
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Copy.historyTitle(language), color = Tokens.listen, modifier = Modifier.clickable(onClick = onBack))
            Text(task?.title ?: Copy.recordTitle(language), color = Tokens.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        }
        if (task == null) {
            Text(Copy.historyEmpty(language), color = Tokens.textSecondary)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(task.entries, key = { it.id }) { entry ->
                    Text(entry.sourceText, color = Tokens.textPrimary, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
                    if (entry.translatedText.isNotBlank()) {
                        Text(entry.translatedText, color = Tokens.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Text(
                Copy.summarize(language),
                color = Tokens.listen,
                modifier = Modifier.clickable(onClick = viewModel::requestSummary).padding(vertical = 12.dp),
            )
            Text(
                Copy.exportShare(language),
                color = Tokens.listen,
                modifier = Modifier.clickable {
                    viewModel.prepareExport()?.let { file ->
                        (context as? Activity)?.let { shareFile(it, file, Copy.exportShare(language)) }
                    }
                }.padding(bottom = 16.dp),
            )
        }
    }
    SummarySheet(state.summaryOpen, language, state.summaryAvailability, state.summaryMessage, state.summarizing, viewModel::dismissSummary)
}
