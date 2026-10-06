package dev.amenhancer.module.hook

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.CurrentSongIdentityProtocol
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

/** Responds only to the module's signature-protected, user-initiated requests. */
internal class CurrentSongIdentityRequestResponder(
    private val application: Application,
    private val cache: CurrentSongIdentityCache,
    private val logger: (String) -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CurrentSongIdentityProtocol.REQUEST_ACTION) return
            val token = intent.getStringExtra(CurrentSongIdentityProtocol.EXTRA_REQUEST_TOKEN)
                ?.takeIf(String::isNotBlank)
                ?: return
            val resultReceiver = intent.resultReceiver() ?: return
            val details = cache.current()?.details
            resultReceiver.send(
                if (details == null) {
                    CurrentSongIdentityProtocol.RESULT_UNAVAILABLE
                } else {
                    CurrentSongIdentityProtocol.RESULT_AVAILABLE
                },
                Bundle().apply {
                    putString(CurrentSongIdentityProtocol.EXTRA_REQUEST_TOKEN, token)
                    details?.let {
                        putLong(CurrentSongIdentityProtocol.EXTRA_APPLE_MUSIC_ID, it.appleMusicId)
                        it.title?.let { title ->
                            putString(CurrentSongIdentityProtocol.EXTRA_SONG_TITLE, title)
                        }
                        it.artist?.let { artist ->
                            putString(CurrentSongIdentityProtocol.EXTRA_SONG_ARTIST, artist)
                        }
                    }
                },
            )
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register(): Boolean = runCatching {
        val filter = IntentFilter(CurrentSongIdentityProtocol.REQUEST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                receiver,
                filter,
                CurrentSongIdentityProtocol.REQUEST_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            application.registerReceiver(
                receiver,
                filter,
                CurrentSongIdentityProtocol.REQUEST_PERMISSION,
                null,
            )
        }
        true
    }.onFailure { error ->
        logger("current song identity request receiver failed: $error")
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun Intent.resultReceiver(): ResultReceiver? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(
                CurrentSongIdentityProtocol.EXTRA_RESULT_RECEIVER,
                ResultReceiver::class.java,
            )
        } else {
            getParcelableExtra(CurrentSongIdentityProtocol.EXTRA_RESULT_RECEIVER)
        }
}

