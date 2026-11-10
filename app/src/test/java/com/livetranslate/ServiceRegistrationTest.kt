package com.livetranslate

import com.livetranslate.a11y.ServiceRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRegistrationTest {
    private var services = listOf("reader", "translate", "keyboard")

    private fun registration(write: (List<String>) -> Boolean = { services = it; true }) = ServiceRegistration(
        "translate", { it == "translate" || it == "translate/short" }, { services }, write,
    )

    @Test fun restartPreservesChangesToOtherServicesDuringPause() {
        assertTrue(registration().restart {
            assertEquals(listOf("reader", "keyboard"), services)
            services = listOf("reader", "new-service")
        })
        assertEquals(listOf("reader", "new-service", "translate"), services)
    }

    @Test fun deniedRemovalDoesNotReportRestartOrPause() {
        var paused = false
        assertFalse(registration { false }.restart { paused = true })
        assertFalse(paused)
        assertEquals(listOf("reader", "translate", "keyboard"), services)
    }

    @Test fun deniedRestoreReportsFailure() {
        assertFalse(registration { desired ->
            if ("translate" in desired) false else { services = desired; true }
        }.restart {})
    }

    @Test fun partialRemovalFailureStillRestoresRegistration() {
        var paused = false
        assertFalse(registration { desired ->
            services = desired
            "translate" in desired
        }.restart { paused = true })
        assertFalse(paused)
        assertEquals(listOf("reader", "keyboard", "translate"), services)
    }

    @Test fun cancellationRestoresRegistrationBeforePropagating() {
        assertThrows(InterruptedException::class.java) {
            registration().restart { throw InterruptedException() }
        }
        assertEquals(listOf("reader", "keyboard", "translate"), services)
    }

    @Test fun existingShortComponentIsNotDuplicated() {
        services = listOf("reader", "translate/short")
        assertTrue(registration().enable())
        assertEquals(listOf("reader", "translate/short"), services)
    }
}
