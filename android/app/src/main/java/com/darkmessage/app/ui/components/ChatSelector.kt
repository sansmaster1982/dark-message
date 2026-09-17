package com.darkmessage.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.darkmessage.app.R
import com.darkmessage.app.data.model.Chat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSelector(
    chats: List<Chat>,
    selectedChat: Chat?,
    onChatSelected: (Chat) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedChat?.name ?: stringResource(R.string.chat_selector_placeholder),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            label = { Text(stringResource(R.string.chat_selector_label)) }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            chats.forEach { chat ->
                DropdownMenuItem(
                    text = { Text(chat.name) },
                    onClick = {
                        onChatSelected(chat)
                        expanded = false
                    }
                )
            }
            if (chats.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_selector_empty)) },
                    onClick = { expanded = false },
                    enabled = false
                )
            }
        }
    }
}
