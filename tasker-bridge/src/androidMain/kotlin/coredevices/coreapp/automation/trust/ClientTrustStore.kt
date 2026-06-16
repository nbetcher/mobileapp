package coredevices.coreapp.automation.trust

import android.content.Context
import android.content.SharedPreferences
import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.BridgeJson
import coredevices.coreapp.automation.ClientRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** A not-yet-approved client awaiting user consent (HLDD-001 §6). */
data class PendingClient(
    val packageName: String,
    val certSha256Hex: String,
    val installSource: String?,
)

/**
 * Durable allowlist of approved automation clients + the master enable flag (HLDD-001 §6, §10).
 * Persisted in SharedPreferences as JSON. No event content is ever stored here — only grants.
 */
class ClientTrustStore(context: Context) : TrustLookup {
    private val logger = Logger.withTag("AutomationBridge")
    private val prefs: SharedPreferences =
        context.getSharedPreferences("automation_trust", Context.MODE_PRIVATE)

    private val _masterEnabled = MutableStateFlow(prefs.getBoolean(KEY_MASTER, false))
    override val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    // App-wide "dangerous commands" toggle (PLAN §5.5). Default OFF; gates DANGEROUS-tier commands
    // (e.g. dev connection) in addition to the per-client tier grant.
    private val _dangerousCommandsEnabled = MutableStateFlow(prefs.getBoolean(KEY_DANGEROUS, false))
    val dangerousCommandsEnabled: StateFlow<Boolean> = _dangerousCommandsEnabled.asStateFlow()

    private val _clients = MutableStateFlow(load())
    val clients: StateFlow<Map<String, ClientRecord>> = _clients.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingClient>>(emptyList())
    val pending: StateFlow<List<PendingClient>> = _pending.asStateFlow()

    override fun get(pkg: String): ClientRecord? = _clients.value[pkg]

    fun setMasterEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_MASTER, on).apply()
        _masterEnabled.value = on
    }

    fun setDangerousCommandsEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_DANGEROUS, on).apply()
        _dangerousCommandsEnabled.value = on
    }

    fun addPending(p: PendingClient) {
        if (_pending.value.any { it.packageName == p.packageName }) return
        _pending.value = _pending.value + p
    }

    fun approve(record: ClientRecord) {
        _clients.value = _clients.value + (record.packageName to record)
        persist()
        _pending.value = _pending.value.filterNot { it.packageName == record.packageName }
    }

    fun deny(pkg: String) {
        _pending.value = _pending.value.filterNot { it.packageName == pkg }
    }

    fun revoke(pkg: String) {
        _clients.value = _clients.value - pkg
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY_CLIENTS, BridgeJson.json.encodeToString(SER, _clients.value)).apply()
    }

    private fun load(): Map<String, ClientRecord> {
        val str = prefs.getString(KEY_CLIENTS, null) ?: return emptyMap()
        return try {
            BridgeJson.json.decodeFromString(SER, str)
        } catch (e: Exception) {
            logger.w(e) { "trust store load failed; starting empty" }
            emptyMap()
        }
    }

    companion object {
        private const val KEY_MASTER = "master_enabled"
        private const val KEY_DANGEROUS = "dangerous_commands_enabled"
        private const val KEY_CLIENTS = "clients"
        private val SER = MapSerializer(String.serializer(), ClientRecord.serializer())
    }
}
