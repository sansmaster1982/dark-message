package com.darkmessage.app.ui.screens.qr

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmessage.app.R
import com.darkmessage.app.crypto.ChatQrCodec
import com.darkmessage.app.crypto.ChatQrEncodeException
import com.darkmessage.app.crypto.QrEncodeError
import com.darkmessage.app.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Seconds the QR code stays on screen before it is hidden (spec 3.1 / 3.2). */
private const val CODE_VISIBLE_SECONDS = 60

/** Seconds the PIN stays on screen before it is hidden. */
private const val PIN_VISIBLE_SECONDS = 20

/** A code (salt/nonce/PIN) may be shown for at most 5 minutes, then it expires for good. */
private const val SESSION_LIFETIME_MS = 5 * 60 * 1000L

/** Payload limit of the QR format: a longer passphrase cannot be shared this way. */
private const val MAX_PASSPHRASE_BYTES = 128

/** Payload limit for the sender display name (UTF-8 bytes); the input field enforces it. */
internal const val MAX_SENDER_NAME_BYTES = 64

/**
 * What the sender screen currently shows. The QR code and the PIN are NEVER rendered at the
 * same time: [Code] and [Pin] replace each other, so a bystander who photographs the screen
 * gets only one of the two halves.
 */
sealed interface ShowQrPhase {
    /** Name field + warning + "Show code". Nothing sensitive has been rendered yet. */
    data object Intro : ShowQrPhase

    data class Code(
        val text: String,
        val fingerprint: String,
        val secondsLeft: Int
    ) : ShowQrPhase

    data class Pin(
        val pin: String,
        val fingerprint: String,
        val secondsLeft: Int
    ) : ShowQrPhase

    /** Code/PIN taken off the screen; [canShowAgain] is false once the session is burned. */
    data class Hidden(
        @StringRes val reasonRes: Int,
        val canShowAgain: Boolean
    ) : ShowQrPhase

    /** The session is older than the 5 minute limit: only "New code" is left. */
    data object Expired : ShowQrPhase
}

data class ShowQrUiState(
    val chatName: String = "",
    /** True while PBKDF2 runs (about 1 s) for a new code. */
    val isPreparing: Boolean = false,
    @StringRes val errorRes: Int? = null,
    /** Format argument for [errorRes] when that string takes one. */
    val errorArg: String? = null,
    val phase: ShowQrPhase = ShowQrPhase.Intro
)

/**
 * Sender side of the QR chat key exchange (spec 3.2).
 *
 * The encoded payload lives only in [session] - in memory, for at most 5 minutes, never
 * written anywhere and never logged. There is deliberately no copy / share / save action.
 */
