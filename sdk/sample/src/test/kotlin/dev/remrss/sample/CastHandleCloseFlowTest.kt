package dev.remrss.sample

import dev.remrss.platform.sdk.RearCastSessionHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class CastHandleCloseFlowTest {
    private val handle = RearCastSessionHandle("session-a", 3L)
    private val newerHandle = RearCastSessionHandle("session-b", 4L)

    @Test fun closingQueryRetainsHandleUntilTerminalState() {
        assertEquals(handle, castHandleAfterQuery(handle, handle, "CLOSING"))
        assertEquals(handle, castHandleAfterQuery(handle, handle, "READY"))
        assertEquals(handle, castHandleAfterQuery(handle, handle, "INDETERMINATE"))
        assertEquals(null, castHandleAfterQuery(handle, handle, "CLOSED"))
        assertEquals(null, castHandleAfterQuery(handle, handle, "FAILED"))
    }

    @Test fun staleTerminalQueryCannotClearNewReadyHandle() {
        assertEquals(newerHandle, castHandleAfterQuery(newerHandle, handle, "CLOSED"))
        assertEquals(newerHandle, castHandleAfterQuery(newerHandle, handle, "FAILED"))
    }
}
