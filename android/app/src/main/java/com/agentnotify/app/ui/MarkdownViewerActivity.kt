package com.agentnotify.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MarkdownViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_FILE_URI)?.let(Uri::parse)
        val fileName = uri?.let(::displayName).orEmpty().ifBlank { "Markdown file" }
        val (content, error) = when {
            uri == null -> "" to "This Markdown file is no longer available."
            else -> runCatching {
                requireNotNull(contentResolver.openInputStream(uri)) { "File is unavailable" }
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }.fold(
                onSuccess = { it to null },
                onFailure = { "" to "Could not read this Markdown file." },
            )
        }

        setContent {
            AgentNotifyTheme {
                MarkdownViewer(
                    title = fileName,
                    markdown = content,
                    error = error,
                    onBack = ::finish,
                )
            }
        }
    }

    companion object {
        private const val EXTRA_FILE_URI = "file_uri"

        fun intent(context: Context, uri: String): Intent =
            Intent(context, MarkdownViewerActivity::class.java)
                .putExtra(EXTRA_FILE_URI, uri)
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?: uri.lastPathSegment.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownViewer(
    title: String,
    markdown: String,
    error: String?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            MarkdownText(
                markdown = markdown,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}
