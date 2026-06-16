package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.CommandEnvelope

/**
 * The actuation seam: turns an allowlisted, already-authorized [CommandEnvelope] into LibPebble calls
 * (HOOKS.md §3). The [CommandExecutor] owns ALL policy (allowlist, tier gating, rate limiting,
 * dangerous toggle, timeout) — a handler only does the watch/app work and never re-checks trust.
 *
 * Implementations return [CommandResult.Ok] with a flat string result map (becomes
 * `ResultEnvelope.data`), or [CommandResult.Failure] with an [coredevices.coreapp.automation.ErrorCode]
 * for domain failures (e.g. no connected watch, bad UUID). Throwing is also tolerated by the executor
 * (mapped to INTERNAL), but returning a typed failure is preferred.
 */
interface CommandHandler {
    suspend fun handle(command: CommandEnvelope): CommandResult
}

sealed interface CommandResult {
    data class Ok(val data: Map<String, String> = emptyMap()) : CommandResult
    data class Failure(val code: String, val message: String) : CommandResult
}

/**
 * No-op handler used when the host app has not (yet) wired a LibPebble-backed handler. Every command
 * resolves to UNSUPPORTED_COMMAND so the module compiles and runs standalone (e.g. in tests) without
 * a watch stack. The real [coredevices.coreapp.automation.command.LibPebbleCommandHandler] replaces it
 * via Koin in the host app.
 */
object UnsupportedCommandHandler : CommandHandler {
    override suspend fun handle(command: CommandEnvelope): CommandResult =
        CommandResult.Failure(
            coredevices.coreapp.automation.ErrorCode.UNSUPPORTED_COMMAND,
            "command '${command.type}' is not implemented on this build",
        )
}
