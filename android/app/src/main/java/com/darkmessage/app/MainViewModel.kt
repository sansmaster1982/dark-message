package com.darkmessage.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Something another app handed to Dark Message: a .darkm file opened via "Open with" / share
 * sheet, an encrypted Base64 message shared as plain text, or a darkmessage:// chat-key QR
 * link opened from a system camera / scanner app.
 */
sealed interface Incoming {
    data class File(val uri: Uri) : Incoming
    data class Text(val text: String) : Incoming

    /**
     * A "darkmessage://chat/1/BODY" deep link. [text] is the RAW url string exactly as it was
     * received: the payload is a path segment, so it must never be taken apart with URI query
     * parsing. It goes to the QR scan/confirm flow, never to Decrypt, and it is only ever
     * handed to the QR codec parser - never displayed or logged.
     */
    data class ChatQr(val text: String) : Incoming
}

/**
 * Activity-scoped holder for the pending [Incoming] item.
 *
 * The item survives configuration changes (rotation) and is consumed exactly once - by the
 * Decrypt screen for a file/text, by the QR scan screen for a [Incoming.ChatQr] link - so a
 * received item is never re-imported after a rotation and a new intent delivered through
 * onNewIntent() replaces the previous one.
 */
@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    private val _incoming = MutableStateFlow<Incoming?>(null)
    val incoming: StateFlow<Incoming?> = _incoming.asStateFlow()

    /** Stores a new pending item; a null argument is ignored so a plain launch never clears it. */
    fun offer(item: Incoming?) {
        if (item != null) {
            _incoming.value = item
        }
    }

    /** Marks the pending item as handled. */
    fun consume() {
        _incoming.value = null
    }
}
