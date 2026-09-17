package com.darkmessage.app.ui.screens.qr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.darkmessage.app.R
import com.darkmessage.app.ui.qr.QrCodeImage
import com.darkmessage.app.ui.qr.SecureWindow
import com.darkmessage.app.ui.screens.settings.SettingsViewModel

/**
 * Sender screen of the QR chat key exchange (spec 3.2), shown as a full screen route
 * ("show_qr/{chatId}", bottom bar hidden).
 *
 * Security properties implemented here:
 * - [SecureWindow] keeps FLAG_SECURE on for the whole route (no screenshot, no recording, no
 *   recents thumbnail);
 * - the QR code and the PIN are never on screen together, each with its own countdown;
 * - ON_PAUSE hides whatever is visible, a detected capture burns the code;
 * - there is no copy, share or save-image control anywhere on this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowQrScreen(
    onDone: () -> Unit,
    viewModel: ShowQrViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    SecureWindow()

    val uiState by viewModel.uiState.collectAsState()
    val storedName by settingsViewModel.qrSenderName.collectAsState()

    // null until the user edits the field, so the persisted name still appears once the
    // settings DataStore has loaded.
    var typedName by rememberSaveable { mutableStateOf<String?>(null) }
    val senderName = typedName ?: storedName

    val context = LocalContext.current
    val activity = remember(context) { context.findQrActivity() }

    // ON_PAUSE also fires when the Activity is only being recreated for a configuration change
    // (rotation, theme or font-scale change). Hiding then would blank the code mid-handoff while
    // the contact is aiming their camera at it, so only a real move to the background hides.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (activity?.isChangingConfigurations != true) viewModel.onAppBackgrounded()
    }

    DisposableEffect(activity) {
        var unregister: (() -> Unit)? = null
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unregister = registerCaptureCallback(activity) { viewModel.onScreenCaptured() }
        }
        onDispose { unregister?.invoke() }
    }

    val startNewCode: () -> Unit = {
        settingsViewModel.setQrSenderName(senderName.trim())
        viewModel.showCode(senderName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qr_share_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.qr_done)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val phase = uiState.phase) {
                is ShowQrPhase.Intro -> IntroContent(
                    chatName = uiState.chatName,
                    senderName = senderName,
                    isPreparing = uiState.isPreparing,
                    errorText = uiState.errorRes?.let { res ->
                        val arg = uiState.errorArg
                        if (arg != null) stringResource(res, arg) else stringResource(res)
                    },
                    onSenderNameChange = { typedName = sanitizeSenderName(it) },
                    onShowCode = startNewCode
                )

                is ShowQrPhase.Code -> CodeContent(
                    phase = phase,
                    onShowPin = { viewModel.showPin() },
                    onNewCode = startNewCode,
                    onDone = onDone
                )

                is ShowQrPhase.Pin -> PinContent(
                    phase = phase,
                    onBackToCode = { viewModel.backToCode() },
                    onNewCode = startNewCode,
                    onDone = onDone
                )

                is ShowQrPhase.Hidden -> HiddenContent(
                    phase = phase,
                    onShowAgain = { viewModel.showAgain() },
                    onNewCode = startNewCode,
                    onDone = onDone
                )

                is ShowQrPhase.Expired -> PlaceholderContent(
                    message = stringResource(R.string.qr_expired),
                    onNewCode = startNewCode,
                    onDone = onDone
                )
            }
        }
    }
}

@Composable
private fun IntroContent(
    chatName: String,
    senderName: String,
    isPreparing: Boolean,
    errorText: String?,
    onSenderNameChange: (String) -> Unit,
    onShowCode: () -> Unit
) {
    if (chatName.isNotEmpty()) {
        Text(
            text = chatName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    val nameBytes = senderName.toByteArray(Charsets.UTF_8).size
    OutlinedTextField(
        value = senderName,
        onValueChange = onSenderNameChange,
        label = { Text(stringResource(R.string.qr_sender_name)) },
        singleLine = true,
        enabled = !isPreparing,
        supportingText = {
            Text(
                stringResource(R.string.qr_sender_name_hint) +
                    "  " + nameBytes + "/" + MAX_SENDER_NAME_BYTES
            )
        },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(16.dp))

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.qr_show_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }

    if (errorText != null) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = errorText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    if (isPreparing) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
    } else {
        Button(onClick = onShowCode, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.qr_show_button))
        }
    }
}

@Composable
private fun CodeContent(
    phase: ShowQrPhase.Code,
    onShowPin: () -> Unit,
    onNewCode: () -> Unit,
    onDone: () -> Unit
) {
    // Black on white, independent of the app theme, so cheap cameras can read it.
    QrCodeImage(phase.text)

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.qr_fingerprint, phase.fingerprint),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center
    )
    Text(
        text = stringResource(R.string.qr_fingerprint_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = stringResource(R.string.qr_code_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(R.string.qr_hides_in, phase.secondsLeft),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(20.dp))

    Button(onClick = onShowPin, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.qr_show_pin))
    }
    Spacer(modifier = Modifier.height(8.dp))
    SecondaryActions(onNewCode = onNewCode, onDone = onDone)
}

@Composable
private fun PinContent(
    phase: ShowQrPhase.Pin,
    onBackToCode: () -> Unit,
    onNewCode: () -> Unit,
    onDone: () -> Unit
) {
    Text(
        text = stringResource(R.string.qr_pin_label),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))

    val grouped = phase.pin.take(3) + " " + phase.pin.drop(3)
    val spokenPin = stringResource(R.string.qr_pin_label) + " " + phase.pin.toCharArray().joinToString(" ")
    Text(
        text = grouped,
        fontFamily = FontFamily.Monospace,
        fontSize = 44.sp,
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { contentDescription = spokenPin }
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.qr_pin_tell_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(R.string.qr_fingerprint, phase.fingerprint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = stringResource(R.string.qr_pin_hides_in, phase.secondsLeft),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(20.dp))

    Button(onClick = onBackToCode, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.qr_show_code_back))
    }
    Spacer(modifier = Modifier.height(8.dp))
    SecondaryActions(onNewCode = onNewCode, onDone = onDone)
}

@Composable
private fun HiddenContent(
    phase: ShowQrPhase.Hidden,
    onShowAgain: () -> Unit,
    onNewCode: () -> Unit,
    onDone: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Text(
                text = stringResource(phase.reasonRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    if (phase.canShowAgain) {
        Button(onClick = onShowAgain, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.qr_show_again))
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    SecondaryActions(onNewCode = onNewCode, onDone = onDone)
}

@Composable
private fun PlaceholderContent(
    message: String,
    onNewCode: () -> Unit,
    onDone: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }

    Spacer(modifier = Modifier.height(20.dp))
    SecondaryActions(onNewCode = onNewCode, onDone = onDone)
}

@Composable
private fun SecondaryActions(
    onNewCode: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedButton(onClick = onNewCode, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.qr_new_code))
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.qr_done))
        }
    }
}

/**
 * Keeps the sender name inside the payload limits: control characters are rejected by the
 * parser on the other side, and the name field is capped at 64 UTF-8 bytes.
 */
