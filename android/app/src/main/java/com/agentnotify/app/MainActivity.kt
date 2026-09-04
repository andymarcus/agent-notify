package com.agentnotify.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import androidx.core.content.FileProvider
import com.agentnotify.app.crypto.AttachmentCrypto
import java.io.File
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.agentnotify.app.ui.AgentNotifyScreen
import com.agentnotify.app.ui.AgentNotifyTheme
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentNotifyTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                LaunchedEffect(Unit) {
                    if (
                        Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    refreshToken()
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        AgentNotifyScreen(
                            repository = (application as AgentNotifyApplication).repository,
                            tokenProvider = { callback -> loadToken(callback) },
                            copyToken = ::copyToken,
                            fileKeyProvider = { AttachmentCrypto.publicKeyBase64() },
                            copyFileKey = ::copyFileKey,
                            openAttachment = ::openAttachment,
                        )
                    }
                }
            }
        }
    }

    private fun refreshToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            getSharedPreferences("agent-notify", MODE_PRIVATE)
                .edit().putString("fcm_token", token).apply()
        }
    }

    private fun loadToken(callback: (String) -> Unit) {
        val cached = getSharedPreferences("agent-notify", MODE_PRIVATE)
            .getString("fcm_token", "").orEmpty()
        if (cached.isNotEmpty()) callback(cached)
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener(callback)
            .addOnFailureListener { callback("") }
    }

    private fun copyToken(token: String) {
        if (token.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FCM device token", token))
        Toast.makeText(this, "Device token copied", Toast.LENGTH_SHORT).show()
    }

    private fun copyFileKey(key: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Agent Notify file key", key))
        Toast.makeText(this, "File key copied", Toast.LENGTH_SHORT).show()
    }

    private fun openAttachment(path: String, mime: String?) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Downloaded file is missing", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Open attachment")) }
            .onFailure { Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show() }
    }
}
