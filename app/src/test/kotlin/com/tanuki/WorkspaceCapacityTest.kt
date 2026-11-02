package com.tanuki

import com.tanuki.storage.WorkspaceCapacity
import com.tanuki.storage.WorkspaceStorageState
import org.junit.Assert.*
import org.junit.Test

class WorkspaceCapacityTest {
    @Test fun decimalInputUsesBinaryGiBAndRejectsInvalidOrOverflowingValues() {
        assertEquals(WorkspaceCapacity.MIN_LIMIT, WorkspaceCapacity.parseGiB("0.0625"))
        assertEquals(1610612736L, WorkspaceCapacity.parseGiB("1,5"))
        assertEquals(2L * WorkspaceCapacity.GIB, WorkspaceCapacity.parseGiB(" 2 "))
        for (invalid in listOf("", "0", "-1", "NaN", "1e9", "0.000001", "1.1234567", "9223372036854775807")) {
            assertThrows("invalid capacity $invalid", IllegalArgumentException::class.java) { WorkspaceCapacity.parseGiB(invalid) }
        }
    }

    @Test fun capacityIncludesExistingDataButLeavesDeviceReserveAvailable() {
        val used = 2L * WorkspaceCapacity.GIB
        val free = WorkspaceCapacity.GIB
        val maximum = used + free - WorkspaceCapacity.DISK_RESERVE
        WorkspaceCapacity.validateLimit(maximum, used, free)
        assertThrows(IllegalArgumentException::class.java) { WorkspaceCapacity.validateLimit(used - 1, used, free) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceCapacity.validateLimit(maximum + 1, used, free) }
    }

    @Test fun exhaustedDiskAndLargeNumbersNeverInventFreeCapacity() {
        assertEquals(123L, WorkspaceCapacity.availableForWorkspace(123, WorkspaceCapacity.DISK_RESERVE - 1))
        assertEquals(Long.MAX_VALUE, WorkspaceCapacity.availableForWorkspace(Long.MAX_VALUE - 10, WorkspaceCapacity.GIB))
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceCapacity.validateLimit(WorkspaceCapacity.MIN_LIMIT, 0, WorkspaceCapacity.DISK_RESERVE)
        }
    }

    @Test fun remainingCapacityIsLimitedByBothBudgetAndPhysicalSpace() {
        val state = WorkspaceStorageState(limitBytes = 5L * WorkspaceCapacity.GIB,
            usedBytes = 2L * WorkspaceCapacity.GIB, freeBytes = WorkspaceCapacity.GIB)
        assertTrue(state.complete)
        assertEquals(WorkspaceCapacity.GIB - WorkspaceCapacity.DISK_RESERVE, state.availableBytes)
        assertFalse(state.overBudget)
        val full = state.copy(usedBytes = 5L * WorkspaceCapacity.GIB)
        assertFalse(full.overBudget)
        assertTrue(full.copy(usedBytes = full.usedBytes!! + 1).overBudget)
        assertEquals(0L, full.availableBytes)
    }

    @Test fun unknownOrIncompleteMeasurementsNeverAppearAsEmptyOrWithinBudget() {
        assertNull(WorkspaceStorageState().availableBytes)
        val partial = WorkspaceStorageState(limitBytes = WorkspaceCapacity.GIB, usedBytes = 0,
            freeBytes = 3L * WorkspaceCapacity.GIB, error = "统计超时")
        assertFalse(partial.complete)
        assertNull(partial.availableBytes)
        assertTrue(partial.copy(usedBytes = WorkspaceCapacity.GIB + 1).overBudget)
        assertNull(partial.copy(error = null, measuring = true).availableBytes)
        val empty = partial.copy(error = null)
        assertTrue(empty.complete)
        assertEquals(WorkspaceCapacity.GIB, empty.availableBytes)
    }
}
