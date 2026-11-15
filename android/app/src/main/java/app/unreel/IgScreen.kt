package app.unreel

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A snapshot of what is on the Instagram screen, built from one walk of the
 * accessibility tree.
 *
 * Instagram's view ids change from time to time. Every id Unreel relies on is
 * listed in [Ids] so it can be updated in one place.
 */
data class IgScreen(
    /** A full-screen reel player is showing (Reels tab, or a reel opened from anywhere). */
    val inReelViewer: Boolean,
    /** The bottom "Reels" tab is the selected tab. */
    val reelsTabSelected: Boolean,
    /** The bottom "Profile" tab (your own profile) is the selected tab. */
    val profileTabSelected: Boolean,
    /** Author of the reel that is currently on screen, if it could be read. */
    val visibleAuthor: String?,
    /** Username shown in the top bar (used to learn your username on your profile). */
    val actionBarTitle: String?,
    /** The Home tab button, used to leave the Reels tab. */
    val homeTab: AccessibilityNodeInfo?,
) {
    object Ids {
        const val PREFIX = "com.instagram.android:id/"

        /** Any id starting with one of these means a reel player is on screen. */
        val VIEWER_PREFIXES = listOf("clips_viewer", "clips_viewer_view_pager", "reel_viewer_clips")
        val VIEWER_EXACT = setOf("root_clips_layout", "clips_root_layout", "clips_single_media_component")

        val AUTHOR = setOf(
            "clips_author_username",
            "clips_author_username_text",
            "clips_author_info_username",
            "clips_attribution_username",
        )
        val AUTHOR_CONTAINS = listOf("author_username")

        const val TAB_REELS = "clips_tab"
        const val TAB_PROFILE = "profile_tab"
        const val TAB_HOME = "feed_tab"

        val ACTION_BAR_TITLE = setOf(
            "action_bar_title",
            "action_bar_large_title",
            "action_bar_large_title_auto_size",
            "action_bar_username",
        )
    }

    companion object {
        private val USERNAME_TOKEN = Regex("[A-Za-z0-9._]{1,30}")

        fun read(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): IgScreen {
            var inViewer = false
            var reelsTab = false
            var profileTab = false
            var title: String? = null
            var homeTab: AccessibilityNodeInfo? = null
            val authors = mutableListOf<Pair<String, Rect>>()

            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 4000) {
                val node = stack.removeLast()
                visited++
                val id = node.viewIdResourceName?.removePrefix(Ids.PREFIX)
                if (id != null) {
                    when {
                        id in Ids.VIEWER_EXACT || Ids.VIEWER_PREFIXES.any { id.startsWith(it) } ->
                            if (node.isVisibleToUser) inViewer = true

                        id == Ids.TAB_REELS -> reelsTab = node.isSelected
                        id == Ids.TAB_PROFILE -> profileTab = node.isSelected
                        id == Ids.TAB_HOME -> homeTab = node

                        id in Ids.ACTION_BAR_TITLE ->
                            if (title == null) title = usernameFrom(node.text)

                        id in Ids.AUTHOR || Ids.AUTHOR_CONTAINS.any { id.contains(it) } -> {
                            val name = usernameFrom(node.text) ?: usernameFrom(node.contentDescription)
                            if (name != null && node.isVisibleToUser) {
                                val r = Rect().also { node.getBoundsInScreen(it) }
                                authors += name to r
                            }
                        }
                    }
                }
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
            }

            // Several pages of the reel pager can be in the tree at once.
            // The one actually on screen is the one whose author label sits inside the screen.
            val screen = Rect(0, 0, screenWidth, screenHeight)
            val author = authors
                .filter { Rect.intersects(it.second, screen) }
                .maxByOrNull { visibleArea(it.second, screen) }
                ?.first

            return IgScreen(
                inReelViewer = inViewer || reelsTab,
                reelsTabSelected = reelsTab,
                profileTabSelected = profileTab,
                visibleAuthor = author,
                actionBarTitle = title,
                homeTab = homeTab,
            )
        }

        private fun visibleArea(r: Rect, screen: Rect): Int {
            val i = Rect(r)
            return if (i.intersect(screen)) i.width() * i.height() else 0
        }

        fun usernameFrom(text: CharSequence?): String? {
            if (text.isNullOrBlank()) return null
            val token = USERNAME_TOKEN.find(text.toString().trim().removePrefix("@"))?.value ?: return null
            return token.takeIf { Prefs.isValid(it) }
        }
    }
}
