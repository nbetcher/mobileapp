package io.rebble.libpebblecommon.automation

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Passive observation by default. Explicit exclusive ownership is only honored for apps without
 * a declared companion/PKJS; other apps remain observable and keep their own ACK ownership. */
object AutomationAppMessageHook {
    private data class Subscription(val owner: String, val uuid: String, val address: String?, val exclusive: Boolean)
    private val subscriptions = atomic<Set<Subscription>>(emptySet())
    private val mutex = Mutex()
    private val receiver = atomic<((String, String, Int, Map<Int, Any>) -> Boolean)?>(null)
    private val authorization = atomic<((String) -> Boolean)?>(null)

    var onReceived: ((String, String, Int, Map<Int, Any>) -> Boolean)?
        get() = receiver.value
        set(value) { receiver.value = value }
    /** Must check current trust/master/apps grant synchronously; absent means deny. */
    var ownerAllowed: ((String) -> Boolean)?
        get() = authorization.value
        set(value) { authorization.value = value }

    suspend fun subscribe(owner: String, appUuid: String, watchAddress: String? = null, exclusive: Boolean = false) {
        require(owner.isNotBlank())
        val entry = Subscription(owner, Uuid.parse(appUuid).toString(), watchAddress, exclusive)
        mutex.withLock {
            subscriptions.value = subscriptions.value.filterNot {
                it.owner == owner && it.uuid == entry.uuid && it.address == watchAddress
            }.toSet() + entry
        }
    }
    data class DesiredSubscription(val uuid: String, val address: String?, val exclusive: Boolean)
    /** Replace one owner's complete configuration in a single publication; other owners survive. */
    suspend fun replaceOwner(owner: String, desired: List<DesiredSubscription>) {
        require(owner.isNotBlank())
        val replacements = desired.map { Subscription(owner, Uuid.parse(it.uuid).toString(), it.address, it.exclusive) }.toSet()
        mutex.withLock { subscriptions.value = subscriptions.value.filterNot { it.owner == owner }.toSet() + replacements }
    }
    suspend fun unsubscribe(owner: String, appUuid: String, watchAddress: String? = null) {
        val uuid = Uuid.parse(appUuid).toString()
        mutex.withLock {
            subscriptions.value = subscriptions.value.filterNot {
                it.owner == owner && it.uuid == uuid && it.address == watchAddress
            }.toSet()
        }
    }
    suspend fun clearOwner(owner: String) {
        mutex.withLock { subscriptions.value = subscriptions.value.filterNot { it.owner == owner }.toSet() }
    }
    suspend fun clear() {
        mutex.withLock { subscriptions.value = emptySet() }
    }
    fun hasAuthorizedSubscription(address: String, appUuid: String): Boolean =
        subscriptions.value.any { it.uuid == appUuid && (it.address == null || it.address == address) &&
            runCatching { authorization.value?.invoke(it.owner) == true }.getOrDefault(false) }

    fun hasAuthorizedOwnership(address: String, appUuid: String): Boolean =
        subscriptions.value.any { it.exclusive && it.uuid == appUuid && (it.address == null || it.address == address) &&
            runCatching { authorization.value?.invoke(it.owner) == true }.getOrDefault(false) }

    fun deliver(address: String, uuid: String, transactionId: Int, data: Map<Int, Any>): Boolean {
        if (!hasAuthorizedSubscription(address, uuid)) return false
        return runCatching { receiver.value?.invoke(address, uuid, transactionId, data) == true }.getOrDefault(false)
    }
}
