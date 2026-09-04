package com.agentnotify.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.agentnotify.app.data.AgentMessage
import com.agentnotify.app.data.MessageRepository
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentNotifyScreen(
    repository: MessageRepository,
    tokenProvider: (((String) -> Unit) -> Unit),
    copyToken: (String) -> Unit,
) {
    val messages by repository.messages.collectAsState()
    var selectedTopic by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var expandedMessageIds by remember { mutableStateOf(emptySet<Long>()) }
    var pendingDeletion by remember { mutableStateOf<AgentMessage?>(null) }
    val context = LocalContext.current
    val topics = remember(messages) { messages.map { it.topic }.distinct().sortedBy { it.lowercase() } }
    val filtered = remember(messages, selectedTopic) {
        selectedTopic?.let { topic -> messages.filter { it.topic == topic } } ?: messages
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agent Notify", fontWeight = FontWeight.Bold)
                        Text("${messages.size} saved messages", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedTopic == null,
                        onClick = { selectedTopic = null },
                        label = { Text("All") },
                    )
                }
                items(topics) { topic ->
                    FilterChip(
                        selected = selectedTopic == topic,
                        onClick = { selectedTopic = topic },
                        label = { Text(topic) },
                    )
                }
            }

            if (filtered.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filtered, key = { it.id }) { message ->
                        SwipeableMessage(
                            message = message,
                            isExpanded = message.id in expandedMessageIds,
                            onClick = {
                                repository.markRead(message.id)
                                if (isLongMessage(message.body)) {
                                    expandedMessageIds = if (message.id in expandedMessageIds) {
                                        expandedMessageIds - message.id
                                    } else {
                                        expandedMessageIds + message.id
                                    }
                                }
                            },
                            onLongClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(message.topic, message.body))
                                Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
                            },
                            onDeleteRequested = { pendingDeletion = message },
                            onMarkUnread = { repository.markUnread(message.id) },
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        var token by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { tokenProvider { token = it } }
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 40.dp)) {
                Text("Connect the CLI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Copy this device token into the agent-notify configure command on your Mac.")
                Spacer(Modifier.height(16.dp))
                Text(
                    text = token.ifEmpty { "Loading Firebase token…" },
                    fontFamily = FontFamily.Monospace,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                AssistChip(
                    onClick = { copyToken(token) },
                    enabled = token.isNotBlank(),
                    label = { Text("Copy token") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                )
            }
        }
    }

    pendingDeletion?.let { message ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete message?") },
            text = { Text("This will permanently delete the message from “${message.topic}”.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.delete(message.id)
                        expandedMessageIds = expandedMessageIds - message.id
                        pendingDeletion = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("No messages yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Messages sent from the CLI will appear here and stay available offline.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMessage(
    message: AgentMessage,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteRequested: () -> Unit,
    onMarkUnread: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onDeleteRequested()
                SwipeToDismissBoxValue.EndToStart -> onMarkUnread()
                SwipeToDismissBoxValue.Settled -> return@rememberSwipeToDismissBoxState true
            }
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SwipeAction(Icons.Outlined.Delete, "Delete", MaterialTheme.colorScheme.error)
                SwipeAction(Icons.Outlined.MarkEmailUnread, "Unread", MaterialTheme.colorScheme.primary)
            }
        },
        content = {
            MessageCard(
                message = message,
                isExpanded = isExpanded,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        },
    )
}

@Composable
private fun SwipeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = color)
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageCard(
    message: AgentMessage,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val displayedBody = remember(message.body, isExpanded) {
        if (isExpanded) message.body else collapsedBody(message.body)
    }
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (!message.isRead) {
                        Icon(
                            Icons.Outlined.MarkEmailUnread,
                            contentDescription = "Unread",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        message.topic,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = if (message.isRead) FontWeight.SemiBold else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.receivedAt)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            MarkdownText(displayedBody)
            if (isLongMessage(message.body)) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isExpanded) "Tap to collapse" else "Tap to expand",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun isLongMessage(body: String): Boolean =
    body.length > COLLAPSED_CHARACTER_LIMIT || body.lines().size > COLLAPSED_LINE_LIMIT

private fun collapsedBody(body: String): String {
    if (!isLongMessage(body)) return body

    var preview = body.lines().take(COLLAPSED_LINE_LIMIT).joinToString("\n")
    if (preview.length > COLLAPSED_CHARACTER_LIMIT) {
        preview = preview.take(COLLAPSED_CHARACTER_LIMIT)
    }
    return preview.trimEnd() + "…"
}

private const val COLLAPSED_CHARACTER_LIMIT = 280
private const val COLLAPSED_LINE_LIMIT = 5
