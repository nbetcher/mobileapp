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
                val json = BridgeJson.json.encodeToString(
                    EventBatch(bootId = dispatcher.bootId, events = listOf(envelope)),
                )
                for ((token, entry) in listeners) {
                    try {
                        entry.listener.onEvents(json)
                    } catch (e: RemoteException) {
                        logger.w(e) { "listener $token dead; dropping" }
                        remove(token)
                    }
                }
            }
        }
    }

    fun register(token: String, listener: IBridgeEventListener, fromSeq: Long) {
        val missed = dispatcher.since(fromSeq)
        if (missed.isNotEmpty()) {
            runCatching {
                listener.onEvents(BridgeJson.json.encodeToString(EventBatch(bootId = dispatcher.bootId, events = missed)))
            }
        }
        val recipient = IBinder.DeathRecipient { remove(token) }
        try {
            listener.asBinder().linkToDeath(recipient, 0)
        } catch (e: RemoteException) {
            return
        }
        listeners[token] = Entry(listener, recipient)
        logger.i { "listener registered token=$token (replayed ${missed.size})" }
    }

    fun unregister(token: String) = remove(token)

    private fun remove(token: String) {
        val entry = listeners.remove(token) ?: return
        runCatching { entry.listener.asBinder().unlinkToDeath(entry.recipient, 0) }
    }
}
