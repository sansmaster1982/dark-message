package com.darkmessage.app.ui.screens.decrypt

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmessage.app.R
import com.darkmessage.app.core.files.documentFileName
import com.darkmessage.app.core.files.sanitizeFileName
import com.darkmessage.app.crypto.PayloadCodec
import com.darkmessage.app.crypto.CryptoEngine
import com.darkmessage.app.data.model.Chat
import com.darkmessage.app.data.model.DecryptionResult
import com.darkmessage.app.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class DecryptInputMode { PASTE_TEXT, IMPORT_FILE }

data class DecryptUiState(
    val selectedChat: Chat? = null,
    val inputMode: DecryptInputMode = DecryptInputMode.PASTE_TEXT,
    val base64Input: String = "",
    val importedFileUri: Uri? = null,
    /** Display name of the imported file (from the provider), when known. */
    val importedFileName: String? = null,
    /** Hint shown while a received file/text waits for the user to pick (or create) a chat. */
    @StringRes val incomingHintRes: Int? = null,
    val isDecrypting: Boolean = false,
    val result: DecryptionResult? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class DecryptViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val cryptoEngine: CryptoEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val chats: StateFlow<List<Chat>> = chatRepository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(DecryptUiState())
    val uiState: StateFlow<DecryptUiState> = _uiState.asStateFlow()

    /** Latest chat list from the database; null until the first emission. */
    private var loadedChats: List<Chat>? = null

    /** True while a received file/text waits for the chats to load or for the user to pick one. */
    private var incomingPending = false

    /** Plaintext temp files written for sharing; deleted as soon as the result is cleared. */
    private val tempFiles = mutableListOf<File>()

    private val shareDir: File
        get() = File(context.cacheDir, SHARE_DIR)

    init {
        // Remove plaintext left behind by a previous session (process death, crash, old versions)
        viewModelScope.launch(Dispatchers.IO) { cleanupStaleTempFiles() }

        viewModelScope.launch {
            chatRepository.getAllChats().collect { list -> onChatsLoaded(list) }
        }
    }

    private fun onChatsLoaded(list: List<Chat>) {
        loadedChats = list
        val current = _uiState.value.selectedChat
        // Keep the current selection (refreshed in case it was renamed); with exactly one chat
        // select it automatically so a received file can be decrypted without extra taps.
        val refreshed = current?.let { c -> list.firstOrNull { it.id == c.id } }
        val selected = refreshed ?: if (list.size == 1) list.first() else null
        if (selected != current) {
            _uiState.value = _uiState.value.copy(selectedChat = selected)
        }
        if (incomingPending) {
            handlePendingIncoming()
        }
    }

    fun selectChat(chat: Chat) {
        _uiState.value = _uiState.value.copy(selectedChat = chat, incomingHintRes = null)
        if (incomingPending) {
            handlePendingIncoming()
        }
    }

    fun setInputMode(mode: DecryptInputMode) {
        clearTempFiles()
        incomingPending = false
        _uiState.value = _uiState.value.copy(
            inputMode = mode,
            incomingHintRes = null,
            result = null,
            errorMessage = null
        )
    }

    fun setBase64Input(text: String) {
        _uiState.value = _uiState.value.copy(base64Input = text)
    }

    fun setImportedFileUri(uri: Uri?) {
        clearTempFiles()
        incomingPending = false
        _uiState.value = _uiState.value.copy(
            importedFileUri = uri,
            importedFileName = uri?.let { queryDisplayName(it) },
            incomingHintRes = null,
            result = null,
            errorMessage = null
        )
    }

    fun clearInput() {
        clearTempFiles()
        incomingPending = false
        _uiState.value = _uiState.value.copy(
            base64Input = "",
            importedFileUri = null,
            importedFileName = null,
            incomingHintRes = null,
            result = null,
            errorMessage = null
        )
    }

    fun clearResult() {
        clearTempFiles()
        _uiState.value = _uiState.value.copy(result = null, errorMessage = null)
    }

    // ---------------------------------------------------------------------------------------
    // Incoming file / text from another app
    // ---------------------------------------------------------------------------------------

    /** A .darkm file opened with / shared to the app. */
    fun receiveFile(uri: Uri) {
        clearTempFiles()
        _uiState.value = _uiState.value.copy(
            inputMode = DecryptInputMode.IMPORT_FILE,
            importedFileUri = uri,
            importedFileName = queryDisplayName(uri),
            base64Input = "",
            incomingHintRes = null,
            result = null,
            errorMessage = null
        )
        incomingPending = true
        handlePendingIncoming()
    }

    /** An encrypted Base64 message shared as plain text. */
    fun receiveText(text: String) {
        clearTempFiles()
        _uiState.value = _uiState.value.copy(
            inputMode = DecryptInputMode.PASTE_TEXT,
            base64Input = text.trim(),
            importedFileUri = null,
            importedFileName = null,
            incomingHintRes = null,
            result = null,
            errorMessage = null
        )
        incomingPending = true
        handlePendingIncoming()
    }

    /**
     * Decrypts the pending item as soon as a chat is selected (auto-selected when there is
     * exactly one); otherwise shows a hint asking the user to pick or create a chat.
     * Retried from [onChatsLoaded] and [selectChat] while [incomingPending] is set.
     */
    private fun handlePendingIncoming() {
        val list = loadedChats ?: return // chats not loaded yet; onChatsLoaded() will retry
        val state = _uiState.value
        if (state.selectedChat != null) {
            incomingPending = false
            _uiState.value = state.copy(incomingHintRes = null)
            decrypt()
        } else {
            val hintRes = if (list.isEmpty()) {
                R.string.decrypt_incoming_no_chats
            } else {
                R.string.decrypt_incoming_pick_chat
            }
            _uiState.value = state.copy(incomingHintRes = hintRes)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (e: Exception) {
            // Provider rejected the query or the grant expired; fall back to the path below
        }
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------------------------------
    // Decryption
    // ---------------------------------------------------------------------------------------

    private fun localizeError(code: String): String = when {
        code == "INVALID_FORMAT" -> context.getString(R.string.decrypt_error_format)
        code == "BAD_PASSPHRASE" -> context.getString(R.string.decrypt_error_bad_passphrase)
        code.startsWith("DECRYPT_ERROR:") -> context.getString(R.string.decrypt_error, code.removePrefix("DECRYPT_ERROR:"))
        else -> code
    }

    private fun fail(@StringRes messageRes: Int) {
        _uiState.value = _uiState.value.copy(
            isDecrypting = false,
            errorMessage = context.getString(messageRes)
        )
    }

    private fun readUri(uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    fun decrypt() {
        val state = _uiState.value
        val chat = state.selectedChat ?: return

        viewModelScope.launch {
            clearTempFiles()
            _uiState.value = _uiState.value.copy(
                isDecrypting = true,
                result = null,
                errorMessage = null,
                incomingHintRes = null
            )

            try {
                val passphrase = chatRepository.getPassphrase(chat.id)
                if (passphrase.isNullOrBlank()) {
                    fail(R.string.decrypt_error_no_passphrase)
                    return@launch
                }

                val encryptedBytes: ByteArray = when (state.inputMode) {
                    DecryptInputMode.PASTE_TEXT -> {
                        val text = state.base64Input.trim()
                        if (text.isEmpty()) {
                            fail(R.string.decrypt_error_empty_text)
                            return@launch
                        }
                        // Text that travelled through a messenger or a mail client comes
                        // back wrapped across lines, and some clients hand over the
                        // URL-safe alphabet. Normalise before decoding.
                        val normalized = PayloadCodec.normalizeBase64(text)
                        if (normalized == null) {
                            fail(R.string.decrypt_error_bad_base64)
                            return@launch
                        }
                        try {
                            Base64.decode(normalized, Base64.NO_WRAP)
                        } catch (e: IllegalArgumentException) {
                            fail(R.string.decrypt_error_bad_base64)
                            return@launch
                        }
                    }
                    DecryptInputMode.IMPORT_FILE -> {
                        val uri = state.importedFileUri
                        if (uri == null) {
                            fail(R.string.decrypt_error_no_file)
                            return@launch
                        }
                        val bytes = withContext(Dispatchers.IO) { readUri(uri) }
                        if (bytes == null) {
                            fail(R.string.decrypt_error_read_file)
                            return@launch
                        }
                        bytes
                    }
                }

                val passphraseChars = passphrase.toCharArray()
                val result = cryptoEngine.decrypt(encryptedBytes, passphraseChars)
                passphraseChars.fill('\u0000')

                val finalResult: DecryptionResult = when (result) {
                    is DecryptionResult.Error -> DecryptionResult.Error(localizeError(result.message))
                    // The file name comes from the (remote) sender: never use it as a path as-is
                    is DecryptionResult.DocumentMessage -> DecryptionResult.DocumentMessage(
                        documentBytes = result.documentBytes,
                        fileName = documentFileName(result.fileName, result.documentBytes)
                    )
                    else -> result
                }

                _uiState.value = _uiState.value.copy(
                    isDecrypting = false,
                    result = finalResult,
                    errorMessage = if (finalResult is DecryptionResult.Error) finalResult.message else null
                )

                if (finalResult !is DecryptionResult.Error) {
                    chatRepository.updateLastActivity(chat.id)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDecrypting = false,
                    errorMessage = context.getString(R.string.decrypt_error, e.message ?: "")
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Saving / sharing decrypted content
    // ---------------------------------------------------------------------------------------

    /**
     * Writes the decrypted document to a location chosen by the user through the Storage
     * Access Framework (ActivityResultContracts.CreateDocument). [onDone] receives true on success.
     */
    fun saveDocumentTo(target: Uri, onDone: (Boolean) -> Unit) {
        val document = _uiState.value.result as? DecryptionResult.DocumentMessage
        if (document == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val output = try {
                        resolver.openOutputStream(target, "wt")
                    } catch (e: Exception) {
                        resolver.openOutputStream(target)
                    }
                    if (output == null) {
                        false
                    } else {
                        output.use { stream ->
                            stream.write(document.documentBytes)
                            stream.flush()
                        }
                        true
                    }
                } catch (e: Exception) {
                    false
                }
            }
            onDone(ok)
        }
    }

    /**
     * Writes [bytes] into the app-private cache subdirectory used for FileProvider sharing and
     * remembers the file so it is deleted when the result is cleared or the ViewModel dies.
     * [fileName] is sanitized: it can never leave the share directory.
     */
    suspend fun writeShareFile(fileName: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val dir = shareDir
        dir.mkdirs()
        val file = File(dir, sanitizeFileName(fileName))
        val dirPath = dir.canonicalPath + File.separator
        require(file.canonicalPath.startsWith(dirPath)) { "Invalid file name" }
        file.writeBytes(bytes)
        synchronized(tempFiles) { tempFiles.add(file) }
        file
    }

    private fun clearTempFiles() {
        val files = synchronized(tempFiles) {
            val copy = tempFiles.toList()
            tempFiles.clear()
            copy
        }
        if (files.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (file in files) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    // best effort
                }
            }
        }
    }

    private fun cleanupStaleTempFiles() {
        try {
            shareDir.deleteRecursively()
            // Older versions wrote decrypted_<ts>.* straight into cacheDir
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(LEGACY_TEMP_PREFIX)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // best effort
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled here, so delete synchronously (a few small files)
        synchronized(tempFiles) { tempFiles.clear() }
        try {
            shareDir.deleteRecursively()
        } catch (e: Exception) {
            // best effort
        }
    }

    private companion object {
        const val SHARE_DIR = "share"
        const val LEGACY_TEMP_PREFIX = "decrypted_"
    }
}

/** MIME type derived from the file extension, "application/octet-stream" when unknown. */
internal fun mimeTypeForFileName(fileName: String?): String {
    val name = fileName ?: return DEFAULT_MIME_TYPE
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return DEFAULT_MIME_TYPE
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
}

private const val DEFAULT_MIME_TYPE = "application/octet-stream"

/**
 * Detects the real image format from its magic bytes so the decrypted image is saved/shared with
 * the right extension and MIME type. Returns (mimeType, extension); defaults to JPEG.
 */
internal fun detectImageFormat(bytes: ByteArray): Pair<String, String> {
    fun b(index: Int): Int = bytes[index].toInt() and 0xFF

    if (bytes.size >= 8 && b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47) {
        return "image/png" to "png"
    }
    if (bytes.size >= 3 && b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) {
        return "image/jpeg" to "jpg"
    }
    if (bytes.size >= 12 &&
        b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
        b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50
    ) {
        return "image/webp" to "webp"
    }
    if (bytes.size >= 6 && b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38) {
        return "image/gif" to "gif"
    }

    // Anything else (HEIC, BMP, ...): let the platform decoder sniff the header
    val sniffed = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.outMimeType
    } catch (e: Exception) {
        null
    }
    if (sniffed != null) {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(sniffed)
        if (extension != null) return sniffed to extension
    }
    return "image/jpeg" to "jpg"
}
