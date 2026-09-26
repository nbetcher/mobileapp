package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.BridgeJson
import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.ErrorCode
import coredevices.coreapp.automation.ResultEnvelope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.currentTime
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Policy tests for the command path: allowlist, tier gating, dangerous toggle, rate limiting, and the
 * success/error envelope shape. The handler is faked so these are pure-logic (no Android / no watch).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommandExecutorTest {
    @Test fun unrecognizedPersistedGrantDoesNotAuthorizeNormalActuation() = runTest {
        for (grant in listOf("", "none", "observe", "corrupted")) {
            val handler = FakeHandler()
            val result = decode(CommandExecutor(handler).execute("t", cmd(CommandCatalog.WATCH_LAUNCH_APP),
                CommandTier.fromGrant(grant), true))
            assertFalse(result.ok)
            assertEquals(0, handler.calls)
        }
    }
    @Test fun everyCatalogCommandChecksEveryGrantAndDangerousToggle() = runTest {
        for ((type, required) in CommandCatalog.tiers) for (grant in CommandTier.entries) for (dangerous in listOf(false, true)) {
            val handler = FakeHandler()
            val allowed = grant.rank >= required.rank && (required.rank < CommandTier.DANGEROUS.rank || dangerous)
            val result = decode(CommandExecutor(handler).execute("client", cmd(type, "request"), grant, dangerous))
            assertEquals(allowed, result.ok, "$type $grant $dangerous")
            assertEquals(if (allowed) 1 else 0, handler.calls)
            assertEquals("request", result.reqId)
            if (!allowed) assertEquals(ErrorCode.COMMAND_NOT_AUTHORIZED, result.error?.code)
        }
        assertEquals(CommandCatalog.tiers.keys, CommandCatalog.types)
        assertTrue(CommandCatalog.globalTypes.all { it in CommandCatalog.types })
    }

    @Test fun timeoutIsBoundedAndDistinctFromInternalFailure() = runTest {
        var cancelled = false
        val handler = object : CommandHandler {
            override suspend fun handle(command: CommandEnvelope): CommandResult {
                try { delay(20_000); return CommandResult.Ok() } finally { cancelled = true }
            }
        }
        val start = currentTime
        val result = decode(CommandExecutor(handler).execute("client", cmd(CommandCatalog.SYSTEM_PING, "r"), CommandTier.NORMAL, false))
        assertEquals(ErrorCode.TIMEOUT, result.error?.code)
        assertEquals("r", result.reqId)
        assertEquals(6_000L, currentTime - start)
        assertTrue(cancelled)
        assertFailsWith<IllegalArgumentException> { CommandExecutor(handler, timeoutMs = 6_001) }
    }

    @Test fun cancellationIsPropagatedAndErrorsCarryRequestId() = runTest {
        val handler = object : CommandHandler {
            override suspend fun handle(command: CommandEnvelope): CommandResult = throw CancellationException("stopped")
        }
        assertFailsWith<CancellationException> { CommandExecutor(handler).execute("t", cmd(CommandCatalog.SYSTEM_PING), CommandTier.NORMAL, false) }
        val failing = object : CommandHandler {
            override suspend fun handle(command: CommandEnvelope): CommandResult = error("backend failure")
        }
        val result = decode(CommandExecutor(failing).execute("t", cmd(CommandCatalog.SYSTEM_PING, "req"), CommandTier.NORMAL, false))
        assertEquals(ErrorCode.INTERNAL, result.error?.code)
        assertEquals("req", result.reqId)
    }

    @Test fun validatesEnvelopeBeforeDispatch() = runTest {
        val handler = FakeHandler()
        for ((raw, code) in listOf(
            """{"v":999,"type":"system.ping"}""" to ErrorCode.UNSUPPORTED_VERSION,
            """{"kind":"hello","type":"system.ping"}""" to ErrorCode.INVALID_ARGS,
        )) assertEquals(code, decode(CommandExecutor(handler).execute("t", raw, CommandTier.DANGEROUS, true)).error?.code)
        assertEquals(0, handler.calls)
    }

    @Test fun stablePackageRateLimitSurvivesTokenReplacementAndPassesIdentitySeparately() = runTest {
        val identities = mutableListOf<String>()
        val handler = object : CommandHandler {
            override suspend fun handle(command: CommandEnvelope): CommandResult = error("identity required")
            override suspend fun handle(command: CommandEnvelope, clientIdentity: String): CommandResult {
                identities += clientIdentity
                return CommandResult.Ok()
            }
        }
        val executor = CommandExecutor(handler, RateLimiter(capacity = 1, refillPerMinute = 0))
        suspend fun call(token: String, owner: String) = decode(executor.execute(token, cmd(CommandCatalog.SYSTEM_PING), CommandTier.NORMAL, false, owner))
        assertTrue(call("old", "package.a").ok)
        assertEquals(ErrorCode.RATE_LIMITED, call("new", "package.a").error?.code)
        assertTrue(call("other", "package.b").ok)
        executor.clearClientRateLimits("package.a")
        assertTrue(call("third", "package.a").ok)
        assertEquals(ErrorCode.RATE_LIMITED, call("other", "package.b").error?.code)
        assertEquals(listOf("package.a", "package.b", "package.a"), identities)
    }

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
        assertEquals(ErrorCode.COMMAND_NOT_AUTHORIZED, out.error?.code)
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
        assertEquals(ErrorCode.COMMAND_NOT_AUTHORIZED, out.error?.code)
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
