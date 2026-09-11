package com.duiyi.android.features.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duiyi.android.designsystem.Tokens
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.ManagedAsset
import com.duiyi.android.domain.ManagedAssetId
import com.duiyi.android.domain.ManagedAssetStatus
import com.duiyi.android.features.shared.ConfirmDialog

@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    onBack: () -> Unit,
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val language = viewModel.language
    var pendingDelete by remember { mutableStateOf<ManagedAssetId?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(Copy.back(language), color = Tokens.listen, modifier = Modifier.clickable(onClick = onBack))
            Text(Copy.modelsTitle(language), color = Tokens.textPrimary, fontSize = 16.sp)
            Text("", color = Tokens.canvas)
        }
        Text(Copy.modelsSubtitle(language), color = Tokens.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        val canDownloadMissing = snapshot.loaded && snapshot.hasMissing && !snapshot.downloading
        Text(
            Copy.downloadMissing(language),
            color = if (canDownloadMissing) Tokens.listen else Tokens.textSecondary,
            modifier = Modifier
                .clickable(enabled = canDownloadMissing, onClick = viewModel::downloadMissing)
                .padding(vertical = 8.dp),
        )
        if (!snapshot.loaded) {
            Spacer(Modifier.height(12.dp))
            Text(Copy.preparing(language), color = Tokens.listen)
        }
        snapshot.speech?.let { speech ->
            Spacer(Modifier.height(16.dp))
            Text(Copy.speechSection(language), color = Tokens.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            AssetCard(
                language = language,
                title = Copy.speechModelTitle(language),
                body = Copy.speechModelBody(language),
                asset = speech,
                status = viewModel.statusLabel(speech, language),
                onDownload = { viewModel.download(speech.id) },
                onDelete = { pendingDelete = speech.id },
            )
            if (speech.files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                speech.files.forEach { file ->
                    val size = sizeLabel(file.bytes, file.expectedBytes, file.ready, language)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(Copy.speechPartTitle(file.name, language), color = Tokens.textPrimary, fontSize = 14.sp)
                            Text(file.name, color = Tokens.textSecondary, fontSize = 12.sp)
                        }
                        Text(
                            if (file.ready) "${Copy.assetReady(language)}  $size" else size,
                            color = if (file.ready) Tokens.listen else Tokens.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(Copy.translationSection(language), color = Tokens.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        snapshot.translations.forEach { pack ->
            AssetCard(
                language = language,
                title = Copy.translationModelTitle(language),
                body = Copy.translationModelBody(language),
                asset = pack,
                status = viewModel.statusLabel(pack, language),
                onDownload = { viewModel.download(pack.id) },
                onDelete = { pendingDelete = pack.id },
            )
            if (pack.files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                pack.files.forEach { file ->
                    val size = sizeLabel(file.bytes, file.expectedBytes, file.ready, language)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(Copy.speechPartTitle(file.name, language), color = Tokens.textPrimary, fontSize = 14.sp)
                            Text(file.name, color = Tokens.textSecondary, fontSize = 12.sp)
                        }
                        Text(
                            if (file.ready) "${Copy.assetReady(language)}  $size" else size,
                            color = if (file.ready) Tokens.listen else Tokens.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }

    ConfirmDialog(
        visible = pendingDelete != null,
        title = Copy.confirmDeleteAsset(language),
        confirm = Copy.delete(language),
        cancel = Copy.cancel(language),
        destructive = true,
        onConfirm = {
            pendingDelete?.let(viewModel::delete)
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    )
}

@Composable
private fun AssetCard(
    language: AppLanguage,
    title: String,
    body: String,
    asset: ManagedAsset,
    status: String,
    downloadEnabled: Boolean = true,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tokens.sheet)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Tokens.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Text(
                status,
                color = when (asset.status) {
                    ManagedAssetStatus.Ready -> Tokens.listen
                    ManagedAssetStatus.Failed -> Tokens.danger
                    else -> Tokens.textSecondary
                },
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(body, color = Tokens.textSecondary, fontSize = 13.sp)
        val size = sizeLabel(asset.bytes, asset.expectedBytes, asset.status == ManagedAssetStatus.Ready, language)
        if (size.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(size, color = Tokens.textSecondary, fontSize = 13.sp)
        }
        if (asset.status == ManagedAssetStatus.Downloading) {
            Spacer(Modifier.height(10.dp))
            if (asset.indeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Tokens.listen,
                    trackColor = Tokens.sheetChip,
                )
            } else {
                LinearProgressIndicator(
                    progress = { asset.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Tokens.listen,
                    trackColor = Tokens.sheetChip,
                )
                Spacer(Modifier.height(6.dp))
                Text(Copy.percent(asset.progress, language), color = Tokens.listen, fontSize = 13.sp)
            }
        } else if (asset.status == ManagedAssetStatus.Partial) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { asset.progress },
                modifier = Modifier.fillMaxWidth(),
                color = Tokens.listen,
                trackColor = Tokens.sheetChip,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val showDownload = asset.canDownload && downloadEnabled
            Text(
                if (asset.status == ManagedAssetStatus.Failed) Copy.retry(language) else Copy.downloadAsset(language),
                color = if (showDownload) Tokens.listen else Tokens.textSecondary,
                modifier = Modifier.clickable(enabled = showDownload, onClick = onDownload),
            )
            Text(
                Copy.deleteAsset(language),
                color = if (asset.canDelete) Tokens.danger else Tokens.textSecondary,
                modifier = Modifier.clickable(enabled = asset.canDelete, onClick = onDelete),
            )
        }
    }
}

private fun sizeLabel(bytes: Long, expectedBytes: Long, ready: Boolean, language: AppLanguage): String {
    val actual = Copy.bytesSize(bytes, language)
    val expected = Copy.bytesSize(expectedBytes, language)
    return when {
        ready && actual.isNotBlank() -> actual
        bytes > 0L && expected.isNotBlank() -> "$actual / $expected"
        expected.isNotBlank() -> expected
        else -> actual
    }
}
