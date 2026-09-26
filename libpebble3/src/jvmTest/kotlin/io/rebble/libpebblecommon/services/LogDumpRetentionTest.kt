package io.rebble.libpebblecommon.services

import kotlin.test.Test
import kotlin.test.assertEquals

class LogDumpRetentionTest {
    @Test fun keepsTheNewestDumpsOfThisWatchOnly() {
        val names = listOf(
            "logs-AA:BB-1700000000300", "logs-AA:BB-1700000000100", "logs-AA:BB-1700000000200",
            "logs-AA:BB-999", "logs-CC:DD-1600000000000", "logs-AA:BB", "logs-AA:BB-notes", "other.txt",
        )
        assertEquals(
            listOf("logs-AA:BB-999", "logs-AA:BB-1700000000100"),
            dumpsToPrune(names, "logs-AA:BB-", keep = 2),
        )
        assertEquals(emptyList(), dumpsToPrune(listOf("logs-AA:BB-1"), "logs-AA:BB-", keep = 2))
    }
}
