package com.agentnotify.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
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
}

