package io.rebble.libpebblecommon.automation

import io.rebble.libpebblecommon.services.WatchInfo

/** Per-watch services the fork adds for the automation bridge; created in the connection scope. */
class AutomationWatchServices(
    val remoteInput: RemoteInputService,
) {
    fun init(watchInfo: WatchInfo) {
        remoteInput.init(watchInfo.runningFwVersion)
    }
}