private fun sanitizeSenderName(input: String): String {
    val cleaned = input.filter { it.code >= 0x20 && it.code != 0x7F }
    if (cleaned.toByteArray(Charsets.UTF_8).size <= MAX_SENDER_NAME_BYTES) return cleaned
    var result = cleaned
    while (result.isNotEmpty() &&
        result.toByteArray(Charsets.UTF_8).size > MAX_SENDER_NAME_BYTES
    ) {
        result = result.dropLast(1)
    }
    return result
}

/** Walks the context wrappers up to the hosting Activity. */
private fun Context.findQrActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Registers the Android 14+ screen capture callback. Returns null when the callback cannot be
 * registered (the DETECT_SCREEN_CAPTURE permission is not declared) - FLAG_SECURE already
 * blocks captures in that case, so this is a best effort second line of defence.
 *
 * MissingPermission is suppressed on purpose: DETECT_SCREEN_CAPTURE is deliberately NOT declared
 * (the spec's manifest list does not include it and Android already blocks the capture through
 * FLAG_SECURE, so the permission would only add a store-visible entry for a callback that can
 * never fire). Both calls are wrapped in try/catch so the missing permission degrades to "no
 * callback" instead of a crash.
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun registerCaptureCallback(
    activity: Activity,
    onCapture: () -> Unit
): (() -> Unit)? {
    val callback = Activity.ScreenCaptureCallback { onCapture() }
    try {
        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
    } catch (e: RuntimeException) {
        // Typically a SecurityException because DETECT_SCREEN_CAPTURE is not declared.
        return null
    }
    return {
        try {
            activity.unregisterScreenCaptureCallback(callback)
        } catch (e: RuntimeException) {
            // Already unregistered together with the Activity.
        }
    }
}
