package com.emre.wearbook.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.emre.wearbook.upload.UploadServer
import com.emre.wearbook.upload.UploadServerService

@Composable
fun UploaderScreen() {
    val context = LocalContext.current
    val running by UploadServerService.running.collectAsState()
    val ip = remember { UploadServer.watchIpv4() ?: "?" }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    fun ensurePermissionsAndStart() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) {
            UploadServerService.start(context)
        } else {
            permLauncher.launch(wanted.toTypedArray())
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (running) "Uploader on\nhttp://$ip:${UploadServerService.PORT}"
            else "Uploader off",
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { if (running) UploadServerService.stop(context) else ensurePermissionsAndStart() },
        ) { Text(if (running) "Stop" else "Start") }
    }
}
