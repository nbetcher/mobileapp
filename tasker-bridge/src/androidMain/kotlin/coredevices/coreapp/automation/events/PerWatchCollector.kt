package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Per-connection observers. Initial and terminal snapshots are part of the wire contract. */
class PerWatchCollector(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            val jobs = HashMap<String, Job>()
            val identities = HashMap<String, WatchRef>()
            val devFlows = HashMap<String, Any>()
            val fwJobs = HashMap<String, Job>()
            val fwStates = HashMap<String, FirmwareUpdateStatus>()
            libPebble.watches.collect { devices ->
                val connected = devices.filterIsInstance<CommonConnectedDevice>()
                val present = connected.map { it.identifier.asString }.toSet()
                jobs.keys.filter { it !in present }.forEach { key ->
                    jobs.remove(key)?.cancelAndJoin()
                    fwJobs.remove(key)?.cancelAndJoin()
                    fwStates.remove(key)
                    devFlows.remove(key)
                    val ref = identities.remove(key)?.copy(devEnabled = false, fwStatus = "unavailable", fwProgress = null, currentAppUuid = null)
                    dispatcher.emit("system", "dev.state", ref, mapOf("enabled" to "false", "available" to "false", "transport" to "unknown"))
                    dispatcher.emit("system", "fw.status", ref, mapOf("status" to "unavailable"))
                }
                for (device in connected) {
                    val id = device.identifier.asString
                    identities[id] = device.toWatchRef()
                    // The device wrapper changes on battery/firmware snapshots; the underlying
                    // connection's StateFlow identity changes only on a replacement connection.
                    if (devFlows[id] !== device.devConnectionActive) {
                        jobs.remove(id)?.cancelAndJoin()
                        fwJobs.remove(id)?.cancelAndJoin()
                        fwStates.remove(id)
                        devFlows[id] = device.devConnectionActive
                        jobs[id] = launch { observe(device) }
                    }
                    val fw = device.firmwareUpdateState
                    if (fwStates.put(id, fw) != fw) {
                        fwJobs.remove(id)?.cancelAndJoin()
                        fun emitFirmware() {
                            val current = currentDevice(device) ?: return
                            if (current.firmwareUpdateState != fw) return
                            dispatcher.emit("system", "fw.status", current.toWatchRef(), buildMap {
                                put("status", fw.wireStatus())
                                fw.wireProgress()?.let { put("progress", it.toString()) }
                            })
                        }
                        if (fw is FirmwareUpdateStatus.InProgress) {
                            fwJobs[id] = launch { fw.progress.collect { emitFirmware() } }
                        } else emitFirmware()
                    }
                }
            }
        }
    }

    private suspend fun observe(device: CommonConnectedDevice) = coroutineScope {
        launch {
            device.devConnectionActive.collect { active ->
                val current = currentDevice(device) ?: return@collect
                dispatcher.emit("system", "dev.state", current.toWatchRef(), mapOf("enabled" to active.toString(), "available" to "true", "transport" to "unknown"))
            }
        }
        if (device is ConnectedPebbleDevice) {
            launch {
                var previous: String? = null
                device.runningApp.map { uuid -> uuid to currentDevice(device)?.toWatchRef()?.copy(currentAppUuid = uuid?.toString()) }
                    .buffer(64).collect { (uuid, ref) ->
                    if (ref == null) return@collect
                    val app = try { uuid?.let { withTimeoutOrNull(250) { libPebble.getLockerApp(it).first() } } }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    // Metadata lookup suspends; a disconnect/replacement may have happened meanwhile.
                    if (currentDevice(device) == null) return@collect
                    dispatcher.emit("apps", "apps.run_state", ref, buildMap {
                        put("uuid", uuid?.toString().orEmpty())
                        put("state", if (uuid == null) "stopped" else "running")
                        // Unknown app metadata stays unknown; never label an unknown face as an app.
                        app?.properties?.let { put("app_type", it.type.code); put("app_name", it.title) }
                        previous?.let { put("previous_uuid", it) }
                    })
                    previous = uuid?.toString()
                }
            }
            launch {
                device.musicActions.collect { action ->
                    val current = currentDevice(device) ?: return@collect
                    dispatcher.emit("media", "media.command", current.toWatchRef(), mapOf("action" to action.name))
                }
            }
        }
    }

    // Wrappers refresh within one connection. A queued callback from a departed/replaced
    // connection must not resurrect it or borrow the new connection's identity before cancellation.
    private fun currentDevice(device: CommonConnectedDevice): CommonConnectedDevice? =
        (libPebble.watches.value.firstOrNull { it.identifier == device.identifier } as? CommonConnectedDevice)
            ?.takeIf { it.devConnectionActive === device.devConnectionActive }
}
