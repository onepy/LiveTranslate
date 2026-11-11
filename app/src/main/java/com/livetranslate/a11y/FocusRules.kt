package com.livetranslate.a11y

/**
 * What to do with the currently tracked input box when we re-read the screen but cannot see a usable
 * editable node.
 *
 * The old code cleared the tracked input whenever [android.view.accessibility.AccessibilityService.rootInActiveWindow]
 * or `findFocus` came back empty, which happens routinely right after the panel itself is touched or
 * after any settings change on HarmonyOS. That permanently removed the panel from the screen with no
 * way back except restarting the process. Only a positive sighting of another window may clear it.
 */
object FocusRules {

    enum class Decision {
        /** Keep the tracked input: the screen could not be read right now. */
        KEEP,

        /** A usable editable node was found; use it. */
        USE,

        /** The tracked input is really gone (other app, or the editor disappeared). */
        CLEAR,
    }

    fun decide(
        /** Package of the window we could look at, or null when the framework has no active window. */
        rootPackage: String?,
        /** True when that window has an editable, non-password input node. */
        nodeUsable: Boolean,
        /** True when the tracked package is inside the enabled App scope. */
        allowed: Boolean,
        ownPackage: String,
        /** Package of the input we currently track, if any. */
        trackedPackage: String?,
        /** True when the tracked node still exists (refresh() succeeded). */
        trackedAlive: Boolean,
    ): Decision = when {
        rootPackage == null -> Decision.KEEP
        rootPackage == ownPackage -> Decision.KEEP
        nodeUsable -> if (allowed) Decision.USE else Decision.CLEAR
        trackedPackage == null -> Decision.CLEAR
        trackedAlive && trackedPackage == rootPackage -> Decision.KEEP
        else -> Decision.CLEAR
    }
}
