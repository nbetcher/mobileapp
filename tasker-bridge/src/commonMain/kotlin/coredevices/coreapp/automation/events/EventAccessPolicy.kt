package coredevices.coreapp.automation.events

object EventAccessPolicy {
    fun watch(ref: WatchRef?, categories: Set<String>): WatchRef? = ref?.copy(
        battery = ref.battery.takeIf { "connectivity" in categories },
        devEnabled = ref.devEnabled.takeIf { "system" in categories },
        fwStatus = ref.fwStatus.takeIf { "system" in categories },
        fwProgress = ref.fwProgress.takeIf { "system" in categories },
        currentAppUuid = ref.currentAppUuid.takeIf { "apps" in categories },
    )
    fun event(event: EventEnvelope, categories: Set<String>): EventEnvelope? =
        event.takeIf { it.category in categories }?.copy(watch = watch(event.watch, categories))
}
