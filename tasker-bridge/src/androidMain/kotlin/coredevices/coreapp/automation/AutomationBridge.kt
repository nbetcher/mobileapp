package coredevices.coreapp.automation

import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.events.ConnectivityCollector
import coredevices.coreapp.automation.events.EventDispatcher
import coredevices.coreapp.automation.events.ListenerHub
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * App-side endpoint of the automation (Tasker) integration — the one object the host app touches
 * (HLDD-001 §1). Generic, zero Tasker references (ADR-008). Starts the event collectors and the
 * listener fan-out; the IPC surface is served by [coredevices.coreapp.automation.service.BridgeService].
 */
class AutomationBridge(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
    private val listenerHub: ListenerHub,
) {
    private val logger = Logger.withTag("AutomationBridge")

    // Bridge-owned, application-lifetime scope (not GlobalScope, per repo guideline).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("AutomationBridge"),
    )

    private val connectivity = ConnectivityCollector(libPebble, dispatcher)
    private var started = false

    /** Idempotent. Call once from the host Application after Koin starts. */
    fun init() {
        if (started) return
        started = true
        logger.i { "init bootId=${dispatcher.bootId}" }
        connectivity.start(scope)
        listenerHub.start(scope)
    }
}
