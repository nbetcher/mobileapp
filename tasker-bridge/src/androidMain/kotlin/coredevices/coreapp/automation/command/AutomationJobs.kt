package coredevices.coreapp.automation.command

import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.events.EventAccessPolicy
import coredevices.coreapp.automation.events.EventDispatcher
import coredevices.coreapp.automation.events.WatchRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.uuid.Uuid

/**
 * Runs commands that outlast the IPC deadline (screenshots, log dumps). The command returns a
 * `job_id` at once; the outcome arrives later as a `system`/`job.done` event visible only to the
 * package that started it (see [EventAccessPolicy.OWNER_KEY]). One job per kind and watch at a time.
 */
class AutomationJobs(private val scope: CoroutineScope, private val dispatcher: EventDispatcher) {
    private val logger = Logger.withTag("AutomationBridge")
    private val active = HashSet<String>()

    /** @return the job id, or null when a job of this [command] is already running for [slot]. */
    fun start(
        command: String,
        slot: String,
        owner: String,
        watch: WatchRef?,
        timeoutMs: Long,
        block: suspend () -> Map<String, String>,
    ): String? {
        val key = "$command:$slot"
        synchronized(active) { if (!active.add(key)) return null }
        val jobId = Uuid.random().toString()
        scope.launch {
            val result = try {
                mapOf("status" to "ok") + withTimeout(timeoutMs) { block() }
            } catch (e: TimeoutCancellationException) {
                mapOf("status" to "failed", "error" to "timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "$command job failed" }
                mapOf("status" to "failed", "error" to (e.message ?: "failed"))
            } finally {
                synchronized(active) { active.remove(key) }
            }
            dispatcher.emit("system", "job.done", watch,
                result + mapOf("job_id" to jobId, "command" to command, EventAccessPolicy.OWNER_KEY to owner))
        }
        return jobId
    }
}
