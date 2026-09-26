package io.rebble.libpebblecommon.automation

import co.touchlab.kermit.Logger
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.Settings
import com.russhwolf.settings.serialization.decodeValue
import com.russhwolf.settings.serialization.encodeValue
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

data class WatchPrefSyncOutcome(val prefId: String, val accepted: Boolean)

/**
 * Which watch preferences this watch supports, learned from the settings sync. PebbleOS only syncs
 * keys on its board-dependent allowlist, so a key the watch has written to the phone, or accepted from
 * it, is supported; a key it refused is not; anything else is unknown.
 */
interface WatchPrefSupport {
    val acceptedWatchPrefs: StateFlow<Set<String>>
    val rejectedWatchPrefs: StateFlow<Set<String>>
    val watchPrefSyncOutcomes: SharedFlow<WatchPrefSyncOutcome>

    object Unavailable : WatchPrefSupport {
        override val acceptedWatchPrefs: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val rejectedWatchPrefs: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val watchPrefSyncOutcomes: SharedFlow<WatchPrefSyncOutcome> = MutableSharedFlow()
    }
}

// No default values: the settings decoder silently returns the fallback for them.
@Serializable
private data class StoredPrefSupport(
    val firmware: String,
    val accepted: Set<String>,
    val rejected: Set<String>,
    /** Firmware under which the watch was last asked to send every setting it supports. */
    val fullSyncFirmware: String,
) {
    companion object {
        val EMPTY = StoredPrefSupport("", emptySet(), emptySet(), "")
    }
}

/** Per-watch, persisted across connections so the first full settings sync is not needed again. */
@OptIn(ExperimentalSettingsApi::class)
class WatchPrefSupportTracker(
    identifier: PebbleIdentifier,
    private val settings: Settings,
) : WatchPrefSupport {
    private val logger = Logger.withTag("WatchPrefSupport")
    private val storageKey = "automationPrefSupport-${identifier.asString}"
    private val state = atomic(StoredPrefSupport.EMPTY)
    private val accepted = MutableStateFlow<Set<String>>(emptySet())
    private val rejected = MutableStateFlow<Set<String>>(emptySet())
    private val outcomes = MutableSharedFlow<WatchPrefSyncOutcome>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val acceptedWatchPrefs: StateFlow<Set<String>> = accepted.asStateFlow()
    override val rejectedWatchPrefs: StateFlow<Set<String>> = rejected.asStateFlow()
    override val watchPrefSyncOutcomes: SharedFlow<WatchPrefSyncOutcome> = outcomes.asSharedFlow()

    /** Loads what earlier connections learned. A firmware update can add keys, so its refusals are dropped. */
    fun init(runningFirmware: String) {
        val saved = runCatching { settings.decodeValue(storageKey, StoredPrefSupport.EMPTY) }
            .onFailure { logger.w(it) { "could not load pref support" } }
            .getOrDefault(StoredPrefSupport.EMPTY)
        mutate { if (saved.firmware == runningFirmware) saved else saved.copy(firmware = runningFirmware, rejected = emptySet()) }
    }

    /**
     * Ongoing settings sync only carries changed keys, so a watch set up before this tracker existed
     * would never report its unchanged ones. A full sync once per firmware version fills them in; it
     * changes no values, since each side keeps the newer copy.
     */
    fun needsFullSync(): Boolean = state.value.let { it.firmware.isNotEmpty() && it.fullSyncFirmware != it.firmware }

    fun markFullSyncRequested() {
        mutate { it.copy(fullSyncFirmware = it.firmware) }
    }

    /** The watch wrote this key to the phone, so it is on the watch's allowlist. */
    fun recordWatchWrite(prefId: String) {
        mutate { it.copy(accepted = it.accepted + prefId, rejected = it.rejected - prefId) }
    }

    /**
     * The watch's reply to a phone write. PebbleOS answers both "not on the allowlist" and "the watch's
     * value is newer" with DataStale; a newer watch value is written back to the phone, which then
     * moves the key to accepted through [recordWatchWrite].
     */
    fun recordInsertResult(prefId: String, status: BlobResponse.BlobStatus?) {
        val isAccepted = when (status) {
            BlobResponse.BlobStatus.Success -> mutate { it.copy(accepted = it.accepted + prefId, rejected = it.rejected - prefId) }
            BlobResponse.BlobStatus.DataStale -> mutate { if (prefId in it.accepted) it else it.copy(rejected = it.rejected + prefId) }
            else -> return
        }.let { prefId in it.accepted }
        outcomes.tryEmit(WatchPrefSyncOutcome(prefId, isAccepted))
    }

    private fun mutate(transform: (StoredPrefSupport) -> StoredPrefSupport): StoredPrefSupport {
        var changed = false
        val next = state.updateAndGet { previous -> transform(previous).also { changed = it != previous } }
        if (changed) {
            accepted.value = next.accepted
            rejected.value = next.rejected
            runCatching { settings.encodeValue(storageKey, next) }.onFailure { logger.w(it) { "could not save pref support" } }
        }
        return next
    }
}
