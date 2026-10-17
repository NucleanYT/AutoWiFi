package com.nucleanyt.autowifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppOpsTest {

    @Test
    fun readsMiuiAutostartMode() {
        assertEquals(true, AppOps.parseAllowed("MIUIOP(10008): allow; time=+1d2h ago\n"))
        assertEquals(false, AppOps.parseAllowed("MIUIOP(10008): deny\n"))
        assertEquals(false, AppOps.parseAllowed("AUTO_START: ignore; rejectTime=+3m ago"))
        assertEquals(false, AppOps.parseAllowed("MIUIOP(10008): default"))
    }

    @Test
    fun unknownOutput_isNull() {
        assertNull(AppOps.parseAllowed("Error: Unknown operation string: 10008"))
        assertNull(AppOps.parseAllowed("No operations."))
        assertNull(AppOps.parseAllowed(""))
    }
}
