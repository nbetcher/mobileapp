package coredevices.coreapp.automation.events

import android.os.IBinder
import android.os.RemoteException
import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.BridgeJson
import coredevices.coreapp.automation.EventBatch
import coredevices.coreapp.automation.IBridgeEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds registered client listeners and fans out events over the verified binding (HLDD-001 §8).
 * Death-linked: a client whose process dies is dropped automatically.
 */
class ListenerHub(
    private val dispatcher: EventDispatcher,
) {
    private val logger = Logger.withTag("AutomationBridge")

    private class Entry(val listener: IBridgeEventListener, val recipient: IBinder.DeathRecipient)

    private val listeners = ConcurrentHashMap<String, Entry>() // clientToken -> entry

    fun start(scope: CoroutineScope) {
        scope.launch {
            dispatcher.events.collect { envelope ->
                if (listeners.isEmpty()) return@collect
                // Anything escaping this block cancels the collector permanently: nothing restarts it
                // and clients can't detect the silence, because their binding stays up.
                runCatching {
                    val json = BridgeJson.json.encodeToString(
                        EventBatch(bootId = dispatcher.bootId, events = listOf(envelope)),
                    )
                    for ((token, entry) in listeners) {
                        try {
                            entry.listener.onEvents(json)
                        } catch (t: Throwable) {
                            // Only a genuinely dead binder is dropped. A transient failure (an oversized
                            // transaction, a frozen process) would otherwise deregister a live client
                            // for good — there is no re-registration path once its binding stays up.
                            if (entry.listener.asBinder().isBinderAlive) {
                                logger.w(t) { "listener $token delivery failed; keeping" }
                            } else {
                                logger.w(t) { "listener $token dead; dropping" }
                                remove(token)
                            }
                        }
                    }
                }.onFailure { logger.e(it) { "event fan-out failed; collector kept alive" } }
            }
        }
    }

    fun register(token: String, listener: IBridgeEventListener, fromSeq: Long) {
        val recipient = IBinder.DeathRecipient { remove(token) }
        try {
            listener.asBinder().linkToDeath(recipient, 0)
        } catch (e: RemoteException) {
            return
        }
        // Register before replaying: replaying first leaves a window where a live event is fanned out
        // to nobody, and a client that dies during replay never lands in the map at all. Replayed and
        // live events can now interleave, which is safe — the client dedupes on (bootId, seq).
        listeners[token] = Entry(listener, recipient)
        val missed = dispatcher.since(fromSeq)
        if (missed.isNotEmpty()) {
            runCatching {
                listener.onEvents(BridgeJson.json.encodeToString(EventBatch(bootId = dispatcher.bootId, events = missed)))
            }
        }
        logger.i { "listener registered token=$token (replayed ${missed.size})" }
    }

    fun unregister(token: String) = remove(token)

    private fun remove(token: String) {
        val entry = listeners.remove(token) ?: return
        runCatching { entry.listener.asBinder().unlinkToDeath(entry.recipient, 0) }
    }
}
