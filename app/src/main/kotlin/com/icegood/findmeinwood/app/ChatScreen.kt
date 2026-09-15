package com.icegood.findmeinwood.app

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.icegood.findmeinwood.core.chat.ChatMessage
import com.icegood.findmeinwood.core.chat.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chatRepo: ChatRepository,
    onSelectChannel: (String) -> Unit,
) {
    val channels by chatRepo.observeChannelIds().collectAsState(initial = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("Chats") }) },
    ) { pad ->
        if (channels.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Messages with your group appear here.\nShare your network code with peers to start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(8.dp)) {
                items(channels) { channelId ->
                    val latest = chatRepo.getLatestMessage(channelId)
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelectChannel(channelId) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val label = if (channelId == "group") "Group" else channelId
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                                latest?.let {
                                    val preview = when {
                                        it.photoRef != null -> "📷 Photo"
                                        it.text.isBlank() -> "(empty)"
                                        else -> it.text
                                    }
                                    Text(
                                        "${it.senderName}: $preview",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            latest?.let {
                                Text(
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onBack: (() -> Unit)? = null,
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
            val file = File(context.filesDir, "chat_photos").apply { mkdirs() }
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
        topBar = {
            TopAppBar(
                title = { Text(if (channelId == "group") "Group" else channelId) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Text("‹", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(horizontalAlignment = if (msg.isOwn) Alignment.End else Alignment.Start) {
            if (!msg.isOwn && msg.senderName.isNotBlank()) {
                Text(
                    msg.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = color),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    Modifier.widthIn(max = 280.dp).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val photo = msg.photoRef
                    if (photo != null) {
                        PhotoPreview(photo)
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
}

@Composable
private fun PhotoPreview(photoRef: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, photoRef) {
        value = withContext(Dispatchers.IO) { decodePhoto(photoRef) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).fillMaxWidth().height(160.dp),
            contentScale = ContentScale.Crop,
        )
    } else {
        Text(
            "📷 [photo]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun decodePhoto(photoRef: String): android.graphics.Bitmap? {
    return try {
        val uri = Uri.parse(photoRef)
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            if (!File(path).exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val sample = maxOf(1, (bounds.outWidth / 480) / 2)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}