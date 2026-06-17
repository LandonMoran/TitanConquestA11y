package com.titanconquest.a11y.accessibility

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

/**
 * Utility for sending live-region announcements to TalkBack.
 *
 * Use this to announce battle results, navigation changes, and other
 * dynamic events that TalkBack won't pick up automatically.
 */
object A11yAnnouncer {

    /**
     * Announce a message via TalkBack. Call after any significant game event:
     * - Battle results (hit, kill, loot)
     * - Location changes
     * - Level up
     * - Chat messages received
     */
    fun announce(context: Context, message: String) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return
        if (!am.isEnabled) return

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
            className = "android.app.Activity"
            packageName = context.packageName
            text.add(message)
        }
        am.sendAccessibilityEvent(event)
    }

    /**
     * Announce that a new screen/section has been loaded.
     * This cues TalkBack to read the new content.
     */
    fun announceScreenChange(context: Context, screenName: String) {
        announce(context, "Navigated to $screenName")
    }
}

/**
 * Accessibility labels for common game actions.
 * Centralised here so they're consistent across the whole UI.
 */
object A11yLabels {

    // Navigation
    const val NAV_PATROL = "Patrol — find and battle enemies"
    const val NAV_HERO = "My Hero — view stats and gear"
    const val NAV_LOCATIONS = "Locations — travel to a new area"
    const val NAV_CHAT = "Chat — global and clan messages"
    const val NAV_BOUNTIES = "Bounties — daily missions"

    // Battle actions
    const val ACTION_STRIKE = "Strike enemy"
    const val ACTION_RUN = "Run from battle — restores HP"
    const val ACTION_TAKE_COVER = "Take cover — restore some HP"
    const val ACTION_SUPER = "Use Super ability"

    // Login
    const val FIELD_USERNAME = "Username"
    const val FIELD_PASSWORD = "Password"
    const val BUTTON_LOGIN = "Log in to Titan Conquest"

    // Generic
    const val BUTTON_REFRESH = "Refresh"
    const val LOADING = "Loading, please wait"

    /**
     * Build an enemy button label for TalkBack.
     * e.g. "Attack Cyclops II — HP 80 of 120. Avenging opportunity."
     */
    fun enemyAttackLabel(name: String, tier: Int, hp: Int, maxHp: Int, isAvenging: Boolean): String {
        val tierSuffix = if (tier > 1) " ${"I".repeat(tier)}" else ""
        val avenge = if (isAvenging) " Avenging opportunity — double rewards." else ""
        return "Attack $name$tierSuffix. HP $hp of $maxHp.$avenge"
    }

    /**
     * HP bar description for low-vision users.
     * e.g. "HP: 45 of 200, 22 percent"
     */
    fun hpBarLabel(current: Int, max: Int): String {
        val pct = if (max > 0) (current * 100 / max) else 0
        return "HP: $current of $max, $pct percent"
    }

    /**
     * XP bar description.
     */
    fun xpBarLabel(current: Long, next: Long): String {
        val pct = if (next > 0) (current * 100 / next) else 0
        return "XP: $current of $next needed for next level, $pct percent"
    }
}
