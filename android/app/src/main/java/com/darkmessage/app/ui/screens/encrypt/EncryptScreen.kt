package com.darkmessage.app.ui.screens.encrypt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.darkmessage.app.R
import com.darkmessage.app.data.model.EncryptionResult
import com.darkmessage.app.ui.components.ChatSelector
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptScreen(
    viewModel: EncryptViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        viewModel.setImageUri(uri)
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.setDocumentUri(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.encrypt_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Bottom inset keeps the last result card clear of the navigation bar
                // (cards used to end 2 px above it, and the document card was clipped).
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                // Keeps the Encrypt button reachable while the keyboard is up.
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Chat selector
            ChatSelector(
                chats = chats,
                selectedChat = uiState.selectedChat,
                onChatSelected = { viewModel.selectChat(it) }
            )

            // Input mode toggle
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.inputMode == InputMode.TEXT,
                    onClick = { viewModel.setInputMode(InputMode.TEXT) },
                    label = { Text(stringResource(R.string.encrypt_text)) }
                )
                FilterChip(
                    selected = uiState.inputMode == InputMode.IMAGE,
                    onClick = { viewModel.setInputMode(InputMode.IMAGE) },
                    label = { Text(stringResource(R.string.encrypt_photo)) }
                )
                FilterChip(
                    selected = uiState.inputMode == InputMode.DOCUMENT,
                    onClick = { viewModel.setInputMode(InputMode.DOCUMENT) },
                    label = { Text(stringResource(R.string.encrypt_document)) }
                )
            }

            // Input area
            when (uiState.inputMode) {
                InputMode.TEXT -> {
                    OutlinedTextField(
                        value = uiState.textInput,
                        onValueChange = { viewModel.setTextInput(it) },
                        label = { Text(stringResource(R.string.encrypt_message)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        maxLines = 10,
                        trailingIcon = {
                            if (uiState.textInput.isNotEmpty()) {
                                IconButton(onClick = { viewModel.clearInput() }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.encrypt_clear))
                                }
                            }
                        }
                    )
                }
                InputMode.IMAGE -> {
                    OutlinedButton(
                        onClick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.encrypt_select_image))
                    }
                    uiState.imageUri?.let { uri ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        TextButton(onClick = { viewModel.clearInput() }) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(stringResource(R.string.encrypt_clear))
                        }
                    }
                }
                InputMode.DOCUMENT -> {
                    OutlinedButton(
                        onClick = {
                            documentPicker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.encrypt_select_document))
                    }
                    uiState.documentName?.let { name ->
                        Text(
                            text = stringResource(R.string.encrypt_document_selected, name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { viewModel.clearInput() }) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(stringResource(R.string.encrypt_clear))
                        }
                    }
                }
            }

            // Encrypt button
            Button(
                onClick = { viewModel.encrypt() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isEncrypting && uiState.selectedChat != null
            ) {
                if (uiState.isEncrypting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.encrypt_encrypting))
                } else {
                    Text(stringResource(R.string.encrypt_button))
                }
            }

            // Error message
            uiState.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Result
            when (val result = uiState.result) {
                is EncryptionResult.Success -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.encrypt_success),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            result.base64Text?.let { base64 ->
                                Text(
                                    text = base64,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("encrypted", base64))
                                            Toast.makeText(context, context.getString(R.string.encrypt_copied), Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.size(4.dp))
                                        Text(stringResource(R.string.encrypt_copy))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            shareTextViaChooser(context, base64)
                                        }
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.size(4.dp))
                                        Text(stringResource(R.string.encrypt_send))
                                    }
                                }
                            }

                            result.rawBytes?.let { bytes ->
                                if (result.base64Text != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.encrypt_or),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                OutlinedButton(
                                    onClick = { saveDarkmFileAndShare(context, bytes) }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(stringResource(R.string.encrypt_send_file))
                                }
                            }
                        }
                    }
                }
                is EncryptionResult.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = result.message,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                null -> {}
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun shareTextViaChooser(context: Context, text: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }
    val chooserIntent = Intent.createChooser(sendIntent, context.getString(R.string.encrypt_send_to))
    context.startActivity(chooserIntent)
}

private fun saveDarkmFileAndShare(context: Context, bytes: ByteArray) {
    try {
        val fileName = "message_${System.currentTimeMillis()}.darkm"
        val file = File(context.cacheDir, fileName)
        file.writeBytes(bytes)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/octet-stream"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.encrypt_send_file_to))
        )
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.error, e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}
