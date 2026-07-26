package com.musornibak.pocketjarvis.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.musornibak.pocketjarvis.service.OrionService

/**
 * Ловит клик по кнопке на Bluetooth-гарнитуре / гарнитуре в jack.
 * PLAY / PAUSE / HEADSETHOOK триггерит Orion.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (key.action != KeyEvent.ACTION_DOWN) return
        when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                runCatching {
                    ContextCompat.startForegroundService(ctx, OrionService.wakeIntent(ctx))
                }
                if (isOrderedBroadcast) abortBroadcast()
            }
        }
    }
}
