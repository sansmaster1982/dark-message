package com.darkmessage.app.ui.screens.encrypt

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmessage.app.R
import com.darkmessage.app.core.constants.AppConstants
import com.darkmessage.app.core.files.documentFileName
import com.darkmessage.app.core.files.hasUsableExtension
import com.darkmessage.app.core.files.sanitizeFileName
import com.darkmessage.app.crypto.CryptoEngine
import com.darkmessage.app.data.model.Chat
import com.darkmessage.app.data.model.ContentType
import com.darkmessage.app.data.model.EncryptionResult
import com.darkmessage.app.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class InputMode { TEXT, IMAGE, DOCUMENT }

data class EncryptUiState(
    val selectedChat: Chat? = null,
    val inputMode: InputMode = InputMode.TEXT,
    val textInput: String = "",
    val imageUri: Uri? = null,
    val documentUri: Uri? = null,
    val documentName: String? = null,
    val isEncrypting: Boolean = false,
    val result: EncryptionResult? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class EncryptViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val cryptoEngine: CryptoEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val chats: StateFlow<List<Chat>> = chatRepository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(EncryptUiState())
    val uiState: StateFlow<EncryptUiState> = _uiState.asStateFlow()

    fun selectChat(chat: Chat) {
        _uiState.value = _uiState.value.copy(selectedChat = chat)
    }

    fun setInputMode(mode: InputMode) {
        _uiState.value = _uiState.value.copy(
            inputMode = mode,
            result = null,
            errorMessage = null
        )
    }

    fun setTextInput(text: String) {
        _uiState.value = _uiState.value.copy(textInput = text)
    }

    fun setImageUri(uri: Uri?) {
        _uiState.value = _uiState.value.copy(imageUri = uri)
    }

    fun setDocumentUri(uri: Uri?) {
        val name = uri?.let { getFileName(it) }
        _uiState.value = _uiState.value.copy(documentUri = uri, documentName = name)
    }

    fun clearInput() {
        _uiState.value = _uiState.value.copy(
            textInput = "",
            imageUri = null,
            documentUri = null,
            documentName = null,
            result = null,
            errorMessage = null
        )
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(result = null, errorMessage = null)
    }

    /**
     * The name of the file the user picked.
     *
     * DISPLAY_NAME is the right answer and almost always there, but not always: a file:// URI has
     * no provider to ask (query returns null), and some providers - cloud storage and a few
     * messengers - return a row without that column, or a blank value in it. When that happened the
     * name became the literal string "document", which is what the owner saw arrive on his iPhone:
     * a file that decrypts but is called "document" and therefore opens nowhere.
     *
     * So this falls back, in order, to the last path segment of the URI and then to the extension
     * the provider's MIME type implies. A query that throws (a grant that lapsed, a provider that
     * misbehaves) must not take the whole encryption down with it.
     */
    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        if (!name.isNullOrBlank()) return name

        // A file:// URI, or a provider with no DISPLAY_NAME: the last path segment is usually the
        // file name for the first and a meaningless id for the second, so it only counts when it
        // carries an extension the system knows.
        val segment = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        if (!segment.isNullOrBlank() && hasUsableExtension(segment)) return sanitizeFileName(segment)

        // Nothing named it. At least say what kind of file it is, so the other phone can open it.
        val extension = runCatching {
            context.contentResolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        }.getOrNull()
        return if (extension.isNullOrBlank()) null else "document.$extension"
    }

    fun encrypt() {
        val state = _uiState.value
        val chat = state.selectedChat ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isEncrypting = true, result = null, errorMessage = null)

            try {
                val passphrase = chatRepository.getPassphrase(chat.id)
                if (passphrase.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isEncrypting = false,
                        errorMessage = context.getString(R.string.encrypt_error_no_passphrase)
                    )
                    return@launch
                }

                val passphraseChars = passphrase.toCharArray()

                // Step 1: Get plaintext bytes based on input mode
                val plaintextBytes: ByteArray
                val contentType: ContentType

                when (state.inputMode) {
                    InputMode.TEXT -> {
                        if (state.textInput.isBlank()) {
                            _uiState.value = _uiState.value.copy(
                                isEncrypting = false,
                                errorMessage = context.getString(R.string.encrypt_error_empty_text)
                            )
                            return@launch
                        }
                        plaintextBytes = state.textInput.toByteArray(Charsets.UTF_8)
                        contentType = ContentType.TEXT
                    }
                    InputMode.IMAGE -> {
                        val uri = state.imageUri
                        if (uri == null) {
                            _uiState.value = _uiState.value.copy(
                                isEncrypting = false,
                                errorMessage = context.getString(R.string.encrypt_error_no_image)
                            )
                            return@launch
                        }
                        val imageBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (imageBytes == null) {
                            _uiState.value = _uiState.value.copy(
                                isEncrypting = false,
                                errorMessage = context.getString(R.string.encrypt_error_read_image)
                            )
                            return@launch
                        }
                        plaintextBytes = imageBytes
                        contentType = ContentType.IMAGE
                    }
                    InputMode.DOCUMENT -> {
                        val uri = state.documentUri
                        if (uri == null) {
                            _uiState.value = _uiState.value.copy(
                                isEncrypting = false,
                                errorMessage = context.getString(R.string.encrypt_error_no_document)
                            )
                            return@launch
                        }
                        val docBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (docBytes == null) {
                            _uiState.value = _uiState.value.copy(
                                isEncrypting = false,
                                errorMessage = context.getString(R.string.encrypt_error_read_document)
                            )
                            return@launch
                        }
                        // Last line of defence: whatever the picker gave us, the name that goes
                        // into the payload carries an extension if the content can name one. The
                        // receiving phone decides what to do with a file from its extension alone.
                        val name = documentFileName(state.documentName, docBytes)
                        val nameBytes = name.toByteArray(Charsets.UTF_8)
                        val nameLen = minOf(nameBytes.size, 65535)
                        val combined = ByteArray(2 + nameLen + docBytes.size)
                        combined[0] = ((nameLen shr 8) and 0xFF).toByte()
                        combined[1] = (nameLen and 0xFF).toByte()
                        System.arraycopy(nameBytes, 0, combined, 2, nameLen)
                        System.arraycopy(docBytes, 0, combined, 2 + nameLen, docBytes.size)
                        plaintextBytes = combined
                        contentType = ContentType.DOCUMENT
                    }
                }

                // Step 2: Encrypt
                val encrypted = cryptoEngine.encrypt(plaintextBytes, passphraseChars, contentType)
                passphraseChars.fill('\u0000')

                // Step 3: Output
                when (state.inputMode) {
                    InputMode.TEXT -> {
                        val base64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
                        _uiState.value = _uiState.value.copy(
                            isEncrypting = false,
                            result = EncryptionResult.Success(base64Text = base64)
                        )
                    }
                    InputMode.IMAGE -> {
                        if (plaintextBytes.size <= AppConstants.MAX_CLIPBOARD_PLAINTEXT_SIZE) {
                            val base64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
                            _uiState.value = _uiState.value.copy(
                                isEncrypting = false,
                                result = EncryptionResult.Success(base64Text = base64, rawBytes = encrypted)
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isEncrypting = false,
                                result = EncryptionResult.Success(rawBytes = encrypted)
                            )
                        }
                    }
                    InputMode.DOCUMENT -> {
                        _uiState.value = _uiState.value.copy(
                            isEncrypting = false,
                            result = EncryptionResult.Success(rawBytes = encrypted)
                        )
                    }
                }

                chatRepository.updateLastActivity(chat.id)

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isEncrypting = false,
                    errorMessage = context.getString(R.string.encrypt_error, e.message ?: "")
                )
            }
        }
    }
}
