package com.danielealbano.androidremotecontrolmcp.services.character

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One-tap import from any app's share sheet: the card is stored, made the active character, the
 * user is told, and the chat is opened on it. Not a card: the caller keeps its normal handling.
 */
class CharacterShareImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val characters: CharacterRepository,
    ) {
        /** True when [bytes] were a character card (imported, or already present). */
        fun isCard(bytes: ByteArray): Boolean = CharacterCardParser.parse(bytes) != null

        suspend fun importAndOpen(bytes: ByteArray) {
            val result = characters.import(bytes)
            val message =
                result.fold(
                    onSuccess = { card ->
                        characters.setActive(card.id)
                        context.getString(R.string.character_imported, card.name)
                    },
                    onFailure = { it.message ?: context.getString(R.string.character_import_failed) },
                )
            withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
            if (result.isSuccess) {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB, TopLevelRoute.Chat.route),
                )
            }
        }
    }
