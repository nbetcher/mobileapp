package coredevices.coreapp.automation

import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.events.ConnectivityCollector
import coredevices.coreapp.automation.events.EventDispatcher
import coredevices.coreapp.automation.events.ListenerHub
import coredevices.coreapp.automation.events.AppMessageCollector
import coredevices.coreapp.automation.events.NotificationCollector
import coredevices.coreapp.automation.events.PerWatchCollector
import coredevices.coreapp.automation.events.SystemEventCollector
import coredevices.coreapp.automation.events.TimelineCollector
import coredevices.coreapp.automation.trust.ClientTrustStore
import coredevices.coreapp.automation.trust.PackageInspector
import coredevices.coreapp.automation.trust.hexToBytesOrNull
import coredevices.coreapp.automation.events.EventAccessPolicy
import io.rebble.libpebblecommon.automation.AutomationAppMessageHook
import io.rebble.libpebblecommon.automation.AutomationNotificationHooks
import io.rebble.libpebblecommon.automation.AutomationTimelineHook
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking

/**
 * App-side endpoint of the automation (Tasker) integration — the one object the host app touches
 * (HLDD-001 §1). Generic, zero Tasker references (ADR-008). Starts the event collectors and the
 * listener fan-out; the IPC surface is served by [coredevices.coreapp.automation.service.BridgeService].
 */
class AutomationBridge(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
    private val listenerHub: ListenerHub,
    private val trustStore: ClientTrustStore,
    private val settings: AutomationSettings,
    private val clientTether: ClientTether,
    private val inspector: PackageInspector,
) {
    private val logger = Logger.withTag("AutomationBridge")

    // Bridge-owned, application-lifetime scope (not GlobalScope, per repo guideline).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("AutomationBridge"),
    )

    private val connectivity = ConnectivityCollector(libPebble, dispatcher)
    private val perWatch = PerWatchCollector(libPebble, dispatcher)
    private val system = SystemEventCollector(libPebble, dispatcher)
    private val notifications = NotificationCollector(dispatcher, settings)
    private val appMessages = AppMessageCollector(dispatcher, libPebble)
    private val timeline = TimelineCollector(dispatcher)
    private var started = false
    private val policyLock = Any()

    /** Idempotent. Call once from the host Application after Koin starts. */
    fun init() {
        if (started) return
        started = true
        logger.i { "init bootId=${dispatcher.bootId}" }
        // Consent gate (PLAN §5.4): drop any event whose category is disabled, or everything when
        // the master switch is off. Enforced centrally in the dispatcher so every collector AND the
        // getEventsSince recovery path respect it uniformly.
        dispatcher.categoryGate = { category ->
            trustStore.masterEnabled.value && settings.isCategoryEnabled(category)
        }
        dispatcher.deliveryFilter = { event ->
            val visible = event.copy(watch = EventAccessPolicy.watch(event.watch,
                AutomationSettings.CATEGORIES.filter(settings::isCategoryEnabled).toSet()))
            if (event.category == "notifications") {
                val hidden = if (!settings.notificationContentEnabled.value) setOf("title", "text", "body", "reply_text")
                    else if (settings.redactNotificationContent.value) setOf("text", "body", "reply_text") else emptySet()
                visible.copy(data = event.data.filterKeys { it !in hidden } +
                    ("redacted" to (!settings.notificationContentEnabled.value || settings.redactNotificationContent.value).toString()) +
                    ("title_shared" to settings.notificationContentEnabled.value.toString()))
            } else visible
        }
        fun policyAllows(owner: String): Boolean {
            val record = trustStore.get(owner) ?: return false
            val cert = record.certSha256.hexToBytesOrNull() ?: return false
            return trustStore.masterEnabled.value && settings.isCategoryEnabled("apps") &&
                "apps" in record.categories && inspector.hasSigningCert(owner, cert)
        }
        AutomationAppMessageHook.ownerAllowed = { owner -> policyAllows(owner) && listenerHub.hasAuthorizedOwner(owner) }
        var previousOwners = trustStore.clients.value.keys.toSet()
        fun clearUnauthorizedSubscriptions() {
            val owners = previousOwners + trustStore.clients.value.keys
            owners.filterNot(::policyAllows).forEach { owner -> runBlocking { AutomationAppMessageHook.clearOwner(owner) } }
            previousOwners = trustStore.clients.value.keys.toSet()
        }
        trustStore.onPolicyChanged = {
            synchronized(policyLock) {
                listenerHub.revalidate()
                dispatcher.reconcilePolicy()
                clearUnauthorizedSubscriptions()
            }
        }
        settings.onPolicyChanged = {
            synchronized(policyLock) {
                listenerHub.invalidateAll()
                dispatcher.reconcilePolicy()
                clearUnauthorizedSubscriptions()
            }
        }
        connectivity.start(scope)
        perWatch.start(scope)
        system.start(scope)
        notifications.start(scope)
        appMessages.start(scope)
        timeline.start(scope)
        listenerHub.start(scope)
        // Keep consented clients alive while a watch is connected so events aren't delivered to a dead
        // process (the reverse-bind tether). No-op for clients that don't expose a KEEP_ALIVE service.
        clientTether.start(scope)
    }

    /**
     * Teardown counterpart to [init]: clears the process-global notification/appmessage hooks (so a
     * stale lambda can't fire into a cancelled scope, and a re-created bridge re-registers cleanly)
     * and cancels the running collectors. Safe to call repeatedly.
     */
    fun stop() {
        if (!started) return
        started = false
        trustStore.onPolicyChanged = null
        settings.onPolicyChanged = null
        AutomationNotificationHooks.onSent = null
        AutomationNotificationHooks.onAction = null
        AutomationAppMessageHook.onReceived = null
        AutomationAppMessageHook.ownerAllowed = null
        runBlocking { AutomationAppMessageHook.clear() }
        listenerHub.invalidateAll()
        AutomationTimelineHook.onAction = null
        // Cancel the collectors FIRST: ClientTether's reconcile loop lives in this scope, and a
        // pending emission arriving after stop() would re-bind clients that nothing would ever
        // unbind (stop() has already cleared its bookkeeping) — a leaked ServiceConnection.
        scope.coroutineContext.cancelChildren()
        clientTether.stop()
    }
}
