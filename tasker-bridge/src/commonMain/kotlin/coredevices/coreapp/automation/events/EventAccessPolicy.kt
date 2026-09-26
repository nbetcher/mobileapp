package coredevices.coreapp.automation.events

object EventAccessPolicy {
    fun watch(ref: WatchRef?, categories: Set<String>): WatchRef? = ref?.copy(
        battery = ref.battery.takeIf { "connectivity" in categories },
        devEnabled = ref.devEnabled.takeIf { "system" in categories },
        fwStatus = ref.fwStatus.takeIf { "system" in categories },
        fwProgress = ref.fwProgress.takeIf { "system" in categories },
        currentAppUuid = ref.currentAppUuid.takeIf { "apps" in categories },
    )
    /** Data key naming the only package an event may reach (e.g. the requester of a job result). */
    const val OWNER_KEY = "owner"

    fun event(event: EventEnvelope, categories: Set<String>, pkg: String? = null): EventEnvelope? =
        event.takeIf { it.category in categories && (it.data[OWNER_KEY] ?: pkg) == pkg }
            ?.copy(watch = watch(event.watch, categories))
}
