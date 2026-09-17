package com.darkmessage.app.ui.screens.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.darkmessage.app.R
import com.darkmessage.app.data.model.Chat
import com.darkmessage.app.ui.components.AvatarIcon
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Set by the scanner "Enter manually" fallback: opens the normal New chat dialog. */
private const val NAV_RESULT_OPEN_ADD_CHAT = "open_add_chat"

/** Snackbar text handed back after a chat was added / its key updated from a QR code. */
private const val NAV_RESULT_QR_TOAST = "qr_toast"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onShowQr: (Chat) -> Unit,
    onScanQr: () -> Unit,
    viewModel: ChatsViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val fingerprints by viewModel.fingerprints.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Results the QR routes write into the SavedStateHandle of this destination
    // (previousBackStackEntry.savedStateHandle). navigation-compose provides the back stack
    // entry as the local ViewModelStoreOwner, so no NavController reference is needed here.
    val navEntry = LocalViewModelStoreOwner.current as? NavBackStackEntry
    val navResults = remember(navEntry) {
        navEntry?.let { entry -> runCatching { entry.savedStateHandle }.getOrNull() }
    }
    navResults?.let { handle ->
        QrNavResults(
            handle = handle,
            onOpenAddChat = { viewModel.showAddDialog() },
            onToast = { text -> scope.launch { snackbarHostState.showSnackbar(text) } }
        )
    }

    // A chat whose key could not be stored is not created at all; say so rather than
    // leaving one in the list that answers "no passphrase" to everything sent to it.
    val context = LocalContext.current
    uiState.errorRes?.let { res ->
        LaunchedEffect(res) {
            snackbarHostState.showSnackbar(context.getString(res))
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chats_title)) },
                actions = {
                    IconButton(onClick = onScanQr) {
                        Icon(
                            Icons.Outlined.QrCodeScanner,
                            contentDescription = stringResource(R.string.qr_scan)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showAddDialog() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chats_add))
            }
        }
    ) { padding ->
        if (chats.isEmpty()) {
            EmptyChatsPlaceholder(
                onScanQr = onScanQr,
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(chats, key = { it.id }) { chat ->
                    ChatListItem(
                        chat = chat,
                        fingerprint = fingerprints[chat.id],
                        onShowQr = { onShowQr(chat) },
                        onEdit = { viewModel.showEditDialog(chat) },
                        onDelete = { viewModel.showDeleteDialog(chat) }
                    )
                }
            }
        }

        when (val dialog = uiState.dialogState) {
            is ChatDialogState.AddChat -> {
                AddChatDialog(
                    onDismiss = { viewModel.dismissDialog() },
                    onConfirm = { name, passphrase -> viewModel.addChat(name, passphrase) }
                )
            }
            is ChatDialogState.EditChat -> {
                EditChatDialog(
                    chat = dialog.chat,
                    currentPassphrase = dialog.currentPassphrase,
                    onDismiss = { viewModel.dismissDialog() },
                    onConfirm = { name, passphrase ->
                        viewModel.updateChat(dialog.chat, name, passphrase)
                    }
                )
            }
            is ChatDialogState.DeleteChat -> {
                DeleteChatDialog(
                    chat = dialog.chat,
                    onDismiss = { viewModel.dismissDialog() },
                    onConfirm = { viewModel.deleteChat(dialog.chat) }
                )
            }
            ChatDialogState.None -> {}
        }
    }
}

/**
 * Consumes the one-shot results of the QR routes. Each key is cleared as soon as it is read, so
 * it never fires a second time after a rotation or when this screen is shown again.
 */
@Composable
private fun QrNavResults(
    handle: SavedStateHandle,
    onOpenAddChat: () -> Unit,
    onToast: (String) -> Unit
) {
    // getStateFlow() wraps its backing flow anew on every call, so the flows are remembered:
    // otherwise collectAsState() would restart its collector on every recomposition.
    val openAddChatFlow = remember(handle) {
        handle.getStateFlow(NAV_RESULT_OPEN_ADD_CHAT, false)
    }
    val openAddChat by openAddChatFlow.collectAsState()
    LaunchedEffect(openAddChat) {
        if (openAddChat) {
            handle[NAV_RESULT_OPEN_ADD_CHAT] = false
            onOpenAddChat()
        }
    }

    val qrToastFlow = remember(handle) {
        handle.getStateFlow<String?>(NAV_RESULT_QR_TOAST, null)
    }
    val qrToast by qrToastFlow.collectAsState()
    LaunchedEffect(qrToast) {
        val text = qrToast
        if (!text.isNullOrBlank()) {
            handle.set<String?>(NAV_RESULT_QR_TOAST, null)
            onToast(text)
        }
    }
}

@Composable
private fun EmptyChatsPlaceholder(
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .height(64.dp)
                    .width(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                stringResource(R.string.chats_empty_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.chats_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(onClick = onScanQr) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.qr_scan))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListItem(
    chat: Chat,
    fingerprint: String?,
    onShowQr: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = { showMenu = true }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarIcon(name = chat.name, colorHue = chat.colorHue)

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                chat.lastActivityAt?.let { timestamp ->
                    Text(
                        text = formatTimestamp(timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Key fingerprint: not secret, and the fastest way for two people to see
                // whether their keys match without ever showing the key itself.
                fingerprint?.let { value ->
                    Text(
                        text = stringResource(R.string.chats_fingerprint, value),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(onClick = onShowQr) {
                Icon(
                    Icons.Outlined.QrCode2,
                    contentDescription = stringResource(R.string.qr_show)
                )
            }

            Box {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chats_edit)) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.qr_show)) },
                        onClick = {
                            showMenu = false
                            onShowQr()
                        },
                        leadingIcon = { Icon(Icons.Outlined.QrCode2, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chats_delete)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddChatDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, passphrase: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chats_new_chat)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.chats_contact_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.chats_passphrase)) },
                    singleLine = true,
                    visualTransformation = if (showPassphrase) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(stringResource(R.string.chats_passphrase_hint))
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showPassphrase = !showPassphrase }) {
                        Text(
                            if (showPassphrase) stringResource(R.string.chats_hide)
                            else stringResource(R.string.chats_show)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { passphrase = ChatsViewModel.generateKey() }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(stringResource(R.string.chats_generate_key))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, passphrase) },
                enabled = name.isNotBlank() && passphrase.isNotBlank()
            ) {
                Text(stringResource(R.string.chats_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chats_cancel))
            }
        }
    )
}

@Composable
private fun EditChatDialog(
    chat: Chat,
    currentPassphrase: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, newPassphrase: String?) -> Unit
) {
    var name by remember { mutableStateOf(chat.name) }
    var passphrase by remember { mutableStateOf(currentPassphrase) }
    var showPassphrase by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chats_edit_chat)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.chats_contact_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.chats_passphrase)) },
                    singleLine = true,
                    visualTransformation = if (showPassphrase) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showPassphrase = !showPassphrase }) {
                        Text(
                            if (showPassphrase) stringResource(R.string.chats_hide)
                            else stringResource(R.string.chats_show)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { passphrase = ChatsViewModel.generateKey() }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(stringResource(R.string.chats_generate_key))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newPass = if (passphrase != currentPassphrase) passphrase else null
                    onConfirm(name, newPass)
                },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.chats_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chats_cancel))
            }
        }
    )
}

@Composable
private fun DeleteChatDialog(
    chat: Chat,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chats_delete_chat)) },
        text = {
            Text(stringResource(R.string.chats_delete_confirm, chat.name))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
