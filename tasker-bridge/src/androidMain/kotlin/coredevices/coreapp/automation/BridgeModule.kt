package coredevices.coreapp.automation

import android.content.Context
import coredevices.coreapp.automation.command.CommandExecutor
import coredevices.coreapp.automation.command.CommandHandler
import coredevices.coreapp.automation.command.LibPebbleCommandHandler
import coredevices.coreapp.automation.events.EventDispatcher
import coredevices.coreapp.automation.events.ListenerHub
import coredevices.coreapp.automation.service.LibPebbleStateProvider
import coredevices.coreapp.automation.service.StateProvider
import coredevices.coreapp.automation.trust.AndroidPackageInspector
import coredevices.coreapp.automation.trust.CallerVerifier
import coredevices.coreapp.automation.trust.ClientTrustStore
import coredevices.coreapp.automation.trust.ConsentController
import coredevices.coreapp.automation.trust.PackageInspector
import io.rebble.libpebblecommon.connection.LibPebble
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import kotlin.uuid.Uuid

/**
 * Koin module for the automation bridge. Loaded by the host app's startKoin (MainApplication).
 * `Context` is resolved from `androidContext()` registered by the host (no koin-android needed).
 */
val taskerModule = module {
    single { EventDispatcher(bootId = Uuid.random().toString()) }
    single { ListenerHub(get()) }
    single { ClientTrustStore(get<Context>()) }
    single { AutomationSettings(get<Context>()) }
    single<PackageInspector> { AndroidPackageInspector(get<Context>().packageManager) }
    single { CallerVerifier(get(), get<ClientTrustStore>()) }
    single { ConsentController(get<Context>(), get()) }
    single<StateProvider> { LibPebbleStateProvider(get(), get<Context>()) }
    single<CommandHandler> { LibPebbleCommandHandler(get<LibPebble>(), get<EventDispatcher>()) }
    single { CommandExecutor(get<CommandHandler>()) }
    single {
        ClientTether(
            appContext = get<Context>(),
            libPebble = get<LibPebble>(),
            trustStore = get<ClientTrustStore>(),
            inspector = get<PackageInspector>(),
        )
    }
    singleOf(::AutomationBridge)
}
