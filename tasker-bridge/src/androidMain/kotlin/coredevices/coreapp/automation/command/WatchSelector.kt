package coredevices.coreapp.automation.command

import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.ErrorCode
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble

/** Resolves a command's watch selector (serial or BT address); absent selects the only candidate. */
internal class WatchSelector(private val libPebble: LibPebble) {
    private val logger = Logger.withTag("AutomationBridge")

    fun connected(selector: String?): CommonConnectedDevice? =
        pick(libPebble.watches.value.filterIsInstance<CommonConnectedDevice>(), selector)

    fun known(selector: String?): KnownPebbleDevice? =
        pick(libPebble.watches.value.filterIsInstance<KnownPebbleDevice>(), selector)

    fun noWatch(selector: String?): CommandResult {
        logger.d { "no matching watch for selector=$selector" }
        return CommandResult.Failure(
            ErrorCode.INVALID_ARGS,
            if (selector.isNullOrBlank()) "no unique eligible watch; select a watch" else "no unique eligible watch matching '$selector'",
        )
    }

    private fun <T : KnownPebbleDevice> pick(candidates: List<T>, selector: String?): T? =
        if (selector.isNullOrBlank()) candidates.singleOrNull()
        else candidates.singleOrNull { it.serial == selector || it.identifier.asString == selector }
}
