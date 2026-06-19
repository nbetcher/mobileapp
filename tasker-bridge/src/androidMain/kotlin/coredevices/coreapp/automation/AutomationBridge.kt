package coredevices.coreapp.automation

import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.events.ConnectivityCollector
import coredevices.coreapp.automation.events.EventDispatcher
import coredevices.coreapp.automation.events.ListenerHub
import coredevices.coreapp.automation.events.AppMessageCollector
import coredevices.coreapp.automation.events.NotificationCollector
import coredevices.coreapp.automation.events.PerWatchCollector
import coredevices.coreapp.automation.events.SystemEventCollector
import coredevices.coreapp.automation.trust.ClientTrustStore
import io.rebble.libpebblecommon.automation.AutomationAppMessageHook
import io.rebble.libpebblecommon.automation.AutomationNotificationHooks
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren

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
    private val appMessages = AppMessageCollector(dispatcher)
    private var started = false

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
        connectivity.start(scope)
        perWatch.start(scope)
        system.start(scope)
        notifications.start(scope)
        appMessages.start(scope)
        listenerHub.start(scope)
    }

    /**
     * Teardown counterpart to [init]: clears the process-global notification/appmessage hooks (so a
     * stale lambda can't fire into a cancelled scope, and a re-created bridge re-registers cleanly)
     * and cancels the running collectors. Safe to call repeatedly.
     */
    fun stop() {
        if (!started) return
        started = false
        AutomationNotificationHooks.onSent = null
        AutomationNotificationHooks.onAction = null
        AutomationAppMessageHook.onReceived = null
        scope.coroutineContext.cancelChildren()
    }
}
