package coredevices.coreapp.automation.command

import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.BridgeJson
import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.ErrorCode
import coredevices.coreapp.automation.ResultEnvelope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Owns ALL command policy for `execute()` (PLAN §5.5, HLDD-002 §6). The [BridgeService] has already
 * verified the caller (uid → package → pinned cert) and matched the clientToken before delegating
 * here; this class then enforces, in order:
 *
 *  1. parse + validate the [CommandEnvelope] (bad JSON / unknown type ⇒ INVALID_ARGS / UNSUPPORTED_COMMAND)
 *  2. command-tier gate — the command's required tier must be ≤ the caller's granted tier (NOT_AUTHORIZED)
 *  3. dangerous-toggle gate — DANGEROUS commands additionally require the app-wide "dangerous commands"
 *     toggle to be ON (NOT_AUTHORIZED) (PLAN §5.5)
 *  4. per-(token,type) token-bucket rate limit (RATE_LIMITED)
 *  5. dispatch to the [CommandHandler] under a hard [timeoutMs] (TIMEOUT/INTERNAL on failure)
 *
 * Returns a JSON [ResultEnvelope] string (the exact value `execute()` returns to the plugin), never
 * throws. The [grantedTier] and [dangerousEnabled] are read per call so a mid-session revoke/toggle
 * takes effect immediately.
 */
class CommandExecutor(
    private val handler: CommandHandler,
    private val rateLimiter: RateLimiter = RateLimiter(),
    /** Hard ceiling for a single command. < the plugin's 9s and Tasker's 10s (PLAN §3.4 [FIX-B4]). */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val logger = Logger.withTag("AutomationBridge")

    /**
     * @param clientToken the verified token (already matched to the caller package by BridgeService).
     * @param commandJson raw [CommandEnvelope] JSON from the plugin.
     * @param grantedTier the caller's granted command tier (from its ClientRecord).
     * @param dangerousEnabled the app-wide "dangerous commands" toggle.
     * @return a [ResultEnvelope] JSON string.
     */
    suspend fun execute(
        clientToken: String,
        commandJson: String,
        grantedTier: CommandTier,
        dangerousEnabled: Boolean,
    ): String {
        val cmd = runCatching { BridgeJson.json.decodeFromString<CommandEnvelope>(commandJson) }.getOrNull()
            ?: return err(ErrorCode.INVALID_ARGS, "malformed command", null)

        val required = CommandCatalog.tierOf(cmd.type)
            ?: return err(ErrorCode.UNSUPPORTED_COMMAND, "unknown command '${cmd.type}'", cmd.idempotencyKey)

        if (required.rank > grantedTier.rank) {
            return err(ErrorCode.NOT_AUTHORIZED, "command tier '${cmd.type}' exceeds grant", cmd.idempotencyKey)
        }

        if (required == CommandTier.DANGEROUS && !dangerousEnabled) {
            return err(
                ErrorCode.NOT_AUTHORIZED,
                "dangerous commands are disabled in the app",
                cmd.idempotencyKey,
            )
        }

        if (!rateLimiter.tryAcquire("$clientToken:${cmd.type}")) {
            logger.w { "rate limited ${cmd.type} for $clientToken" }
            return err(ErrorCode.RATE_LIMITED, "too many '${cmd.type}' commands", cmd.idempotencyKey)
        }

        val result = try {
            withTimeout(timeoutMs) { handler.handle(cmd) }
        } catch (e: TimeoutCancellationException) {
            logger.w { "command ${cmd.type} timed out" }
            return err(ErrorCode.INTERNAL, "command timed out", cmd.idempotencyKey)
        } catch (e: Exception) {
            logger.w(e) { "command ${cmd.type} failed" }
            return err(ErrorCode.INTERNAL, e.message ?: "command failed", cmd.idempotencyKey)
        }

        return when (result) {
            is CommandResult.Ok ->
                BridgeJson.json.encodeToString(ResultEnvelope.ok(result.data, cmd.idempotencyKey))
            is CommandResult.Failure ->
                err(result.code, result.message, cmd.idempotencyKey)
        }
    }

    private fun err(code: String, message: String, reqId: String?): String =
        BridgeJson.json.encodeToString(ResultEnvelope.error(code, message, reqId))

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 9_000
    }
}
