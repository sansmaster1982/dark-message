package com.darkmessage.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.darkmessage.app.ui.components.TutorialOverlay
import com.darkmessage.app.ui.components.TutorialStep
import com.darkmessage.app.ui.navigation.BottomNavBar
import com.darkmessage.app.ui.navigation.DarkMessageNavHost
import com.darkmessage.app.ui.navigation.Screen
import com.darkmessage.app.ui.screens.onboarding.OnboardingScreen
import com.darkmessage.app.ui.screens.settings.SettingsViewModel
import com.darkmessage.app.ui.theme.DarkMessageTheme
import dagger.hilt.android.AndroidEntryPoint

/** URL scheme of the chat-key QR deep link (see AndroidManifest intent-filter). */
private const val QR_SCHEME = "darkmessage"

/**
 * True for the routes that own the whole screen and must not show the bottom navigation bar:
 * the sender QR card and the receiver scanner.
 */
private fun hidesBottomBar(route: String?): Boolean =
    route != null && (route.startsWith("show_qr") || route == Screen.ScanQr.route)

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    /**
     * Holds the file/text handed to us by another app until the Decrypt screen consumes it, or a
     * darkmessage:// chat-key link until the QR scan screen consumes it.
     */
    private val mainViewModel: MainViewModel by viewModels()

    private fun extractIncoming(intent: Intent?): Incoming? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) {
                    Incoming.File(stream)
                } else {
                    // Encrypted Base64 message shared as plain text from a messenger
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Incoming.Text(it) }
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (QR_SCHEME.equals(uri.scheme, ignoreCase = true)) {
                    // Chat-key QR link opened from a system camera / scanner app. The payload is
                    // a PATH segment, so the raw string is kept as is and handed to the QR parser
                    // (never getQueryParameter, never routed to Decrypt).
                    intent.dataString?.takeIf { it.isNotBlank() }?.let { Incoming.ChatQr(it) }
                } else {
                    Incoming.File(uri)
                }
            }
            else -> null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Read the launch intent only on a fresh start. After a configuration change getIntent()
        // still returns the original VIEW/SEND intent, and re-reading it would re-import the file.
        if (savedInstanceState == null) {
            mainViewModel.offer(extractIncoming(intent))
        }

        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val themeMode by settingsViewModel.themeMode.collectAsState()
            val language by settingsViewModel.language.collectAsState()
            val isLoaded by settingsViewModel.isLoaded.collectAsState()
            val onboardingCompleted by settingsViewModel.onboardingCompleted.collectAsState()
            val tutorialCompleted by settingsViewModel.tutorialCompleted.collectAsState()
            val pendingIncoming by mainViewModel.incoming.collectAsState()

            // Apply the persisted language only once settings are loaded, and only when it
            // actually differs from what AppCompat already has, to avoid a recreate loop/flash.
            LaunchedEffect(isLoaded, language) {
                if (!isLoaded) return@LaunchedEffect
                val wanted = LocaleListCompat.forLanguageTags(language.code)
                val current = AppCompatDelegate.getApplicationLocales()
                if (current.toLanguageTags() != wanted.toLanguageTags()) {
                    AppCompatDelegate.setApplicationLocales(wanted)
                }
            }

            DarkMessageTheme(themeMode = themeMode) {
                // Root Surface: paints the theme background and provides LocalContentColor for
                // every root (loading frame, onboarding, tutorial overlay), not only the Scaffold.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isLoaded) {
                        if (!onboardingCompleted) {
                            OnboardingScreen(
                                onFinished = { settingsViewModel.completeOnboarding() }
                            )
                        } else {
                            val navController = rememberNavController()

                            // Use BoxWithConstraints at full-screen level to measure for tutorial
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val density = LocalDensity.current
                                val screenWidthPx = with(density) { maxWidth.toPx() }
                                val screenHeightPx = with(density) { maxHeight.toPx() }

                                val currentRoute = navController
                                    .currentBackStackEntryAsState().value?.destination?.route

                                // Targeting API 36 enforces edge-to-edge, and a phone held
                                // sideways has its camera cutout on the LEFT or RIGHT edge.
                                // Scaffold, TopAppBar and NavigationBar pad for the system bars
                                // only, never for the cutout, so on a Pixel 7 Pro in landscape
                                // the first avatar and the first glyph of a row sat under the
                                // hole-punch. Pad the whole shell horizontally by the cutout:
                                // the root Surface still paints the background to the edge,
                                // and windowInsetsPadding consumes what it applied, so the
                                // screens' own Scaffolds do not add it a second time.
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .windowInsetsPadding(
                                            WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
                                        )
                                ) {
                                    Scaffold(
                                        bottomBar = {
                                            // The QR routes are full screen (camera preview / QR card).
                                            if (!hidesBottomBar(currentRoute)) BottomNavBar(navController)
                                        }
                                    ) { padding ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(padding)
                                                // The root Scaffold already applied the status- and
                                                // navigation-bar insets; consume them so the per-screen
                                                // Scaffolds do not add them a second time.
                                                .consumeWindowInsets(padding)
                                        ) {
                                            DarkMessageNavHost(
                                                navController = navController,
                                                incoming = pendingIncoming,
                                                onIncomingConsumed = { mainViewModel.consume() }
                                            )
                                        }
                                    }
                                }

                                // Tutorial overlay covers the FULL screen (on top of Scaffold)
                                if (!tutorialCompleted) {
                                    val navBarHeight = with(density) { 80.dp.toPx() }
                                    val navBarInset = with(density) { 48.dp.toPx() } // gesture nav inset
                                    val itemWidth = screenWidthPx / 4f
                                    val navTop = screenHeightPx - navBarHeight - navBarInset

                                    val tutorialSteps = listOf(
                                        TutorialStep(
                                            titleRes = R.string.tutorial_step1_title,
                                            descRes = R.string.tutorial_step1_desc,
                                            highlightRect = Rect(0f, navTop, itemWidth, navTop + navBarHeight)
                                        ),
                                        TutorialStep(
                                            titleRes = R.string.tutorial_step2_title,
                                            descRes = R.string.tutorial_step2_desc,
                                            highlightRect = Rect(itemWidth, navTop, itemWidth * 2, navTop + navBarHeight)
                                        ),
                                        TutorialStep(
                                            titleRes = R.string.tutorial_step3_title,
                                            descRes = R.string.tutorial_step3_desc,
                                            highlightRect = Rect(itemWidth * 2, navTop, itemWidth * 3, navTop + navBarHeight)
                                        ),
                                        TutorialStep(
                                            titleRes = R.string.tutorial_step4_title,
                                            descRes = R.string.tutorial_step4_desc,
                                            highlightRect = Rect(itemWidth * 3, navTop, itemWidth * 4, navTop + navBarHeight)
                                        )
                                    )

                                    var currentTutorialStep by remember { mutableIntStateOf(0) }

                                    TutorialOverlay(
                                        steps = tutorialSteps,
                                        currentStep = currentTutorialStep,
                                        onNext = { currentTutorialStep++ },
                                        onDismiss = { settingsViewModel.completeTutorial() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * With launchMode="singleTask" a .darkm opened while the app is already running is delivered
     * here instead of spawning a second MainActivity instance.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainViewModel.offer(extractIncoming(intent))
    }
}
