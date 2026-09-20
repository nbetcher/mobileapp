package coredevices.coreapp.automation.trust

import android.content.Context
import coredevices.coreapp.automation.BridgeJson
import coredevices.coreapp.automation.ClientRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

@Serializable
data class PendingClient(
    val packageName: String,
    val certSha256Hex: String,
    val installSource: String?,
    val alerted: Boolean = false,
)

@Serializable
private data class Decisions(
    val pending: List<PendingClient> = emptyList(),
    val denied: Map<String, Set<String>> = emptyMap(),
)

class ClientTrustStore(context: Context) : TrustLookup {
    private val prefs = context.getSharedPreferences("automation_trust", Context.MODE_PRIVATE)
    @Volatile var authorityRevision: String = prefs.getString("authority_revision", null)
        ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("authority_revision", it).apply() }
        private set
    private fun reviseAuthority() {
        authorityRevision = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("authority_revision", authorityRevision).apply()
    }
    @Synchronized fun clientAuthorityRevision(pkg: String): String {
        val key = "authority_client_$pkg"
        return prefs.getString(key, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(key, it).apply()
        }
    }
    private fun reviseClientAuthority(pkg: String) {
        prefs.edit().putString("authority_client_$pkg", java.util.UUID.randomUUID().toString()).apply()
    }
    private val _masterEnabled = MutableStateFlow(prefs.getBoolean(KEY_MASTER, false))
    override val masterEnabled = _masterEnabled.asStateFlow()
    private val _dangerousCommandsEnabled = MutableStateFlow(prefs.getBoolean(KEY_DANGEROUS, false))
    val dangerousCommandsEnabled = _dangerousCommandsEnabled.asStateFlow()
    private val _clients = MutableStateFlow(runCatching {
        BridgeJson.json.decodeFromString(SER, prefs.getString(KEY_CLIENTS, null) ?: "{}")
    }.getOrDefault(emptyMap()))
    val clients = _clients.asStateFlow()
    private val decisions = runCatching {
        BridgeJson.json.decodeFromString<Decisions>(prefs.getString(KEY_DECISIONS, null) ?: "{}")
    }.getOrDefault(Decisions())
    private val _pending = MutableStateFlow(decisions.pending)
    val pending = _pending.asStateFlow()
    private val _denied = MutableStateFlow(decisions.denied)
    val denied = _denied.asStateFlow()
    @Volatile var onPolicyChanged: (() -> Unit)? = null

    override fun get(pkg: String): ClientRecord? = _clients.value[pkg]
    override fun isDenied(pkg: String, cert: String): Boolean = cert in (_denied.value[pkg] ?: emptySet())

    @Synchronized fun setMasterEnabled(on: Boolean) {
        if (_masterEnabled.value == on) return
        reviseAuthority()
        prefs.edit().putBoolean(KEY_MASTER, on).apply()
        _masterEnabled.value = on
        onPolicyChanged?.invoke()
    }
    @Synchronized fun setDangerousCommandsEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_DANGEROUS, on).apply()
        _dangerousCommandsEnabled.value = on
    }
    @Synchronized fun addPending(p: PendingClient): Boolean {
        if (isDenied(p.packageName, p.certSha256Hex)) return false
        if (_clients.value[p.packageName]?.certSha256 == p.certSha256Hex) return false
        if (_pending.value.any { it.packageName == p.packageName && it.certSha256Hex == p.certSha256Hex }) return false
        _pending.value = _pending.value.filterNot { it.packageName == p.packageName } + p
        persist()
        return true
    }
    @Synchronized fun markAlerted(pkg: String, cert: String) {
        _pending.value = _pending.value.map { if (it.packageName == pkg && it.certSha256Hex == cert) it.copy(alerted = true) else it }
        persist()
    }
    @Synchronized fun approve(record: ClientRecord) {
        reviseClientAuthority(record.packageName)
        _clients.value = _clients.value + (record.packageName to record)
        _denied.value = _denied.value - record.packageName
        _pending.value = _pending.value.filterNot { it.packageName == record.packageName }
        persist()
        onPolicyChanged?.invoke()
    }
    @Synchronized fun deny(pkg: String) {
        reviseClientAuthority(pkg)
        val cert = _pending.value.firstOrNull { it.packageName == pkg }?.certSha256Hex ?: _clients.value[pkg]?.certSha256
        if (cert != null) _denied.value = _denied.value + (pkg to ((_denied.value[pkg] ?: emptySet()) + cert))
        _pending.value = _pending.value.filterNot { it.packageName == pkg }
        _clients.value = _clients.value - pkg
        persist()
        onPolicyChanged?.invoke()
    }
    @Synchronized fun revoke(pkg: String) = deny(pkg)
    @Synchronized fun reconsider(pkg: String) {
        reviseClientAuthority(pkg)
        _denied.value = _denied.value - pkg
        persist()
    }
    private fun persist() {
        prefs.edit().putString(KEY_CLIENTS, BridgeJson.json.encodeToString(SER, _clients.value))
            .putString(KEY_DECISIONS, BridgeJson.json.encodeToString(Decisions(_pending.value, _denied.value))).apply()
    }
    companion object {
        private const val KEY_MASTER = "master_enabled"
        private const val KEY_DANGEROUS = "dangerous_commands_enabled"
        private const val KEY_CLIENTS = "clients"
        private const val KEY_DECISIONS = "decisions"
        private val SER = MapSerializer(String.serializer(), ClientRecord.serializer())
    }
}
