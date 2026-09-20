package coredevices.coreapp.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.trust.ClientTrustStore
import coredevices.coreapp.automation.trust.PackageInspector
import coredevices.coreapp.automation.trust.hexToBytesOrNull
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps consented automation clients ALIVE while a watch is connected — the app-side half of the
 * "reverse-bind tether". The Pebble app is already alive holding the BLE link; it lends that liveness
 * to its clients so their processes stay up to receive events and drive their host automation (e.g.
 * Tasker) — WITHOUT any client needing a foreground service, persistent notification, or
 * CompanionDeviceManager association.
 *
 * Why this exists: Android aggressively kills a background automation client between events, so watch
 * connect/disconnect/etc. events land on a dead process and never reach the user's automation. Binding
 * a client's exported KEEP_ALIVE service with BIND_AUTO_CREATE (a) revives a client whose process was
 * killed, and (b) keeps it out of the cached/killable state while the connection lasts.
 *
 * Trust (verify on THIS side only): we bind ONLY to clients already approved in the [ClientTrustStore]
 * whose CURRENT signing certificate still matches the pinned one — re-verified on every reconcile,
 * the same allowlist + cert-pin gate the AIDL surface uses. The client's KEEP_ALIVE service exposes
 * nothing sensitive and auto-accepts, by design; all authentication is here.
 *
 * Lifecycle: bind while (≥1 watch connected AND master switch on); unbind otherwise, so clients are
 * never held alive needlessly. Package visibility comes from a generic `<queries>` intent for
 * [ACTION_KEEP_ALIVE] (no hardcoded client package — any consented client that exposes the service).
 */
class ClientTether(
    private val appContext: Context,
    private val libPebble: LibPebble,
    private val trustStore: ClientTrustStore,
    private val inspector: PackageInspector,
) {
    private val logger = Logger.withTag("AutomationBridge")

    /** package -> live ServiceConnection. Guarded by [reconcile]/[stop] being @Synchronized. */
    private val bound = HashMap<String, ServiceConnection>()
    private val retries = HashMap<String, Job>()
    private val failures = HashMap<String, Int>()
    private var wanted = emptySet<String>()
    private var scope: CoroutineScope? = null
    private var generation = 0L

    /** Observe (connected ∧ master ∧ approved-clients) and reconcile the set of bound clients. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            combine(
                libPebble.watches
                    .map { devices -> devices.any { it is CommonConnectedDevice } }
                    .distinctUntilChanged(),
                trustStore.masterEnabled,
                trustStore.clients,
            ) { anyConnected, master, clients ->
                if (anyConnected && master) clients else emptyMap()
            }.collect { desired ->
                // Cert re-verification is a PackageManager binder round-trip per client; do it
                // BEFORE taking the lock so stop() (called from the host app's main thread) never
                // blocks behind a batch of IPC.
                reconcile(desired.values.filter { verify(it) }.map { it.packageName }.toHashSet())
            }
        }
    }

    @Synchronized
    private fun reconcile(wanted: Set<String>) {
        (wanted - this.wanted).forEach { failures.remove(it) }
        this.wanted = wanted
        retries.keys.filter { it !in wanted }.forEach { retries.remove(it)?.cancel() }
        // Unbind anything no longer wanted (disconnect, master off, revoked, or cert no longer matches).
        val it = bound.entries.iterator()
        while (it.hasNext()) {
            val (pkg, conn) = it.next()
            if (pkg !in wanted) {
                runCatching { appContext.unbindService(conn) }
                it.remove()
                logger.i { "tether: unbound $pkg" }
            }
        }
        // Bind anything newly wanted.
        for (pkg in wanted) {
            if (!bound.containsKey(pkg) && retries[pkg]?.isActive != true && (failures[pkg] ?: 0) < 3) bindClient(pkg)
        }
    }

    /** Re-verify the client's current signing cert against the pinned one. */
    private fun verify(record: ClientRecord): Boolean {
        val pinned = record.certSha256.hexToBytesOrNull() ?: return false
        val ok = inspector.hasSigningCert(record.packageName, pinned)
        if (!ok) logger.w { "tether: cert mismatch/absent for ${record.packageName}; not tethering" }
        return ok
    }

    private fun bindClient(pkg: String) {
        val attempt = generation
        val connected = AtomicBoolean(false)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                synchronized(this@ClientTether) {
                    if (attempt != generation || bound[pkg] !== this) return
                    connected.set(true)
                    failures.remove(pkg)
                    retries.remove(pkg)?.cancel()
                }
                logger.i { "tether: bound $pkg ($name)" }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Client process died while bound; the system will rebind (BIND_AUTO_CREATE), reviving it.
                logger.i { "tether: $pkg disconnected (system will rebind)" }
            }
            override fun onBindingDied(name: ComponentName?) = lostBinding(pkg, this, attempt)
            override fun onNullBinding(name: ComponentName?) = lostBinding(pkg, this, attempt)
        }
        bound[pkg] = conn
        val intent = Intent(ACTION_KEEP_ALIVE).setPackage(pkg)
        val ok = try {
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            logger.w(t) { "tether: bindService threw for $pkg" }
            false
        }
        if (ok) {
            if (bound[pkg] === conn && !connected.get()) {
                retries[pkg] = scope?.launch {
                    delay(10_000)
                    lostBinding(pkg, conn, attempt)
                } ?: return
            }
            logger.i { "tether: bindService accepted for $pkg" }
        } else {
            // No KEEP_ALIVE service / not visible / refused. Roll back the (partial) bind.
            lostBinding(pkg, conn, attempt)
            logger.w { "tether: bindService returned false for $pkg (no KEEP_ALIVE service?)" }
        }
    }

    @Synchronized private fun lostBinding(pkg: String, conn: ServiceConnection, attempt: Long) {
        if (attempt != generation || bound[pkg] !== conn) return
        bound.remove(pkg)
        runCatching { appContext.unbindService(conn) }
        retries.remove(pkg)?.cancel()
        if (pkg !in wanted) return
        val failureCount = (failures[pkg] ?: 0) + 1
        failures[pkg] = failureCount
        if (failureCount >= 3) return
        retries[pkg] = scope?.launch {
            delay(2_000L * failureCount)
            val record = trustStore.get(pkg)
            val allowed = record != null && trustStore.masterEnabled.value && verify(record)
            synchronized(this@ClientTether) {
                retries.remove(pkg)
                if (attempt == generation && pkg in wanted && allowed && !bound.containsKey(pkg)) bindClient(pkg)
            }
        } ?: return
    }

    /** Unbind everything (bridge teardown). */
    @Synchronized
    fun stop() {
        generation++
        wanted = emptySet()
        retries.values.forEach { it.cancel() }
        retries.clear()
        failures.clear()
        bound.values.forEach { runCatching { appContext.unbindService(it) } }
        bound.clear()
    }

    companion object {
        /** Action clients expose on their keep-alive service; MUST match the client + <queries> intent. */
        const val ACTION_KEEP_ALIVE = "coredevices.coreapp.automation.KEEP_ALIVE"
    }
}
