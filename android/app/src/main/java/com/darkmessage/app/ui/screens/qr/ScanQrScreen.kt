package com.darkmessage.app.ui.screens.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.darkmessage.app.Incoming
import com.darkmessage.app.R
import com.darkmessage.app.ui.qr.QrScannerView
import com.darkmessage.app.ui.qr.SecureWindow
import com.darkmessage.app.ui.qr.decodeQrFromBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Longest edge a picked photo is decoded to before the QR decoder runs over it. */
private const val MAX_PHOTO_DIMENSION = 2048

/** What the camera-unavailable dialog is about. */
private enum class CameraBlock { NO_CAMERA, DENIED }

/**
 * Receiver screen of the QR chat key exchange (spec 3.4), shown as a full screen route
 * ("scan_qr", bottom bar hidden): scan -> PIN -> confirm.
 *
 * @param onClose leaves the route (Cancel, back gesture, or after a chat was added).
 * @param onResult snackbar text for the Chats screen after a successful add/update.
 * @param onRequestManualEntry asks the Chats screen to open its normal "New chat" dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanQrScreen(
    onClose: () -> Unit,
    onResult: (String) -> Unit = {},
    onRequestManualEntry: () -> Unit = {},
    incoming: Incoming? = null,
    onIncomingConsumed: () -> Unit = {},
    viewModel: ScanQrViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    // Reported by the bound camera: the torch toggle only appears when there is a flash unit.
    var torchAvailable by remember { mutableStateOf(false) }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
    }

    // Granting a permission from system Settings does not restart the process, so the state
    // captured above would stay stale after the user follows the dialog's "Open Settings"
    // advice. Re-read it on every resume instead of leaving the screen in a dead end.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        permissionGranted = granted
        if (granted) permissionDenied = false
    }

    // The camera is requested on entering the screen and never before: it is only ever used
    // for scanning a code, and nothing is recorded or stored.
    LaunchedEffect(hasCamera) {
        if (hasCamera && !permissionGranted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.Default) {
                    loadBitmapForQr(context, uri)?.let { bitmap -> decodeQrFromBitmap(bitmap) }
                }
                if (text != null) viewModel.onCodeScanned(text) else viewModel.onNoQrInPhoto()
            }
        }
    }

    LaunchedEffect(incoming) {
        val item = incoming
        if (item is Incoming.ChatQr) {
            viewModel.onCodeScanned(item.text)
            onIncomingConsumed()
        }
    }

    val finished = uiState.finished
    val finishedText = finished?.let { stringResource(it.textRes, it.chatName) }
    LaunchedEffect(finishedText) {
        if (finishedText != null) {
            onResult(finishedText)
            onClose()
        }
    }

    val step = uiState.step

    // The camera preview shows the contact's QR code (the encrypted passphrase), the PIN step
    // shows the PIN that unlocks it and the Confirm step shows the passphrase itself: none of
    // it may be screenshotted, recorded or captured in the recents thumbnail, so the flag
    // covers the whole route. PreviewView runs in COMPATIBLE (TextureView) mode, which still
    // renders under FLAG_SECURE. SecureWindow clears the flag again when the route is left.
    SecureWindow()

    BackHandler {
        if (step is ScanQrStep.PinEntry) viewModel.backToScanning() else onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (step is ScanQrStep.PinEntry) R.string.qr_pin_title
                            else R.string.qr_scan_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (step is ScanQrStep.PinEntry) viewModel.backToScanning()
                            else onClose()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chats_cancel)
                        )
                    }
                },
                actions = {
                    if (torchAvailable && permissionGranted && step is ScanQrStep.Scanning) {
                        IconButton(onClick = { viewModel.toggleTorch() }) {
                            Icon(
                                if (uiState.torchOn) Icons.Outlined.FlashOn
                                else Icons.Outlined.FlashOff,
                                contentDescription = stringResource(R.string.qr_torch)
                            )
                        }
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
            uiState.bannerRes?.let { bannerRes ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(bannerRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            when (step) {
                is ScanQrStep.Scanning -> {
                    if (!hasCamera) {
                        CenteredMessage(stringResource(R.string.qr_no_camera))
                    } else if (!permissionGranted) {
                        CenteredMessage(stringResource(R.string.qr_camera_denied_title))
                    } else {
                        ScanningContent(
                            torchOn = uiState.torchOn,
                            restartKey = uiState.scanAttempt,
                            onCode = { viewModel.onCodeScanned(it) },
                            onTorchAvailable = { torchAvailable = it }
                        )
                    }
                    // A code can also arrive as a picture (a messenger, a photo taken by
                    // someone else). Keep this reachable whenever we are scanning, not only
                    // when the camera is unavailable: one phone cannot film its own screen.
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.qr_scan_from_photo))
                    }
                }

                is ScanQrStep.PinEntry -> PinEntryContent(
                    step = step,
                    onSubmit = { viewModel.submitPin(it) }
                )

                is ScanQrStep.Confirm -> ConfirmContent(
                    step = step,
                    isSaving = uiState.isSaving,
                    onAdd = { viewModel.addChat(it) },
                    onUpdateExisting = { viewModel.updateExistingChat() },
                    onCancel = onClose
                )
            }
        }

        val block = when {
            step !is ScanQrStep.Scanning -> null
            !hasCamera -> CameraBlock.NO_CAMERA
            permissionDenied && !permissionGranted -> CameraBlock.DENIED
            else -> null
        }
        if (block != null) {
            CameraUnavailableDialog(
                block = block,
                onOpenSettings = { openAppSettings(context) },
                onEnterManually = onRequestManualEntry,
                onPickPhoto = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onCancel = onClose
            )
        }
    }
}

@Composable
private fun ScanningContent(
    torchOn: Boolean,
    restartKey: Int,
    onCode: (String) -> Unit,
    onTorchAvailable: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
    ) {
        QrScannerView(
            onCode = onCode,
            torchOn = torchOn,
            modifier = Modifier.fillMaxSize(),
            restartKey = restartKey,
            onTorchAvailable = onTorchAvailable
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp)
                )
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.qr_scan_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PinEntryContent(
    step: ScanQrStep.PinEntry,
    onSubmit: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (e: IllegalStateException) {
            // Node not attached yet on some devices: tapping the boxes focuses the field.
        }
    }

    LaunchedEffect(step.shakeKey) {
        if (step.shakeKey > 0) {
            pin = ""
            for (target in SHAKE_STEPS) {
                shakeOffset.animateTo(target, tween(durationMillis = 45))
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { pin = "" }
    }

    Text(
        text = stringResource(R.string.qr_pin_enter_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    Box {
        // Hidden field that owns the IME; the six boxes below are only a rendering of it.
        BasicTextField(
            value = pin,
            onValueChange = { input ->
                if (!step.isChecking) {
                    pin = input.filter { it in '0'..'9' }.take(PIN_LENGTH)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f)
                .focusRequester(focusRequester)
        )

        Row(
            modifier = Modifier
                .offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
                .clickable { focusRequester.requestFocus() },
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (index in 0 until PIN_LENGTH) {
                PinBox(digit = pin.getOrNull(index), filled = index < pin.length)
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (step.wrongPin) {
        Text(
            text = stringResource(R.string.qr_pin_wrong, step.attemptsLeft),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (step.isChecking) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.qr_pin_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Button(
            onClick = { onSubmit(pin) },
            enabled = pin.length == PIN_LENGTH,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.qr_pin_continue))
        }
    }
}

@Composable
private fun PinBox(digit: Char?, filled: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 52.dp)
            .border(
                width = if (filled) 2.dp else 1.dp,
                color = if (filled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = digit?.toString() ?: "",
            fontFamily = FontFamily.Monospace,
            fontSize = 24.sp,
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

@Composable
private fun ConfirmContent(
    step: ScanQrStep.Confirm,
    isSaving: Boolean,
    onAdd: (String) -> Unit,
    onUpdateExisting: () -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(step.suggestedName) { mutableStateOf(step.suggestedName) }
    var showPassphrase by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.qr_confirm_title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.chats_contact_name)) },
        singleLine = true,
        enabled = !isSaving,
        modifier = Modifier.fillMaxWidth()
    )

    if (step.conflictChat != null && step.receivedName != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.qr_conflict_renamed,
                step.receivedName,
                step.suggestedName
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (step.sameKeyChat != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.qr_same_key_exists, step.sameKeyChat.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.qr_fingerprint, step.fingerprint),
        style = MaterialTheme.typography.titleMedium
    )
    Text(
        text = stringResource(R.string.qr_fingerprint_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = step.passphrase,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.chats_passphrase)) },
        singleLine = true,
        visualTransformation = if (showPassphrase) VisualTransformation.None
        else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { showPassphrase = !showPassphrase }) {
            Text(
                if (showPassphrase) stringResource(R.string.chats_hide)
                else stringResource(R.string.chats_show)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(R.string.qr_confirm_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = { onAdd(name) },
        enabled = !isSaving && name.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.qr_confirm_add))
    }

    if (step.conflictChat != null) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showUpdateDialog = true },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.qr_confirm_update_existing))
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.chats_cancel))
    }

    if (showUpdateDialog && step.conflictChat != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text(stringResource(R.string.qr_confirm_update_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.qr_confirm_update_body,
                        step.conflictChat.name
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpdateDialog = false
                        onUpdateExisting()
                    }
                ) {
                    Text(stringResource(R.string.qr_confirm_update_existing))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CameraUnavailableDialog(
    block: CameraBlock,
    onOpenSettings: () -> Unit,
    onEnterManually: () -> Unit,
    onPickPhoto: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(
                    if (block == CameraBlock.NO_CAMERA) R.string.qr_no_camera
                    else R.string.qr_camera_denied_title
                )
            )
        },
        text = {
            Column {
                if (block == CameraBlock.DENIED) {
                    Text(stringResource(R.string.qr_camera_denied_body))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.qr_open_settings))
                    }
                }
                TextButton(onClick = onEnterManually, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.qr_enter_manually))
                }
                TextButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.qr_scan_from_photo))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun CenteredMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private val SHAKE_STEPS = listOf(-14f, 14f, -10f, 10f, -6f, 6f, 0f)

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // No settings activity to open (heavily customised ROM): nothing else to do here.
    }
}

/**
 * Decodes a picked image into a software bitmap small enough to scan, without keeping the
 * full-resolution photo in memory. Returns null when the image cannot be read.
 */
private fun loadBitmapForQr(context: Context, uri: Uri): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestEdge <= 0) return null
        var sample = 1
        while (longestEdge / sample > MAX_PHOTO_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
