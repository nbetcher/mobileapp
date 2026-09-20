package coredevices.coreapp.automation.events

import android.os.IBinder
import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.BridgeJson
import coredevices.coreapp.automation.IBridgeEventListener
import coredevices.coreapp.automation.ResultEnvelope
import coredevices.coreapp.automation.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

class ListenerHub(private val dispatcher: EventDispatcher) {
    private val logger = Logger.withTag("AutomationBridge")
    private class Entry(
        val listener: IBridgeEventListener,
        val recipient: IBinder.DeathRecipient,
        val authorized: () -> Boolean,
        val closed: () -> Unit,
        val owner: String?,
        val reason: () -> String,
    ) {
        @Volatile var job: Job? = null
    }
    private val listeners = ConcurrentHashMap<String, Entry>()
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) { this.scope = scope }
    fun registered(token: String): Boolean = listeners.containsKey(token)
    fun hasAuthorizedOwner(owner: String): Boolean = listeners.values.any { it.owner == owner && it.authorized() }

    fun register(
        token: String,
        listener: IBridgeEventListener,
        fromSeq: Long,
        authorized: () -> Boolean = { true },
        permitted: (EventEnvelope) -> Boolean = { true },
        closed: () -> Unit = {},
        ownerPackage: String? = null,
        transform: (EventEnvelope) -> EventEnvelope? = { it },
        goodbyeReason: () -> String = { BridgeJson.json.encodeToString(ResultEnvelope.error(ErrorCode.NOT_AUTHORIZED, "Access changed in the Pebble app")) },
    ) {
        val owner = scope ?: return
        listeners[token]?.let { remove(token, it, false, false) }
        lateinit var entry: Entry
        val recipient = IBinder.DeathRecipient { remove(token, entry, false) }
        entry = Entry(listener, recipient, authorized, closed, ownerPackage, goodbyeReason)
        listeners[token] = entry
        try {
            listener.asBinder().linkToDeath(recipient, 0)
        } catch (t: Throwable) {
            remove(token, entry, false)
            return
        }
        val worker = owner.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var cursor = fromSeq
            dispatcher.changes.collect {
                var attempts = 0
                while (isActive && listeners[token] === entry) {
                    if (!authorized()) {
                        remove(token, entry, true)
                        break
                    }
                    val batch = dispatcher.snapshot(cursor, transform, permitted).copy(subscriptionToken = token)
                    try {
                        // One worker owns replay and live delivery for this registration.
                        listener.onEvents(BridgeJson.json.encodeToString(batch))
                        cursor = batch.cursor ?: cursor
                        if (batch.more) { attempts = 0; continue }
                        break
                    } catch (t: Throwable) {
                        logger.w(t) { "event delivery failed" }
                        if (!listener.asBinder().isBinderAlive || ++attempts >= 3) {
                            remove(token, entry, true)
                            break
                        }
                        delay(100L * attempts)
                    }
                }
            }
        }
        entry.job = worker
        if (listeners[token] === entry) worker.start() else worker.cancel()
    }

    fun revalidate() {
        for ((token, entry) in listeners) if (!entry.authorized()) remove(token, entry, true)
    }
    fun invalidateAll() { listeners.keys.toList().forEach(::revoke) }

    fun unregister(token: String) { listeners[token]?.let { remove(token, it, false) } }
    fun revoke(token: String) { listeners[token]?.let { remove(token, it, true) } }

    private fun remove(token: String, entry: Entry, goodbye: Boolean, notifyClosed: Boolean = true) {
        if (!listeners.remove(token, entry)) return
        entry.job?.cancel()
        runCatching { entry.listener.asBinder().unlinkToDeath(entry.recipient, 0) }
        if (notifyClosed) entry.closed()
        if (goodbye) {
            val reason = entry.reason()
            scope?.launch(Dispatchers.IO) {
                runCatching { entry.listener.onBridgeGoodbye(reason) }
            }
        }
    }
}
