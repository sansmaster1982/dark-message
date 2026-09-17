package com.darkmessage.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.darkmessage.app.Incoming
import com.darkmessage.app.ui.screens.chats.ChatsScreen
import com.darkmessage.app.ui.screens.decrypt.DecryptScreen
import com.darkmessage.app.ui.screens.encrypt.EncryptScreen
import com.darkmessage.app.ui.screens.qr.ScanQrScreen
import com.darkmessage.app.ui.screens.qr.ShowQrScreen
import com.darkmessage.app.ui.screens.settings.SettingsScreen

/** Saved-state key the scanner uses to hand a success message back to the Chats screen. */
const val QR_TOAST_KEY = "qr_toast"

/** Saved-state key that asks the Chats screen to open its normal "New chat" dialog. */
const val OPEN_ADD_CHAT_KEY = "open_add_chat"

@Composable
fun DarkMessageNavHost(
    navController: NavHostController,
    incoming: Incoming? = null,
    onIncomingConsumed: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Chats.route
    ) {
        composable(Screen.Chats.route) {
            ChatsScreen(
                onShowQr = { chat -> navController.navigate(Screen.ShowQr.route(chat.id)) },
                onScanQr = { navController.navigate(Screen.ScanQr.route) }
            )
        }
        composable(Screen.Encrypt.route) {
            EncryptScreen()
        }
        composable(Screen.Decrypt.route) {
            DecryptScreen(
                incoming = incoming,
                onIncomingConsumed = onIncomingConsumed
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }

        // Full screen QR flows: they are not tabs, and MainActivity hides the bottom bar while
        // one of them is the current destination.
        composable(
            route = Screen.ShowQr.route,
            arguments = listOf(
                navArgument(Screen.ShowQr.ARG_CHAT_ID) { type = NavType.LongType }
            )
        ) {
            ShowQrScreen(onDone = { navController.popBackStack() })
        }
        composable(Screen.ScanQr.route) {
            ScanQrScreen(
                onClose = { navController.popBackStack() },
                onResult = { message ->
                    // Read by ChatsScreen from its own back stack entry once we are popped.
                    navController.previousBackStackEntry
                        ?.savedStateHandle?.set(QR_TOAST_KEY, message)
                },
                onRequestManualEntry = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle?.set(OPEN_ADD_CHAT_KEY, true)
                    navController.popBackStack()
                },
                incoming = incoming,
                onIncomingConsumed = onIncomingConsumed
            )
        }
    }

    // Route a received item: a scanned/opened darkmessage:// chat key goes to the QR scanner
    // (which starts at the PIN step), everything else to the Decrypt tab. This effect lives in
    // the same composition as the NavHost above, so the navigation graph is already set when it
    // runs. The currentDestination guard only protects against an unexpected ordering: the item
    // is not lost in that case, the target screen still consumes it once it is opened.
    LaunchedEffect(incoming) {
        if (incoming == null) return@LaunchedEffect
        val current = navController.currentDestination ?: return@LaunchedEffect
        val target = if (incoming is Incoming.ChatQr) Screen.ScanQr.route else Screen.Decrypt.route
        if (current.route != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.startDestinationId) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
}
