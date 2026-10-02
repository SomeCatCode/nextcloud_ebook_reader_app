package com.somecatcode.ebookreader.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.somecatcode.ebookreader.ui.util.UiPrefs

/**
 * Wraps the first download: on Android 13+ the notification permission is requested once before the
 * download starts (the download service shows progress as a notification). The download proceeds
 * whether or not the permission is granted.
 *
 * Usage: `val gate = rememberDownloadGate(); gate { vm.makeOffline() }`.
 */
@Composable
fun rememberDownloadGate(): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { action ->
        val prefs = UiPrefs(context)
        val needsRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.notificationPermissionAsked
        if (needsRequest) {
            prefs.notificationPermissionAsked = true
            pending = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
}
