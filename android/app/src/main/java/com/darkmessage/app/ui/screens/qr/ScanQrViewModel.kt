package com.darkmessage.app.ui.screens.qr

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmessage.app.R
import com.darkmessage.app.crypto.ChatQrCodec
import com.darkmessage.app.crypto.ParsedQr
import com.darkmessage.app.crypto.QrOpenResult
import com.darkmessage.app.crypto.QrParseResult
import com.darkmessage.app.data.model.Chat
import com.darkmessage.app.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Wrong PINs allowed per scanned code before the payload is discarded. */
private const val MAX_PIN_ATTEMPTS = 5

/** How long an error banner stays on screen while scanning continues. */
private const val BANNER_VISIBLE_MS = 1_500L

/** The same rejected code is ignored for this long, so one bad QR is reported once. */
private const val DUPLICATE_IGNORE_MS = 2_000L

/** Digits in the PIN (spec 1.3). */
internal const val PIN_LENGTH = 6

/** Receiver flow: scan a code, enter the PIN, confirm the chat. */
sealed interface ScanQrStep {
    data object Scanning : ScanQrStep

    data class PinEntry(
        val attemptsLeft: Int = MAX_PIN_ATTEMPTS,
        /** True while PBKDF2 runs (about 1 s), off the main thread. */
        val isChecking: Boolean = false,
        val wrongPin: Boolean = false,
        /** Increments on every wrong PIN to re-trigger the shake animation. */
        val shakeKey: Int = 0
    ) : ScanQrStep

    data class Confirm(
        /** Name to prefill: the received name, or "Name (2)" when it collides. */
        val suggestedName: String,
        /** Name exactly as received (null when the sender left it empty). */
        val receivedName: String?,
        val passphrase: String,
        val fingerprint: String,
        /** Existing chat with the same name, offered for "Update key of existing chat". */
        val conflictChat: Chat?,
        /** Existing chat that already stores this passphrase. */
        val sameKeyChat: Chat?
    ) : ScanQrStep
}

/** Snackbar text handed back to the Chats screen after a successful add/update. */
data class ScanQrResult(
    @StringRes val textRes: Int,
    val chatName: String
)

data class ScanQrUiState(
    val step: ScanQrStep = ScanQrStep.Scanning,
    @StringRes val bannerRes: Int? = null,
    val torchOn: Boolean = false,
    val isSaving: Boolean = false,
    val finished: ScanQrResult? = null,
    /**
     * Re-arms the scanner: QrScannerView accepts exactly one code per value of its restartKey,
     * so this counter is bumped whenever scanning has to continue after a rejected code.
     */
    val scanAttempt: Int = 0
)

/**
 * Receiver side of the QR chat key exchange (spec 3.4).
 *
 * Scanned content that does not parse is never displayed, opened or logged - only a fixed
 * localized banner is shown and scanning continues. Nothing is written to the database until
 * the user confirms on the Confirm step.
 */
