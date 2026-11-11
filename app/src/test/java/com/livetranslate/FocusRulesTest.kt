package com.livetranslate

import com.livetranslate.a11y.FocusRules
import com.livetranslate.a11y.FocusRules.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A screen that cannot be read right now (common on EMUI once the panel has been touched) must never
 * clear the tracked input, or the panel can never come back without killing the app.
 */
class FocusRulesTest {
    private val own = "com.livetranslate"

    private fun decide(
        rootPackage: String?,
        nodeUsable: Boolean = false,
        allowed: Boolean = true,
        trackedPackage: String? = "com.tencent.mm",
        trackedAlive: Boolean = true,
    ) = FocusRules.decide(rootPackage, nodeUsable, allowed, own, trackedPackage, trackedAlive)

    @Test fun unreadableScreenKeepsTrackedInput() {
        assertEquals(Decision.KEEP, decide(rootPackage = null))
    }

    @Test fun ownWindowKeepsTrackedInput() {
        assertEquals(Decision.KEEP, decide(rootPackage = own))
    }

    @Test fun usableAllowedNodeIsUsed() {
        assertEquals(Decision.USE, decide("com.tencent.mm", nodeUsable = true))
    }

    @Test fun usableNodeOutsideScopeIsCleared() {
        assertEquals(Decision.CLEAR, decide("com.android.settings", nodeUsable = true, allowed = false))
    }

    @Test fun samePackageStillAliveKeepsTrackedInput() {
        assertEquals(Decision.KEEP, decide("com.tencent.mm", nodeUsable = false, trackedAlive = true))
    }

    @Test fun samePackageLostNodeIsCleared() {
        assertEquals(Decision.CLEAR, decide("com.tencent.mm", nodeUsable = false, trackedAlive = false))
    }

    @Test fun otherAppClearsTrackedInput() {
        assertEquals(Decision.CLEAR, decide("com.android.settings", nodeUsable = false))
    }

    @Test fun nothingTrackedAndNothingUsableIsCleared() {
        assertEquals(Decision.CLEAR, decide("com.tencent.mm", trackedPackage = null, trackedAlive = false))
    }
}
