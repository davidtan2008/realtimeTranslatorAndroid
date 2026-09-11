package com.duiyi.android.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duiyi.android.app.FeatureRegistry
import com.duiyi.android.designsystem.HistoryRow
import com.duiyi.android.designsystem.HomeModuleCard
import com.duiyi.android.designsystem.Tokens
import com.duiyi.android.domain.Copy
import com.duiyi.android.features.shared.FontSizeRow
import com.duiyi.android.features.shared.LanguageSection
import com.duiyi.android.features.shared.ModelsManagerEntry
import com.duiyi.android.features.shared.SettingsSheet

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpen: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = state.language
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Copy.appTitle(language), color = Tokens.textPrimary, fontSize = 34.sp, modifier = Modifier.weight(1f))
            Icon(
                Icons.Outlined.Settings,
                contentDescription = Copy.settingsTitle(language),
                tint = Tokens.textSecondary,
                modifier = Modifier.clickable { viewModel.openSettings(true) }.padding(8.dp),
            )
        }
        if (state.unfinished.isNotEmpty()) {
            Text(Copy.unfinishedTasks(language), color = Tokens.listen, fontSize = 13.sp)
            state.unfinished.forEach { task ->
                HistoryRow(task, language) { onOpen(task.feature.route) }
            }
            Spacer(Modifier.height(4.dp))
        }
        FeatureRegistry.visible.forEach { feature ->
            HomeModuleCard(
                title = feature.title(language),
                subtitle = feature.subtitle(language),
                background = Tokens.featureCard(feature.id),
                onClick = { onOpen(feature.id.route) },
            )
        }
        HomeModuleCard(
            title = Copy.historyTitle(language),
            subtitle = Copy.historySubtitle(language),
            background = Tokens.sheet,
            onClick = { onOpen("history") },
        )
    }

    SettingsSheet(state.settingsOpen, language, { viewModel.openSettings(false) }) {
        LanguageSection(Copy.iReadSection(language), language, viewModel.catalog, viewModel::setUserLanguage)
        FontSizeRow(language, state.fontSize, viewModel::setFontSize)
        Text(viewModel.speechEngineText(), color = Tokens.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text(viewModel.gmsText(), color = Tokens.textSecondary, fontSize = 13.sp)
        ModelsManagerEntry(language) {
            viewModel.openSettings(false)
            onOpen("models")
        }
        Text(
            Copy.speechEngineSettings(language),
            color = Tokens.listen,
            modifier = Modifier.clickable {
                runCatching { context.startActivity(viewModel.systemSettingsIntent()) }
            },
        )
    }
}
