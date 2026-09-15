package com.agentnotify.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.agentnotify.app.crypto.AttachmentCrypto
import com.agentnotify.app.ui.MarkdownViewerActivity
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.agentnotify.app.ui.AgentNotifyScreen
import com.agentnotify.app.ui.AgentNotifyTheme
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentNotifyTheme {
                val repository = (application as AgentNotifyApplication).repository
                var pendingDownloadId by remember { mutableStateOf<Long?>(null) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                val storagePermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    val attachmentId = pendingDownloadId
                    pendingDownloadId = null
                    if (granted && attachmentId != null) {
                        repository.downloadAttachment(attachmentId)
                    } else if (!granted) {
                        Toast.makeText(this@MainActivity, "Storage permission is needed to save files to Downloads", Toast.LENGTH_SHORT).show()
                    }
                }

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
                            repository = repository,
                            tokenProvider = { callback -> loadToken(callback) },
                            copyToken = ::copyToken,
                            fileKeyProvider = { AttachmentCrypto.publicKeyBase64() },
                            copyFileKey = ::copyFileKey,
                            openAttachment = ::openAttachment,
                            downloadAttachment = { id ->
                                if (
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    pendingDownloadId = id
                                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    repository.downloadAttachment(id)
                                }
                            },
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
        val uri = if (path.startsWith("content:")) Uri.parse(path) else FileProvider.getUriForFile(this, "$packageName.files", file)
        if (!path.startsWith("content:") && !file.exists()) {
            Toast.makeText(this, "Downloaded file is missing", Toast.LENGTH_SHORT).show()
            return
        }
        if (isMarkdownFile(file, mime)) {
            startActivity(MarkdownViewerActivity.intent(this, uri.toString()))
            return
        }
        val isApk = isApkFile(file, mime)
        val intent = Intent(if (isApk) Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (isApk) APK_MIME_TYPE else mime ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val activity = if (isApk) intent else Intent.createChooser(intent, "Open attachment")
        runCatching { startActivity(activity) }
            .onFailure { Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show() }
    }

    private fun isMarkdownFile(file: File, mime: String?): Boolean =
        mime.equals("text/markdown", ignoreCase = true) ||
            mime.equals("text/x-markdown", ignoreCase = true) ||
            file.extension.equals("md", ignoreCase = true) ||
            file.extension.equals("markdown", ignoreCase = true)

    private fun isApkFile(file: File, mime: String?): Boolean =
        mime.equals(APK_MIME_TYPE, ignoreCase = true) || file.extension.equals("apk", ignoreCase = true)

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
