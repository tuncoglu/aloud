package com.emre.aloud.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.emre.aloud.R
import com.emre.aloud.upload.UploadServer
import com.emre.aloud.upload.UploadServerService

@Composable
fun UploaderScreen() {
    val context = LocalContext.current
    val running by UploadServerService.running.collectAsState()
    val pin by UploadServerService.pin.collectAsState()
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
            text = if (running) "http://$ip:${UploadServerService.PORT}"
            else stringResource(R.string.uploader_off),
            textAlign = TextAlign.Center,
        )
        if (running) {
            Text(
                text = stringResource(R.string.uploader_pin, pin ?: "…"),
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = { if (running) UploadServerService.stop(context) else ensurePermissionsAndStart() },
        ) { Text(stringResource(if (running) R.string.action_stop else R.string.action_start)) }
    }
}