@HiltViewModel
class ScanQrViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatQrCodec: ChatQrCodec
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanQrUiState())
    val uiState: StateFlow<ScanQrUiState> = _uiState.asStateFlow()

    /** Parsed payload of the current code; dropped whenever we go back to scanning. */
    private var parsed: ParsedQr? = null

    private var bannerJob: Job? = null
    private var lastRejectedText: String? = null
    private var lastRejectedAt: Long = 0L

    /**
     * Called for every decoded QR string (camera, photo import or deep link). The PIN is not
     * known yet, so this only parses the envelope.
     */
    fun onCodeScanned(text: String) {
        if (_uiState.value.step !is ScanQrStep.Scanning) return
        val now = System.currentTimeMillis()
        if (text == lastRejectedText && now - lastRejectedAt < DUPLICATE_IGNORE_MS) return

        when (val result = chatQrCodec.parse(text)) {
            is QrParseResult.Ok -> {
                parsed = result.parsed
                lastRejectedText = null
                bannerJob?.cancel()
                bannerJob = null
                _uiState.value = _uiState.value.copy(
                    step = ScanQrStep.PinEntry(),
                    bannerRes = null
                )
            }

            is QrParseResult.NotDarkMessage -> reject(text, R.string.qr_error_not_darkmessage)
            is QrParseResult.UnsupportedVersion -> reject(text, R.string.qr_error_version)
            is QrParseResult.Malformed -> reject(text, R.string.qr_error_malformed)
        }
    }

    /** The picked photo contained no QR code at all. */
    fun onNoQrInPhoto() {
        showBanner(R.string.qr_error_no_qr_in_photo)
    }

    fun toggleTorch() {
        _uiState.value = _uiState.value.copy(torchOn = !_uiState.value.torchOn)
    }

    /** Runs PBKDF2 and AES-GCM for the entered PIN; a wrong PIN costs one attempt. */
    fun submitPin(pin: String) {
        val step = _uiState.value.step
        if (step !is ScanQrStep.PinEntry || step.isChecking) return
        if (pin.length != PIN_LENGTH || pin.any { it < '0' || it > '9' }) return
        val current = parsed
        if (current == null) {
            backToScanning()
            return
        }

        _uiState.value = _uiState.value.copy(
            step = step.copy(isChecking = true, wrongPin = false),
            bannerRes = null
        )

        viewModelScope.launch {
            val result = try {
                chatQrCodec.open(current, pin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            when {
                result is QrOpenResult.Ok -> {
                    parsed = null
                    _uiState.value = _uiState.value.copy(
                        step = buildConfirm(result.name, result.passphrase)
                    )
                }

                result is QrOpenResult.WrongPin -> {
                    val left = step.attemptsLeft - 1
                    if (left <= 0) {
                        parsed = null
                        _uiState.value = _uiState.value.copy(
                            step = ScanQrStep.Scanning,
                            scanAttempt = _uiState.value.scanAttempt + 1
                        )
                        showBanner(R.string.qr_pin_locked)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            step = ScanQrStep.PinEntry(
                                attemptsLeft = left,
                                isChecking = false,
                                wrongPin = true,
                                shakeKey = step.shakeKey + 1
                            )
                        )
                    }
                }

                else -> {
                    // Malformed plaintext (or an unexpected failure): the code is unusable.
                    parsed = null
                    _uiState.value = _uiState.value.copy(
                        step = ScanQrStep.Scanning,
                        scanAttempt = _uiState.value.scanAttempt + 1
                    )
                    showBanner(R.string.qr_error_malformed)
                }
            }
        }
    }

    /** Back from the PIN step: the payload is dropped, the code has to be scanned again. */
    fun backToScanning() {
        parsed = null
        bannerJob?.cancel()
        bannerJob = null
        _uiState.value = _uiState.value.copy(
            step = ScanQrStep.Scanning,
            bannerRes = null,
            scanAttempt = _uiState.value.scanAttempt + 1
        )
    }

    fun addChat(name: String) {
        val step = _uiState.value.step
        if (step !is ScanQrStep.Confirm || _uiState.value.isSaving) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.addChat(trimmed, step.passphrase)
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                finished = ScanQrResult(R.string.qr_added, trimmed)
            )
        }
    }

    /** Replaces the passphrase of the name-conflicting chat (confirmed by a dialog first). */
    fun updateExistingChat() {
        val step = _uiState.value.step
        if (step !is ScanQrStep.Confirm || _uiState.value.isSaving) return
        val existing = step.conflictChat ?: return
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.updateChat(existing, newPassphrase = step.passphrase)
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                finished = ScanQrResult(R.string.qr_updated, existing.name)
            )
        }
    }

    override fun onCleared() {
        bannerJob?.cancel()
        bannerJob = null
        parsed = null
        _uiState.value = ScanQrUiState()
        super.onCleared()
    }

    private suspend fun buildConfirm(name: String?, passphrase: String): ScanQrStep.Confirm {
        val chats = chatRepository.getAllChats().first()
        val received = name?.trim().orEmpty()
        val conflict = if (received.isEmpty()) {
            null
        } else {
            chats.firstOrNull { it.name.trim().equals(received, ignoreCase = true) }
        }
        val sameKey = chats.firstOrNull { chatRepository.getPassphrase(it.id) == passphrase }
        return ScanQrStep.Confirm(
            suggestedName = suggestName(received, chats),
            receivedName = received.ifEmpty { null },
            passphrase = passphrase,
            fingerprint = chatQrCodec.fingerprint(passphrase),
            conflictChat = conflict,
            sameKeyChat = sameKey
        )
    }

    /** "Anna" -> "Anna (2)" (first free suffix) when the name is already taken. */
    private fun suggestName(received: String, chats: List<Chat>): String {
        if (received.isEmpty()) return ""
        val taken = { candidate: String ->
            chats.any { it.name.trim().equals(candidate, ignoreCase = true) }
        }
        if (!taken(received)) return received
        var index = 2
        while (taken("$received ($index)")) index++
        return "$received ($index)"
    }

    private fun reject(text: String, @StringRes bannerRes: Int) {
        lastRejectedText = text
        lastRejectedAt = System.currentTimeMillis()
        // The scanner stopped at this code: re-arm it so scanning continues behind the banner.
        _uiState.value = _uiState.value.copy(scanAttempt = _uiState.value.scanAttempt + 1)
        showBanner(bannerRes)
    }

    private fun showBanner(@StringRes bannerRes: Int) {
        bannerJob?.cancel()
        _uiState.value = _uiState.value.copy(bannerRes = bannerRes)
        bannerJob = viewModelScope.launch {
            delay(BANNER_VISIBLE_MS)
            _uiState.value = _uiState.value.copy(bannerRes = null)
        }
    }
}
