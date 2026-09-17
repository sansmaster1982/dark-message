package com.darkmessage.app.ui.screens.chats

import android.util.Base64
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmessage.app.R
import com.darkmessage.app.crypto.ChatQrCodec
import com.darkmessage.app.data.model.Chat
import com.darkmessage.app.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

data class ChatsUiState(
    val dialogState: ChatDialogState = ChatDialogState.None,
    /** Set when a chat could not be created because its key could not be stored. */
    @StringRes val errorRes: Int? = null
)

sealed class ChatDialogState {
    data object None : ChatDialogState()
    data object AddChat : ChatDialogState()
    data class EditChat(val chat: Chat, val currentPassphrase: String = "") : ChatDialogState()
    data class DeleteChat(val chat: Chat) : ChatDialogState()
}

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatQrCodec: ChatQrCodec
) : ViewModel() {

    val chats: StateFlow<List<Chat>> = chatRepository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(ChatsUiState())
    val uiState: StateFlow<ChatsUiState> = _uiState.asStateFlow()

    /// Key fingerprint per chat id: the first four bytes of SHA-256 over the passphrase,
    /// rendered "XXXX XXXX". It reveals nothing about the key but lets two people confirm
    /// that their keys match, which is how a "it does not decrypt" report gets diagnosed.
    private val _fingerprints = MutableStateFlow<Map<Long, String>>(emptyMap())
    val fingerprints: StateFlow<Map<Long, String>> = _fingerprints.asStateFlow()

    init {
        viewModelScope.launch {
            chats.collect { list ->
                _fingerprints.value = list.mapNotNull { chat ->
                    chatRepository.getPassphrase(chat.id)
                        ?.let { chat.id to chatQrCodec.fingerprint(it) }
                }.toMap()
            }
        }
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(dialogState = ChatDialogState.AddChat)
    }

    fun showEditDialog(chat: Chat) {
        // The stored passphrase is deliberately NOT loaded: it must never appear on screen
        // without an identity check, and iOS behaves the same way. An empty field means
        // "keep the current key" (see updateChat below).
        _uiState.value = _uiState.value.copy(
            dialogState = ChatDialogState.EditChat(chat, "")
        )
    }

    fun showDeleteDialog(chat: Chat) {
        _uiState.value = _uiState.value.copy(dialogState = ChatDialogState.DeleteChat(chat))
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(dialogState = ChatDialogState.None)
    }

    fun addChat(name: String, passphrase: String) {
        if (name.isBlank() || passphrase.isBlank()) return
        viewModelScope.launch {
            // Storing the key can fail - encrypted storage on a device in a bad state. It
            // used to take the whole app down from here, and before that it left a chat in
            // the list that could never decrypt anything. Neither is acceptable: say so and
            // leave nothing behind. iOS refuses the same way.
            runCatching { chatRepository.addChat(name.trim(), passphrase) }
                .onSuccess { dismissDialog() }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        dialogState = ChatDialogState.None,
                        errorRes = R.string.chats_error_key_not_saved
                    )
                }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorRes = null)
    }

    fun updateChat(chat: Chat, newName: String, newPassphrase: String?) {
        viewModelScope.launch {
            chatRepository.updateChat(
                chat.copy(name = newName.trim()),
                newPassphrase?.takeIf { it.isNotBlank() }
            )
            dismissDialog()
        }
    }

    fun deleteChat(chat: Chat) {
        viewModelScope.launch {
            chatRepository.deleteChat(chat)
            dismissDialog()
        }
    }

    companion object {
        fun generateKey(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        }
    }
}
