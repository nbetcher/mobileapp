package io.rebble.libpebblecommon.automation

/** Outcome of setting the watch clock and reading it back. [skewSeconds] is watch minus phone. */
sealed interface TimeSyncResult {
    data class Success(val skewSeconds: Long) : TimeSyncResult
    data class Mismatch(val skewSeconds: Long) : TimeSyncResult
    data object Timeout : TimeSyncResult
}
