package coredevices.coreapp.automation

import coredevices.coreapp.automation.events.EventEnvelope
import coredevices.coreapp.automation.events.WatchRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON for the bridge IPC contract (HLDD-002). One instance, lenient to unknown keys. */
object BridgeJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

/** Error codes (HLDD-002 §6.3). */
object ErrorCode {
    const val NOT_AUTHORIZED = "NOT_AUTHORIZED"
    const val CONSENT_PENDING = "CONSENT_PENDING"
    const val CERT_MISMATCH = "CERT_MISMATCH"
    const val CATEGORY_DISABLED = "CATEGORY_DISABLED"
    const val UNSUPPORTED_COMMAND = "UNSUPPORTED_COMMAND"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val INVALID_ARGS = "INVALID_ARGS"
    const val INTERNAL = "INTERNAL"

    /** A command was rejected by per-type rate limiting (HLDD-002 §6, PLAN §5.5). */
    const val RATE_LIMITED = "RATE_LIMITED"
}

/** Client → bridge identification at handshake (HLDD-002 §4.2). */
@Serializable
data class ClientHello(
    val v: Int = 1,
    val kind: String = "hello",
    val clientLabel: String = "",
    val clientProtocol: Int = 1,
    val wants: List<String> = emptyList(),
)

/** Bridge → client handshake response (HLDD-002 §4.2). */
@Serializable
data class BridgeHello(
    val v: Int = 1,
    val kind: String = "hello",
    val bootId: String,
    val protocolVersion: Int = 1,
    val capabilities: List<String>,
    val grants: Grants,
    val latestSeq: Long,
    val appVersion: String,
    val clientToken: String,
)

@Serializable
data class ErrorBody(val code: String, val message: String)

/**
 * Client → bridge command for `execute()` (HLDD-002 §4.5, PLAN §6). All payloads are flat strings
 * (variable-friendly, symmetric with [EventEnvelope.data]); [type] is a closed allowlist enforced by
 * the bridge's CommandExecutor — never reflected. [watch] is an optional serial/address selector;
 * absent ⇒ the active/only connected watch.
 */
@Serializable
data class CommandEnvelope(
    val v: Int = 1,
    val kind: String = "command",
    val type: String,
    val watch: String? = null,
    val args: Map<String, String> = emptyMap(),
    /** Optional client-supplied dedupe key, echoed back as [ResultEnvelope.reqId]. */
    val idempotencyKey: String? = null,
)

/**
 * Generic ok/error result (HLDD-002 §4.5). [data]/[reqId] are append-only additions for `execute()`
 * results — both defaulted so existing [error] callers and decoders are unaffected, and older clients
 * (ignoreUnknownKeys) tolerate their presence.
 */
@Serializable
data class ResultEnvelope(
    val v: Int = 1,
    val kind: String = "result",
    val ok: Boolean,
    val error: ErrorBody? = null,
    /** Command result payload (flat strings, matching [EventEnvelope.data]). Null on plain ok/error. */
    val data: Map<String, String>? = null,
    /** Echo of [CommandEnvelope.idempotencyKey], when supplied. */
    val reqId: String? = null,
) {
    companion object {
        fun error(code: String, message: String): ResultEnvelope =
            ResultEnvelope(ok = false, error = ErrorBody(code, message))

        fun error(code: String, message: String, reqId: String?): ResultEnvelope =
            ResultEnvelope(ok = false, error = ErrorBody(code, message), reqId = reqId)

        fun ok(data: Map<String, String> = emptyMap(), reqId: String? = null): ResultEnvelope =
            ResultEnvelope(ok = true, data = data, reqId = reqId)
    }
}

/** A batch of events for recovery / push (HLDD-002 §4.4). */
@Serializable
data class EventBatch(
    val v: Int = 1,
    val kind: String = "batch",
    val bootId: String,
    val events: List<EventEnvelope>,
    val more: Boolean = false,
)

@Serializable
data class StateData(val watches: List<WatchRef>)

/** State query response (HLDD-002 §4.6). */
@Serializable
data class StateResult(
    val v: Int = 1,
    val kind: String = "state",
    val data: StateData,
)
