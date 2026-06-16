package coredevices.coreapp.automation

import kotlinx.serialization.Serializable

/**
 * A user-approved automation client (HLDD-001 §6 ClientTrustStore record). Persisted; pinned at
 * approval time. Identity = (packageName, certSha256); trust is signer-independent (ADR-003).
 */
@Serializable
data class ClientRecord(
    val packageName: String,
    /** Lowercase hex SHA-256 of the signing certificate, pinned at approval (HLDD-002 §5). */
    val certSha256: String,
    val label: String,
    /** Granted event categories (e.g. connectivity, apps). Content/health are opt-in (HLDD-001 §10). */
    val categories: Set<String> = emptySet(),
    /** Granted command tier: normal | sensitive | dangerous (HLDD-002 §6.2). */
    val tier: String = "normal",
    val approvedAtMs: Long,
)

/** Grants echoed to a client at handshake (HLDD-002 §4.2). */
@Serializable
data class Grants(
    val categories: List<String>,
    val tier: String,
    val contentRedacted: Boolean,
)
