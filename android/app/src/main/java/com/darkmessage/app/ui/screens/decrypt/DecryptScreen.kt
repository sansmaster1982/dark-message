package com.darkmessage.app.ui.screens.decrypt

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.darkmessage.app.Incoming
import com.darkmessage.app.R
import com.darkmessage.app.data.model.DecryptionResult
import com.darkmessage.app.ui.components.ChatSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecryptScreen(
    viewModel: DecryptViewModel = hiltViewModel(),
    incoming: Incoming? = null,
    onIncomingConsumed: () -> Unit = {}
) {
    val chats by viewModel.chats.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // A file or text handed to the app by another app (share sheet / "Open with").
    // MainViewModel holds it until it is consumed here, so a rotation never re-imports it.
    LaunchedEffect(incoming) {
        when (incoming) {
            is Incoming.File -> viewModel.receiveFile(incoming.uri)
            is Incoming.Text -> viewModel.receiveText(incoming.text)
            // A chat-key QR link is handled by the scan_qr route, not here. Leave it pending
            // (do NOT consume it) and never feed it into the decrypt input.
            is Incoming.ChatQr -> return@LaunchedEffect
            null -> return@LaunchedEffect
        }
        onIncomingConsumed()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        // null = picker cancelled: keep whatever was selected before
        if (uri != null) {
            viewModel.setImportedFileUri(uri)
        }
    }

    // "Save document": the user picks the destination through the Storage Access Framework.
    // The contract's MIME type follows the decrypted document's extension.
    val documentResult = uiState.result as? DecryptionResult.DocumentMessage
    val documentMime = remember(documentResult?.fileName) { mimeTypeForFileName(documentResult?.fileName) }
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = remember(documentMime) { ActivityResultContracts.CreateDocument(documentMime) }
    ) { uri ->
        if (uri != null) {
            viewModel.saveDocumentTo(uri) { ok ->
                val messageRes = if (ok) R.string.decrypt_saved_document else R.string.decrypt_error_save
                Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.decrypt_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Bottom inset keeps the last result card clear of the navigation bar
                // (cards used to end 2 px above it, and the document card was clipped).
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                // Without this the scroll viewport keeps its full height while the
                // keyboard is up, so the Decrypt button sits underneath it and
                // scrolling does nothing: there is no overflow to scroll.
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Received file / text waiting for a chat
            val hintRes = uiState.incomingHintRes
            if (hintRes != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(hintRes),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val receivedName = uiState.importedFileName
                        if (receivedName != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.decrypt_incoming_file, receivedName),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // Chat selector
            ChatSelector(
                chats = chats,
                selectedChat = uiState.selectedChat,
                onChatSelected = { viewModel.selectChat(it) }
            )

            // Input mode toggle
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.inputMode == DecryptInputMode.PASTE_TEXT,
                    onClick = { viewModel.setInputMode(DecryptInputMode.PASTE_TEXT) },
                    label = { Text(stringResource(R.string.decrypt_paste_text)) }
                )
                FilterChip(
                    selected = uiState.inputMode == DecryptInputMode.IMPORT_FILE,
                    onClick = { viewModel.setInputMode(DecryptInputMode.IMPORT_FILE) },
                    label = { Text(stringResource(R.string.decrypt_import_file)) }
                )
            }

            // Input area
            when (uiState.inputMode) {
                DecryptInputMode.PASTE_TEXT -> {
                    OutlinedTextField(
                        value = uiState.base64Input,
                        onValueChange = { viewModel.setBase64Input(it) },
                        label = { Text(stringResource(R.string.decrypt_input_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        maxLines = 10,
                        trailingIcon = {
                            if (uiState.base64Input.isNotEmpty()) {
                                IconButton(onClick = { viewModel.clearInput() }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.decrypt_clear))
                                }
                            }
                        }
                    )
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            viewModel.setBase64Input(text)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(stringResource(R.string.decrypt_paste_clipboard))
                    }
                }
                DecryptInputMode.IMPORT_FILE -> {
                    OutlinedButton(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(stringResource(R.string.decrypt_select_file))
                    }
                    if (uiState.importedFileUri != null) {
                        val selectedName = uiState.importedFileName
                        val selectedLabel = if (selectedName != null) {
                            stringResource(R.string.decrypt_incoming_file, selectedName)
                        } else {
                            stringResource(R.string.decrypt_file_selected)
                        }
                        Text(
                            text = selectedLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { viewModel.clearInput() }) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(stringResource(R.string.decrypt_clear))
                        }
                    }
                }
            }

            // Decrypt button
            Button(
                onClick = { viewModel.decrypt() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isDecrypting && uiState.selectedChat != null
            ) {
                if (uiState.isDecrypting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.decrypt_decrypting))
                } else {
                    Text(stringResource(R.string.decrypt_button))
                }
            }

            // Error message
            uiState.errorMessage?.let { error ->
                if (uiState.result !is DecryptionResult.Error) {
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
            }

            // Result
            when (val result = uiState.result) {
                is DecryptionResult.TextMessage -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.decrypt_result_text),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = result.plaintext,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("decrypted", result.plaintext)
                                        )
                                        Toast.makeText(context, context.getString(R.string.decrypt_copied), Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(stringResource(R.string.decrypt_copy))
                                }
                                OutlinedButton(
                                    onClick = {
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, result.plaintext)
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.decrypt_send)))
                                    }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(stringResource(R.string.decrypt_send))
                                }
                            }
                        }
                    }
                }
                is DecryptionResult.ImageMessage -> {
                    val imageBitmap: ImageBitmap? = remember(result.imageBytes) {
                        try {
                            BitmapFactory.decodeByteArray(result.imageBytes, 0, result.imageBytes.size)
                                ?.asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.decrypt_result_image),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            imageBitmap?.let { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(300.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } ?: Text(
                                stringResource(R.string.decrypt_image_error),
                                color = MaterialTheme.colorScheme.error
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { saveImageToGallery(context, result.imageBytes) }
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(stringResource(R.string.decrypt_save_gallery))
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val (mimeType, extension) = detectImageFormat(result.imageBytes)
                                                val file = viewModel.writeShareFile(
                                                    "decrypted_${System.currentTimeMillis()}.$extension",
                                                    result.imageBytes
                                                )
                                                shareFile(context, file, mimeType)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                showError(context, e)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(stringResource(R.string.decrypt_share))
                                }
                            }
                        }
                    }
                }
                is DecryptionResult.DocumentMessage -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.decrypt_result_document),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = result.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            saveDocumentLauncher.launch(result.fileName)
                                        } catch (e: ActivityNotFoundException) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.decrypt_save_document_unavailable),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(stringResource(R.string.decrypt_save_document))
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val file = viewModel.writeShareFile(result.fileName, result.documentBytes)
                                                shareFile(context, file, "application/octet-stream")
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                showError(context, e)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(stringResource(R.string.decrypt_share))
                                }
                            }
                        }
                    }
                }
                is DecryptionResult.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = result.message,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                null -> {}
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun showError(context: Context, e: Exception) {
    Toast.makeText(context, context.getString(R.string.error, e.message ?: ""), Toast.LENGTH_LONG).show()
}

private fun saveImageToGallery(context: Context, imageBytes: ByteArray) {
    try {
        val (mimeType, extension) = detectImageFormat(imageBytes)
        val fileName = "decrypted_${System.currentTimeMillis()}.$extension"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DarkMessage")
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                out.write(imageBytes)
            }
            Toast.makeText(context, context.getString(R.string.decrypt_saved_gallery), Toast.LENGTH_SHORT).show()
        } ?: Toast.makeText(context, context.getString(R.string.decrypt_error_save), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        showError(context, e)
    }
}

/** Shares a file from the app-private share cache through FileProvider. */
private fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_STREAM, uri)
        type = mimeType
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.decrypt_share)))
}
