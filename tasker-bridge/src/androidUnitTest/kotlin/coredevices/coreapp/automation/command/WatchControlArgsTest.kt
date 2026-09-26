package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.ClientRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchControlArgsTest {
    @Test fun buttonsAndDirectionsMapToPebbleOsIds() {
        assertEquals(listOf(0, 1, 2, 3, 2), listOf("back", "UP", " select ", "down", "middle").map(CommandArgs::buttonId))
        assertNull(CommandArgs.buttonId("left"))
        assertNull(CommandArgs.buttonId(null))
        assertEquals(listOf(0, 1, 2, 3), listOf("up", "down", "Left", "right").map(CommandArgs::swipeDirection))
        assertNull(CommandArgs.swipeDirection("back"))
    }

    @Test fun boundedIntDefaultsWhenAbsentAndRejectsOutOfRange() {
        assertEquals(7, CommandArgs.boundedInt(null, 1..10, 7))
        assertEquals(7, CommandArgs.boundedInt(" ", 1..10, 7))
        assertEquals(10, CommandArgs.boundedInt("10", 1..10, 7))
        assertNull(CommandArgs.boundedInt("11", 1..10, 7))
        assertNull(CommandArgs.boundedInt("0", 1..10, 7))
        assertNull(CommandArgs.boundedInt("two", 1..10, 7))
    }

    @Test fun extremeGrantNeedsAcceptedDisclaimer() {
        val record = ClientRecord("pkg", "cert", "Plugin", tier = CommandTier.GRANT_EXTREMELY_DANGEROUS, approvedAtMs = 1)
        assertEquals(CommandTier.EXTREMELY_DANGEROUS, CommandTier.fromGrant("extremely_dangerous"))
        assertEquals(CommandTier.DANGEROUS, CommandTier.forClient(record))
        assertEquals(CommandTier.EXTREMELY_DANGEROUS, CommandTier.forClient(record.copy(extremeDisclaimerAcceptedAtMs = 5)))
        assertEquals(CommandTier.SENSITIVE, CommandTier.forClient(record.copy(tier = "sensitive")))
    }

    @Test fun cooldownReservesKeyThenAppliesOutcomeDuration() {
        var now = 0L
        val cooldowns = Cooldowns { now }
        assertEquals(0, cooldowns.tryAcquire("a", 1_000))
        assertEquals(1_000, cooldowns.tryAcquire("a", 1_000))
        assertEquals(0, cooldowns.tryAcquire("b", 1_000))
        cooldowns.finish("a", 5_000)
        now = 4_999
        assertEquals(1, cooldowns.tryAcquire("a", 1_000))
        now = 5_000
        assertEquals(0, cooldowns.tryAcquire("a", 1_000))
    }
}