@HiltViewModel
class ShowQrViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val chatQrCodec: ChatQrCodec
) : ViewModel() {

    /**
     * Salt and nonce are generated inside [ChatQrCodec.encode]; the session keeps only what the
     * UI has to be able to show again while the code is alive.
     */
    private data class QrSession(
        val pin: String,
        val text: String,
        val fingerprint: String,
        val createdAt: Long
    )

    private val chatId: Long = savedStateHandle.get<Long>("chatId") ?: -1L

    private val _uiState = MutableStateFlow(ShowQrUiState())
    val uiState: StateFlow<ShowQrUiState> = _uiState.asStateFlow()

    private var session: QrSession? = null

    /** Set when a capture was detected: the code is cancelled, only "New code" remains. */
    private var burned: Boolean = false

    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            val chat = chatRepository.getChatById(chatId)
            if (chat != null) {
                _uiState.value = _uiState.value.copy(chatName = chat.name)
            }
        }
    }

    /**
     * Creates a fresh code (new salt, nonce and PIN) and shows it. Used by both "Show code"
     * and "New code"; the previous PIN never opens the new code.
     */
    fun showCode(senderName: String) {
        if (_uiState.value.isPreparing) return
        countdownJob?.cancel()
        countdownJob = null
        session = null
        burned = false
        _uiState.value = _uiState.value.copy(
            isPreparing = true,
            errorRes = null,
            errorArg = null,
            phase = ShowQrPhase.Intro
        )
        viewModelScope.launch {
            val passphrase = chatRepository.getPassphrase(chatId)
            if (passphrase.isNullOrEmpty()) {
                fail(R.string.encrypt_error_no_passphrase)
                return@launch
            }
            if (passphrase.toByteArray(Charsets.UTF_8).size > MAX_PASSPHRASE_BYTES) {
                fail(R.string.qr_error_passphrase_too_long)
                return@launch
            }
            val pin = chatQrCodec.generatePin()
            val text = try {
                chatQrCodec.encode(
                    name = senderName.trim(),
                    passphrase = passphrase,
                    pin = pin
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatQrEncodeException) {
                when (e.error) {
                    QrEncodeError.NO_PASSPHRASE ->
                        fail(R.string.encrypt_error_no_passphrase)
                    QrEncodeError.PASSPHRASE_TOO_LONG ->
                        fail(R.string.qr_error_passphrase_too_long)
                    // Guarded by the UI (name capped at 64 bytes, generated 6-digit PIN).
                    else -> fail(R.string.error, e.error.name)
                }
                return@launch
            } catch (e: Exception) {
                // Never put the payload, the PIN or the passphrase into a message.
                fail(R.string.error, e.javaClass.simpleName)
                return@launch
            }
            session = QrSession(
                pin = pin,
                text = text,
                fingerprint = chatQrCodec.fingerprint(passphrase),
                createdAt = SystemClock.elapsedRealtime()
            )
            _uiState.value = _uiState.value.copy(isPreparing = false)
            enterCode()
        }
    }

    /** Re-shows the current code after an auto-hide or a return from the background. */
    fun showAgain() {
        if (burned) return
        enterCode()
    }

    fun showPin() {
        val current = session
        if (current == null) {
            backToIntro()
            return
        }
        if (isExpired(current)) {
            expire()
            return
        }
        _uiState.value = _uiState.value.copy(
            phase = ShowQrPhase.Pin(current.pin, current.fingerprint, PIN_VISIBLE_SECONDS)
        )
        startCountdown(PIN_VISIBLE_SECONDS)
    }

    fun backToCode() {
        enterCode()
    }

    /** Hides the code as soon as the app is no longer in the foreground. */
    fun onAppBackgrounded() {
        val phase = _uiState.value.phase
        if (phase is ShowQrPhase.Code || phase is ShowQrPhase.Pin) {
            hide(R.string.qr_hidden, canShowAgain = true)
        }
    }

    /**
     * A screenshot / screen capture was detected (Android 14+). FLAG_SECURE normally prevents
     * it; if it happened anyway the code is burned and a new one has to be created.
     */
    fun onScreenCaptured() {
        val phase = _uiState.value.phase
        if (phase is ShowQrPhase.Code || phase is ShowQrPhase.Pin) {
            burned = true
            session = null
            hide(R.string.qr_hidden_screenshot, canShowAgain = false)
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        countdownJob = null
        session = null
        super.onCleared()
    }

    private fun enterCode() {
        val current = session
        if (current == null) {
            backToIntro()
            return
        }
        if (isExpired(current)) {
            expire()
            return
        }
        _uiState.value = _uiState.value.copy(
            phase = ShowQrPhase.Code(current.text, current.fingerprint, CODE_VISIBLE_SECONDS)
        )
        startCountdown(CODE_VISIBLE_SECONDS)
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var left = seconds
            while (left > 0) {
                delay(1_000)
                val current = session ?: return@launch
                if (isExpired(current)) {
                    expire()
                    return@launch
                }
                left -= 1
                val phase = when (val p = _uiState.value.phase) {
                    is ShowQrPhase.Code -> p.copy(secondsLeft = left)
                    is ShowQrPhase.Pin -> p.copy(secondsLeft = left)
                    else -> return@launch
                }
                _uiState.value = _uiState.value.copy(phase = phase)
            }
            hide(R.string.qr_hidden, canShowAgain = true)
        }
    }

    private fun hide(@StringRes reasonRes: Int, canShowAgain: Boolean) {
        countdownJob?.cancel()
        countdownJob = null
        _uiState.value = _uiState.value.copy(
            phase = ShowQrPhase.Hidden(reasonRes, canShowAgain && !burned && session != null)
        )
    }

    private fun expire() {
        countdownJob?.cancel()
        countdownJob = null
        session = null
        _uiState.value = _uiState.value.copy(phase = ShowQrPhase.Expired)
    }

    private fun backToIntro() {
        countdownJob?.cancel()
        countdownJob = null
        _uiState.value = _uiState.value.copy(phase = ShowQrPhase.Intro)
    }

    private fun fail(@StringRes errorRes: Int, arg: String? = null) {
        session = null
        _uiState.value = _uiState.value.copy(
            isPreparing = false,
            errorRes = errorRes,
            errorArg = arg,
            phase = ShowQrPhase.Intro
        )
    }

    private fun isExpired(current: QrSession): Boolean =
        SystemClock.elapsedRealtime() - current.createdAt >= SESSION_LIFETIME_MS
}
