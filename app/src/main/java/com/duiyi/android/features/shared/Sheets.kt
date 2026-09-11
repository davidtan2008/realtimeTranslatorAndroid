package com.duiyi.android.features.shared

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.duiyi.android.designsystem.LanguageChipGrid
import com.duiyi.android.designsystem.Tokens
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.CaptionFontSize
import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.LanguageCataloging
import com.duiyi.android.domain.SummaryAvailability
import java.io.File

@Composable
fun KeepAwake(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
fun PauseOnStop(onStop: () -> Unit) {
    val owner = ProcessLifecycleOwner.get()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onStop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    confirm: String,
    cancel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.sheet,
        titleContentColor = Tokens.textPrimary,
        textContentColor = Tokens.textSecondary,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) Tokens.danger else Tokens.listen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancel, color = Tokens.textSecondary) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    visible: Boolean,
    language: AppLanguage,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Tokens.sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(Copy.settingsTitle(language), color = Tokens.textPrimary, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text(Copy.done(language), color = Tokens.listen, modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(Copy.settingsHint(language), color = Tokens.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun ModelsManagerEntry(language: AppLanguage, onOpen: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text(
        Copy.modelsTitle(language),
        color = Tokens.listen,
        modifier = Modifier.clickable(onClick = onOpen).padding(vertical = 8.dp),
    )
    Text(Copy.modelsSubtitle(language), color = Tokens.textSecondary, fontSize = 13.sp)
}

@Composable
fun FontSizeRow(language: AppLanguage, selected: CaptionFontSize, onSelect: (CaptionFontSize) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        CaptionFontSize.entries.forEach { size ->
            val label = when (size) {
                CaptionFontSize.Standard -> Copy.fontStandard(language)
                CaptionFontSize.Larger -> Copy.fontLarger(language)
                CaptionFontSize.Largest -> Copy.fontLargest(language)
            }
            Text(
                label,
                color = if (size == selected) Tokens.listen else Tokens.textSecondary,
                modifier = Modifier
                    .clickable { onSelect(size) }
                    .padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
fun LanguageSection(
    title: String,
    selected: AppLanguage,
    catalog: LanguageCataloging,
    onSelect: (AppLanguage) -> Unit,
) {
    Text(title, color = Tokens.textSecondary, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    LanguageChipGrid(selected, catalog, onSelect)
    Spacer(Modifier.height(16.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummarySheet(
    visible: Boolean,
    language: AppLanguage,
    availability: SummaryAvailability,
    message: String?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.sheet) {
        Column(Modifier.padding(20.dp).padding(bottom = 32.dp)) {
            Text(Copy.summaryTitle(language), color = Tokens.textPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                if (loading) Copy.summaryLoading(language) else message ?: Copy.summaryAvailabilityMessage(availability, language),
                color = Tokens.textSecondary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text(Copy.done(language), color = Tokens.listen, modifier = Modifier.clickable(onClick = onDismiss))
        }
    }
}

fun shareFile(activity: Activity, file: File, title: String) {
    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.export", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    activity.startActivity(Intent.createChooser(intent, title))
}
