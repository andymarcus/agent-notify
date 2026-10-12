package com.agentnotify.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
    fileKeyProvider: () -> String,
    copyFileKey: (String) -> Unit,
    openAttachment: (String, String?) -> Unit,
    downloadAttachment: (Long) -> Unit,
) {
    val messages by repository.messages.collectAsState()
    var selectedTopic by rememberSaveable { mutableStateOf<String?>(null) }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var expandedMessageIds by remember { mutableStateOf(emptySet<Long>()) }
    var pendingDeletion by remember { mutableStateOf<AgentMessage?>(null) }
    var confirmDeleteRead by remember { mutableStateOf(false) }
    // Messages read while viewing "Unread Only" stay visible until the view changes,
    // so tapping a message to read or expand it does not make it vanish mid-read.
    var keptVisibleIds by remember(selectedTopic, unreadOnly) { mutableStateOf(emptySet<Long>()) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val topics = remember(messages) {
        messages.groupBy { it.topic }
            .map { (topic, items) -> TopicSummary(topic, items.count { !it.isRead }) }
            .sortedBy { it.name.lowercase() }
    }
    // Fall back to all messages when the selected topic no longer has any messages.
    LaunchedEffect(topics, selectedTopic) {
        if (selectedTopic != null && topics.none { it.name == selectedTopic }) selectedTopic = null
    }
    val inTopic = remember(messages, selectedTopic) {
        selectedTopic?.let { topic -> messages.filter { it.topic == topic } } ?: messages
    }
    val visible = remember(inTopic, unreadOnly, keptVisibleIds) {
        if (unreadOnly) inTopic.filter { !it.isRead || it.id in keptVisibleIds } else inTopic
    }
    val unreadInTopic = inTopic.count { !it.isRead }
    val readInTopic = inTopic.size - unreadInTopic

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TopicDrawer(
                totalUnread = messages.count { !it.isRead },
                topics = topics,
                selectedTopic = selectedTopic,
                onSelect = { topic ->
                    selectedTopic = topic
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Topics")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                selectedTopic ?: "All Messages",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${inTopic.size} saved · $unreadInTopic unread",
                                style = MaterialTheme.typography.labelMedium,
                            )
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
                FilterBar(
                    unreadOnly = unreadOnly,
                    onUnreadOnlyChange = { unreadOnly = it },
                    canDeleteRead = readInTopic > 0,
                    onDeleteRead = { confirmDeleteRead = true },
                )

                if (visible.isEmpty()) {
                    EmptyState(
                        modifier = Modifier.fillMaxSize(),
                        caughtUp = unreadOnly && inTopic.isNotEmpty(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visible, key = { it.id }) { message ->
                            SwipeableMessage(
                                message = message,
                                isExpanded = message.id in expandedMessageIds,
                                onClick = {
                                    if (unreadOnly && !message.isRead) keptVisibleIds = keptVisibleIds + message.id
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
                                onDownload = { downloadAttachment(message.id) },
                                onOpenAttachment = { path, mime -> openAttachment(path, mime) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        var token by remember { mutableStateOf("") }
        var fileKey by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            tokenProvider { token = it }
            fileKey = fileKeyProvider()
        }
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
                Spacer(Modifier.height(24.dp))
                Text("Encrypted file transfer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Copy this public key and add it to the CLI. The private key never leaves this phone.")
                Spacer(Modifier.height(12.dp))
                AssistChip(
                    onClick = { copyFileKey(fileKey) },
                    enabled = fileKey.isNotBlank(),
                    label = { Text("Copy file key") },
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

    if (confirmDeleteRead) {
        val scopeLabel = selectedTopic?.let { "in “$it”" } ?: "across all topics"
        AlertDialog(
            onDismissRequest = { confirmDeleteRead = false },
            title = { Text("Delete read messages?") },
            text = {
                Text(
                    "This will permanently delete $readInTopic read " +
                        "${if (readInTopic == 1) "message" else "messages"} $scopeLabel. Unread messages are kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val removed = repository.deleteRead(selectedTopic)
                        val remainingIds = repository.messages.value.mapTo(HashSet()) { it.id }
                        expandedMessageIds = expandedMessageIds.filterTo(HashSet()) { it in remainingIds }
                        confirmDeleteRead = false
                        Toast.makeText(
                            context,
                            "Deleted $removed ${if (removed == 1) "message" else "messages"}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteRead = false }) { Text("Cancel") }
            },
        )
    }
}

private data class TopicSummary(val name: String, val unread: Int)

@Composable
private fun TopicDrawer(
    totalUnread: Int,
    topics: List<TopicSummary>,
    selectedTopic: String?,
    onSelect: (String?) -> Unit,
) {
    ModalDrawerSheet {
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            item {
                Text(
                    "Agent Notify",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text("All Messages", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Outlined.Inbox, contentDescription = null) },
                    badge = { UnreadBadge(totalUnread) },
                    selected = selectedTopic == null,
                    onClick = { onSelect(null) },
                )
            }
            if (topics.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    Text(
                        "Topics",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                }
            }
            items(topics, key = { it.name }) { topic ->
                NavigationDrawerItem(
                    label = { Text(topic.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
                    badge = { UnreadBadge(topic.unread) },
                    selected = selectedTopic == topic.name,
                    onClick = { onSelect(topic.name) },
                )
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    if (count > 0) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    unreadOnly: Boolean,
    onUnreadOnlyChange: (Boolean) -> Unit,
    canDeleteRead: Boolean,
    onDeleteRead: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            SegmentedButton(
                selected = !unreadOnly,
                onClick = { onUnreadOnlyChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("All", maxLines = 1) }
            SegmentedButton(
                selected = unreadOnly,
                onClick = { onUnreadOnlyChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Unread Only", maxLines = 1) }
        }
        OutlinedButton(
            onClick = onDeleteRead,
            enabled = canDeleteRead,
            contentPadding = PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Delete Read", maxLines = 1)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, caughtUp: Boolean = false) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center) {
        Icon(
            if (caughtUp) Icons.Outlined.DoneAll else Icons.Outlined.Notifications,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (caughtUp) "All caught up" else "No messages yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (caughtUp) "There are no unread messages here."
            else "Messages sent from the CLI will appear here and stay available offline."
        )
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
    onDownload: () -> Unit,
    onOpenAttachment: (String, String?) -> Unit,
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
                onDownload = onDownload,
                onOpenAttachment = onOpenAttachment,
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
    onDownload: () -> Unit,
    onOpenAttachment: (String, String?) -> Unit,
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
            if (message.attachmentId != null) {
                Spacer(Modifier.height(12.dp))
                AttachmentCard(message, onDownload, onOpenAttachment)
            }
        }
    }
}

@Composable
private fun AttachmentCard(
    message: AgentMessage,
    onDownload: () -> Unit,
    onOpenAttachment: (String, String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(message.attachmentName ?: "Attachment", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(message.attachmentSize ?: 0), style = MaterialTheme.typography.labelSmall)
            if (message.attachmentStatus == "ready") {
                Text("Saved to Downloads", style = MaterialTheme.typography.labelSmall)
            }
            message.attachmentError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
        }
        when (message.attachmentStatus) {
            "downloading" -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
            "ready" -> FilledTonalButton(onClick = {
                message.attachmentPath?.let { onOpenAttachment(it, message.attachmentMime) }
            }) { Text("Open") }
            else -> FilledTonalButton(onClick = onDownload) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (message.attachmentStatus == "failed") "Retry" else "Download")
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes bytes"
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
