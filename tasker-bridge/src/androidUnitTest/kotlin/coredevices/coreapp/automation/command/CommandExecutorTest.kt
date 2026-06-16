package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.BridgeJson
import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.ErrorCode
import coredevices.coreapp.automation.ResultEnvelope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Policy tests for the command path: allowlist, tier gating, dangerous toggle, rate limiting, and the
 * success/error envelope shape. The handler is faked so these are pure-logic (no Android / no watch).
 */
class CommandExecutorTest {

    private class FakeHandler(
        val result: CommandResult = CommandResult.Ok(mapOf("did" to "it")),
        var calls: Int = 0,
    ) : CommandHandler {
        override suspend fun handle(command: CommandEnvelope): CommandResult {
            calls++
            return result
        }
    }

    private fun cmd(type: String, key: String? = null): String =
        BridgeJson.json.encodeToString(CommandEnvelope(type = type, idempotencyKey = key))

    private fun decode(json: String): ResultEnvelope =
        BridgeJson.json.decodeFromString(ResultEnvelope.serializer(), json)

    @Test
    fun normalCommand_succeeds_andCarriesData() = runTest {
        val handler = FakeHandler()
        val exec = CommandExecutor(handler)
        val out = decode(exec.execute("t1", cmd(CommandCatalog.WATCH_LAUNCH_APP, "k1"), CommandTier.NORMAL, false))
        assertTrue(out.ok)
        assertEquals("it", out.data?.get("did"))
        assertEquals("k1", out.reqId)
        assertEquals(1, handler.calls)
    }

    @Test
    fun unknownType_isUnsupported_andNeverDispatched() = runTest {
        val handler = FakeHandler()
        val exec = CommandExecutor(handler)
        val out = decode(exec.execute("t1", cmd("watch.selfDestruct"), CommandTier.DANGEROUS, true))
        assertFalse(out.ok)
        assertEquals(ErrorCode.UNSUPPORTED_COMMAND, out.error?.code)
        assertEquals(0, handler.calls)
    }

    @Test
    fun sensitiveCommand_withOnlyNormalGrant_isNotAuthorized() = runTest {
        val handler = FakeHandler()
        val exec = CommandExecutor(handler)
        val out = decode(exec.execute("t1", cmd(CommandCatalog.WATCH_DISCONNECT), CommandTier.NORMAL, false))
        assertFalse(out.ok)
        assertEquals(ErrorCode.NOT_AUTHORIZED, out.error?.code)
        assertEquals(0, handler.calls)
    }

    @Test
    fun dangerousCommand_withGrantButToggleOff_isNotAuthorized() = runTest {
        val handler = FakeHandler()
        val exec = CommandExecutor(handler)
        val out = decode(
            exec.execute("t1", cmd(CommandCatalog.DEV_TOGGLE_CONNECTION), CommandTier.DANGEROUS, false),
        )
        assertFalse(out.ok)
        assertEquals(ErrorCode.NOT_AUTHORIZED, out.error?.code)
        assertEquals(0, handler.calls)
    }

    @Test
    fun dangerousCommand_withGrantAndToggleOn_runs() = runTest {
        val handler = FakeHandler()
        val exec = CommandExecutor(handler)
        val out = decode(
            exec.execute("t1", cmd(CommandCatalog.DEV_TOGGLE_CONNECTION), CommandTier.DANGEROUS, true),
        )
        assertTrue(out.ok)
        assertEquals(1, handler.calls)
    }

    @Test
    fun handlerFailure_mapsToErrorEnvelope() = runTest {
        val handler = FakeHandler(result = CommandResult.Failure(ErrorCode.INVALID_ARGS, "no watch"))
        val exec = CommandExecutor(handler)
        val out = decode(exec.execute("t1", cmd(CommandCatalog.SYSTEM_PING), CommandTier.NORMAL, false))
        assertFalse(out.ok)
        assertEquals(ErrorCode.INVALID_ARGS, out.error?.code)
    }

    @Test
    fun rateLimit_exhausts_perTokenPerType() = runTest {
        val handler = FakeHandler()
        // capacity 2, no refill within the test window.
        val exec = CommandExecutor(handler, RateLimiter(capacity = 2, refillPerMinute = 0, nowMs = { 0L }))
        assertTrue(decode(exec.execute("t1", cmd(CommandCatalog.SYSTEM_PING), CommandTier.NORMAL, false)).ok)
        assertTrue(decode(exec.execute("t1", cmd(CommandCatalog.SYSTEM_PING), CommandTier.NORMAL, false)).ok)
        val third = decode(exec.execute("t1", cmd(CommandCatalog.SYSTEM_PING), CommandTier.NORMAL, false))
        assertFalse(third.ok)
        assertEquals(ErrorCode.RATE_LIMITED, third.error?.code)
        // A different type for the same token has its own bucket.
        assertTrue(decode(exec.execute("t1", cmd(CommandCatalog.WATCH_GET_INFO), CommandTier.NORMAL, false)).ok)
    }

    @Test
    fun malformedJson_isInvalidArgs() = runTest {
        val exec = CommandExecutor(FakeHandler())
        val out = decode(exec.execute("t1", "{not json", CommandTier.NORMAL, false))
        assertFalse(out.ok)
        assertEquals(ErrorCode.INVALID_ARGS, out.error?.code)
    }
}
