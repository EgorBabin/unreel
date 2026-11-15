package app.unreel

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches the Instagram app and closes any reel that is not yours.
 *
 * Decision for a reel on screen:
 *   • author is visible and it is you             → allow
 *   • author is visible and it is someone else    → close
 *   • author unknown, opened from your profile    → allow
 *   • author unknown, Reels tab / anywhere else   → close after a short grace period
 */
class ReelBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var scanScheduled = false
    private var inViewer = false
    private var viewerEnteredAt = 0L
    private var viewerOpenedFromOwnProfile = false
    private var lastScreenWasOwnProfile = false
    private var lastBlockAt = 0L
    private var lastToastAt = 0L

    private val scanRunnable = Runnable {
        scanScheduled = false
        try { scan() } catch (_: Throwable) { /* never crash the service */ }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != INSTAGRAM) return
        scheduleScan(SCAN_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    private fun scheduleScan(delay: Long) {
        if (scanScheduled) return
        scanScheduled = true
        handler.postDelayed(scanRunnable, delay)
    }

    private fun scan() {
        if (!prefs.enabled) return
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        if (root.packageName?.toString() != INSTAGRAM) return

        val dm = resources.displayMetrics
        val screen = IgScreen.read(root, dm.widthPixels, dm.heightPixels)
        val now = SystemClock.uptimeMillis()

        if (!screen.inReelViewer) {
            inViewer = false
            // First visit to the Profile tab always lands on your own profile: learn the username there.
            if (screen.profileTabSelected && prefs.detectedUsername.isEmpty()) {
                screen.actionBarTitle?.let { prefs.detectedUsername = it }
            }
            val me = prefs.ownUsername
            lastScreenWasOwnProfile = me != null && screen.actionBarTitle.equals(me, ignoreCase = true)
            return
        }

        if (!inViewer) {
            inViewer = true
            viewerEnteredAt = now
            viewerOpenedFromOwnProfile = !screen.reelsTabSelected && lastScreenWasOwnProfile
        }

        val me = prefs.ownUsername
        val author = screen.visibleAuthor

        val allow = when {
            me == null -> false
            author != null -> author.equals(me, ignoreCase = true)
            viewerOpenedFromOwnProfile -> true
            now - viewerEnteredAt < AUTHOR_GRACE_MS -> {
                // Give the author label a moment to render, then look again.
                scheduleScan(AUTHOR_GRACE_MS)
                return
            }
            else -> false
        }

        if (!allow) block(screen, now)
    }

    private fun block(screen: IgScreen, now: Long) {
        if (now - lastBlockAt < BLOCK_COOLDOWN_MS) {
            scheduleScan(BLOCK_COOLDOWN_MS)
            return
        }
        lastBlockAt = now
        inViewer = false

        val leftReelsTab = screen.reelsTabSelected &&
            screen.homeTab?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!leftReelsTab) performGlobalAction(GLOBAL_ACTION_BACK)

        prefs.blockedCount = prefs.blockedCount + 1

        if (prefs.showToasts && now - lastToastAt > TOAST_INTERVAL_MS) {
            lastToastAt = now
            Toast.makeText(this, "Unreel: reel blocked", Toast.LENGTH_SHORT).show()
        }
        // Make sure we really left (e.g. Back only closed a comments sheet).
        scheduleScan(BLOCK_COOLDOWN_MS + 50)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val INSTAGRAM = "com.instagram.android"
        private const val SCAN_DELAY_MS = 150L
        private const val AUTHOR_GRACE_MS = 1200L
        private const val BLOCK_COOLDOWN_MS = 700L
        private const val TOAST_INTERVAL_MS = 3000L
    }
}
