package com.darkmessage.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.darkmessage.app.R

sealed class Screen(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    data object Chats : Screen("chats", R.string.nav_chats, Icons.AutoMirrored.Outlined.Chat)
    data object Encrypt : Screen("encrypt", R.string.nav_encrypt, Icons.Outlined.Lock)
    data object Decrypt : Screen("decrypt", R.string.nav_decrypt, Icons.Outlined.LockOpen)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Outlined.Settings)

    /**
     * Sender side of the QR chat key exchange: a full screen flow (bottom bar hidden) opened
     * from a chat row in Chats. Deliberately NOT part of [items].
     */
    data object ShowQr : Screen("show_qr/{chatId}", R.string.qr_show, Icons.Outlined.QrCode2) {
        const val ARG_CHAT_ID = "chatId"

        fun route(chatId: Long): String = "show_qr/$chatId"
    }

    /**
     * Receiver side of the QR chat key exchange: a full screen flow (bottom bar hidden) opened
     * from the Chats toolbar, the empty state, or a darkmessage:// link. NOT part of [items].
     */
    data object ScanQr : Screen("scan_qr", R.string.qr_scan, Icons.Outlined.QrCodeScanner)

    companion object {
        /** The four bottom navigation tabs; the QR routes are full screen and never listed. */
        val items = listOf(Chats, Encrypt, Decrypt, Settings)
    }
}
