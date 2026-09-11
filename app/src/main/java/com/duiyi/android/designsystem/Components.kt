package com.duiyi.android.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.CaptionFontSize
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.FeatureID
import com.duiyi.android.domain.LanguageCataloging
import com.duiyi.android.domain.PaneState
import com.duiyi.android.domain.SessionState
import com.duiyi.android.domain.TaskFormatting
import com.duiyi.android.domain.TaskSession
import java.time.Instant

@Composable
fun HomeModuleCard(
    title: String,
    subtitle: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(16.dp)
            .semantics { contentDescription = "$title. $subtitle" },
    ) {
        Text(title, color = Tokens.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Tokens.textSecondary, fontSize = 13.sp)
    }
}

@Composable
fun ModuleChrome(
    title: String,
    language: AppLanguage,
    onHome: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Copy.homeButton(language),
            color = Tokens.listen,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onHome),
        )
        Text(title, color = Tokens.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            Copy.historyTitle(language),
            color = Tokens.listen,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onHistory),
        )
    }
}

@Composable
fun TaskBar(
    language: AppLanguage,
    hasTask: Boolean,
    startedAt: Instant?,
    onNewTask: () -> Unit,
    onEndTask: () -> Unit,
) {
    val status = when {
        !hasTask -> Copy.taskIdle(language)
        startedAt == null -> Copy.taskRunning(language)
        else -> {
            val seconds = java.time.Duration.between(startedAt, Instant.now()).seconds
            "${Copy.taskRunning(language)} · ${if (seconds < 90) Copy.justNow(language) else TaskFormatting.durationText(seconds, language)}"
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, color = if (hasTask) Tokens.listen else Tokens.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            Copy.newTask(language),
            color = Tokens.listen,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onNewTask).padding(horizontal = 8.dp),
        )
        Text(
            Copy.endTask(language),
            color = if (hasTask) Tokens.danger else Tokens.textSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(enabled = hasTask, onClick = onEndTask),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListeningControl(
    session: SessionState,
    label: String,
    enabled: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwap: () -> Unit,
    swapLabel: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(swapLabel, color = Tokens.listen, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onSwap).padding(bottom = 6.dp))
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (session == SessionState.Listening) Tokens.listen else Tokens.controlIdle)
                .combinedClickable(enabled = enabled, onClick = onTap, onLongClick = onLongPress)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (session == SessionState.Listening) "听" else "●",
                color = if (session == SessionState.Listening) Color(0xFF1C1915) else Tokens.textSecondary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(label, color = Tokens.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun CaptionPane(
    language: AppLanguage,
    pane: PaneState,
    fontSize: CaptionFontSize,
    inverted: Boolean,
    idle: String,
    error: String?,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    showSource: Boolean = false,
    onErrorClick: (() -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(pane.history.size, pane.live?.id, pane.live?.sourceText, pane.live?.translatedText) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(if (inverted) Tokens.peerPane else Tokens.userPane)
            .then(if (inverted) Modifier.rotate(180f) else Modifier)
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            error?.let {
                Text(
                    it,
                    color = Tokens.danger,
                    fontSize = 13.sp,
                    modifier = if (onErrorClick != null) Modifier.clickable(onClick = onErrorClick) else Modifier,
                )
            }
            pane.history.forEach { line ->
                Text(
                    line.visibleText(language),
                    color = Tokens.textSecondary,
                    fontSize = fontSize.previousSp.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (showSource && line.sourceText.isNotBlank() && line.translatedText.isNotBlank()) {
                    Text(
                        line.sourceText,
                        color = Tokens.textSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            pane.speakingHint?.let { Text(it, color = Tokens.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            val live = pane.live
            if (live != null) {
                Text(
                    live.visibleText(language),
                    color = Tokens.textPrimary,
                    fontSize = fontSize.liveSp.sp,
                    fontWeight = if (live.phase == com.duiyi.android.domain.CaptionLine.Phase.Settled) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (showSource && live.sourceText.isNotBlank() && live.translatedText.isNotBlank()) {
                    Text(live.sourceText, color = Tokens.textSecondary, fontSize = 13.sp)
                }
            } else if (pane.speakingHint == null && error == null && pane.history.isEmpty()) {
                Text(idle, color = Tokens.textSecondary, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun LanguageChipGrid(
    selected: AppLanguage,
    catalog: LanguageCataloging,
    onSelect: (AppLanguage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        catalog.all.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { language ->
                    val capability = catalog.capability(language)
                    val enabled = capability.speech && capability.translate
                    val bg = if (language == selected) Tokens.listen else Tokens.sheetChip
                    val fg = if (language == selected) Color(0xFF1C1915) else if (enabled) Tokens.textPrimary else Tokens.textSecondary
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg.copy(alpha = if (enabled) 1f else 0.4f))
                            .clickable(enabled = enabled) { onSelect(language) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(language.badge, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(language.nativeName, color = fg, fontSize = 10.sp)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun HistoryRow(task: TaskSession, language: AppLanguage, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(Tokens.featureAccent(task.feature)),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(task.title, color = Tokens.textPrimary, fontSize = 16.sp)
            Text(
                "${TaskFormatting.durationText(task.duration.seconds, language)} · ${task.sourceLanguage.nativeName}",
                color = Tokens.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun ScreenCanvas(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Tokens.canvas)) { content() }
}
