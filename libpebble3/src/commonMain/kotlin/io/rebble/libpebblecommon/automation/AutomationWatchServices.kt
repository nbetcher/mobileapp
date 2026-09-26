package io.rebble.libpebblecommon.automation

import io.rebble.libpebblecommon.services.WatchInfo

/** Per-watch services the fork adds for the automation bridge; created in the connection scope. */
class AutomationWatchServices(
    val remoteInput: RemoteInputService,
    val prefSupport: WatchPrefSupportTracker,
) {
    fun init(watchInfo: WatchInfo) {
        remoteInput.init(watchInfo.runningFwVersion)
        // Recovery firmware does not sync settings; its version must not reset what the main firmware refused.
        if (!watchInfo.runningFwVersion.isRecovery) prefSupport.init(watchInfo.runningFwVersion.stringVersion)
    }
}
