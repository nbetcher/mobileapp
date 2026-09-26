package coredevices.coreapp.automation.command

/**
 * Per-key cooldowns whose length depends on how the guarded operation turned out. [tryAcquire]
 * reserves the key for [provisionalMs] so concurrent callers cannot both start; [finish] then sets the
 * real cooldown once the outcome is known.
 */
class Cooldowns(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val until = HashMap<String, Long>()

    /** @return 0 when acquired, else the milliseconds left on the current cooldown. */
    @Synchronized fun tryAcquire(key: String, provisionalMs: Long): Long {
        val now = nowMs()
        val remaining = (until[key] ?: now) - now
        if (remaining > 0) return remaining
        until[key] = now + provisionalMs
        return 0
    }

    @Synchronized fun finish(key: String, cooldownMs: Long) {
        until[key] = nowMs() + cooldownMs
    }
}
