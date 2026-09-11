package com.duiyi.android.services.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.duiyi.android.domain.PermissionGranting
import com.duiyi.android.domain.PermissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class PermissionService @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionGranting {

    @Volatile
    var activityRequester: (suspend () -> Boolean)? = null

    override suspend fun currentStatus(): PermissionStatus {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) PermissionStatus.Granted else PermissionStatus.MicrophoneDenied
    }

    override suspend fun requestAccess(): PermissionStatus {
        if (currentStatus() == PermissionStatus.Granted) return PermissionStatus.Granted
        val requester = activityRequester
            ?: return PermissionStatus.MicrophoneDenied
        val ok = requester()
        return if (ok) PermissionStatus.Granted else PermissionStatus.MicrophoneDenied
    }
}

/**
 * Bridges Activity Result into [PermissionService] without Feature files
 * calling Activity APIs for business logic.
 */
class PermissionBridge(
    private val service: PermissionService,
) {
    fun attach(request: suspend () -> Boolean) {
        service.activityRequester = request
    }

    fun detach() {
        service.activityRequester = null
    }
}
