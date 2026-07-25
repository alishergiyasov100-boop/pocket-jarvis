package com.musornibak.pocketjarvis.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

class JarvisAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 800) return true
        lastTrigger = now
        runCatching {
            ContextCompat.startForegroundService(this, JarvisOverlayService.wakeIntent(this))
        }
        return true
    }

    private var lastTrigger = 0L
}
