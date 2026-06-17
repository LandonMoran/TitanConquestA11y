package com.titanconquest.a11y.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

import com.titanconquest.a11y.model.ChatMessage

@Composable
fun ChatScreen(
    globalMessages: List<ChatMessage>,
    clanMessages: List<ChatMessage>,
    onLoadChat: (clan: Boolean) -> Unit,
    onSend: (message: String, clan: Boolean) -> Unit
) {
    var isClan by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val messages = if (isClan) clanMessages else globalMessages

    LaunchedEffect(isClan) { onLoadChat(isClan) }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {

        // ── Tab selector ──────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !isClan,
                onClick = { isClan = false },
                label = { Text("Global") },
                modifier = Modifier.semantics {
                    contentDescription = if (!isClan) "Global chat, selected" else "Switch to global chat"
                }
            )
            FilterChip(
                selected = isClan,
                onClick = { isClan = true },
                label = { Text("Clan") },
                modifier = Modifier.semantics {
                    contentDescription = if (isClan) "Clan chat, selected" else "Switch to clan chat"
                }
            )
        }

        HorizontalDivider()

        // ── Messages ──────────────────────────────────────────────────────────
        if (messages.isEmpty()) {
            Box(
                Modifier.weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No messages yet.",
                    modifier = Modifier.semantics {
                        contentDescription = "No chat messages. Send one below."
                    }
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { "${it.sender}${it.timestamp}" }) { msg ->
                    ChatBubble(msg)
                }
            }
        }

        HorizontalDivider()

        // ── Input bar ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "Chat message input. Type your message here."
                    },
                placeholder = { Text("Message...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (draft.isNotBlank()) {
                            onSend(draft.trim(), isClan)
                            draft = ""
                            focusManager.clearFocus()
                        }
                    }
                )
            )
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onSend(draft.trim(), isClan)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Send message" }
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = msg.toContentDescription()
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                msg.sender,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                msg.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(msg.message, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}
