package app.findmeinwood.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.findmeinwood.core.chat.ChatMessage
import app.findmeinwood.core.chat.ChatRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chatRepo: ChatRepository,
    onSelectChannel: (String) -> Unit,
) {
    val channels by remember { mutableStateOf(chatRepo.getChannelIds()) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Chats") }) },
    ) { pad ->
        if (channels.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No conversations yet.\nStart a network session to chat.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(8.dp)) {
                items(channels) { channelId ->
                    val latest = chatRepo.getLatestMessage(channelId)
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelectChannel(channelId) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(channelId, style = MaterialTheme.typography.titleMedium)
                            latest?.let {
                                Text(
                                    it.text.ifBlank { "[photo]" },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    channelId: String,
    chatRepo: ChatRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    val messages = chatRepo.observeMessages(channelId).collectAsState(initial = emptyList())
    val listState = rememberLazyListState()

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(it) ?: return@let
            val fileName = "chat_${System.currentTimeMillis()}.jpg"
            val file = java.io.File(context.filesDir, "chat_photos").apply { mkdirs() }
                .resolve(fileName)
            file.outputStream().use { out -> inputStream.copyTo(out) }
            scope.launch {
                chatRepo.sendPhoto("file://${file.absolutePath}", channelId)
            }
        }
    }

    LaunchedEffect(messages.value.size) {
        if (messages.value.isNotEmpty()) {
            listState.animateScrollToItem(messages.value.size - 1)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(channelId) }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(messages.value) { msg ->
                    ChatBubble(msg)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { photoLauncher.launch("image/*") },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text("📷") }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = inputText, onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            scope.launch {
                                chatRepo.sendText(inputText, channelId)
                                inputText = ""
                            }
                        }
                    },
                    enabled = inputText.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val alignment = if (msg.isOwn) Alignment.CenterEnd else Alignment.CenterStart
    val color = if (msg.isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.secondaryContainer
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = alignment) {
        Card(colors = CardDefaults.cardColors(containerColor = color)) {
            Column(Modifier.padding(8.dp)) {
                if (!msg.isOwn) {
                    Text(msg.senderName, style = MaterialTheme.typography.labelSmall)
                }
                if (msg.photoRef != null) {
                    Text("[photo]", style = MaterialTheme.typography.bodySmall)
                }
                if (msg.text.isNotBlank()) {
                    Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    timeFormat.format(Date(msg.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
