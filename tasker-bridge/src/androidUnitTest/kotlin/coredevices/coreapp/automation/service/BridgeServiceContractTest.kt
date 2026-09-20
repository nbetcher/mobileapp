package coredevices.coreapp.automation.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coredevices.coreapp.automation.*
import coredevices.coreapp.automation.command.*
import coredevices.coreapp.automation.events.*
import coredevices.coreapp.automation.trust.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BridgeServiceContractTest {
    private class Inspector : PackageInspector {
        var packages = listOf("plugin")
        var cert = byteArrayOf(1, 2)
        var unknownVerified: () -> Unit = {}
        var signerChecked: () -> Unit = {}
        override fun packagesForUid(uid: Int) = packages
        override fun hasSigningCert(pkg: String, sha256: ByteArray): Boolean {
            val matches = cert.contentEquals(sha256)
            signerChecked()
            return matches
        }
        override fun signingCertSha256(pkg: String) = cert
        override fun installSource(pkg: String): String? { unknownVerified(); return null }
    }
    private lateinit var store: ClientTrustStore
    private lateinit var settings: AutomationSettings
    private lateinit var consent: ConsentController
    private lateinit var inspector: Inspector
    private lateinit var dispatcher: EventDispatcher
    private lateinit var hub: ListenerHub
    private lateinit var scope: CoroutineScope
    private lateinit var service: BridgeService
    private lateinit var api: IBridgeService
    private var commands = 0
    private val client = ClientRecord("plugin", "0102", "Plugin", setOf("connectivity", "apps", "system"), "normal", 1)

    @Before fun setup() {
        stopKoin()
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("automation_trust", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("automation_settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = ClientTrustStore(context)
        settings = AutomationSettings(context)
        inspector = Inspector()
        consent = ConsentController(context, store, inspector)
        dispatcher = EventDispatcher("boot")
        hub = ListenerHub(dispatcher)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hub.start(scope)
        store.onPolicyChanged = { hub.revalidate() }
        val provider = object : StateProvider {
            override fun watchRefs() = listOf(WatchRef("watch", "Watch", battery = 75, devEnabled = true, fwStatus = "in_progress", currentAppUuid = "private-app"))
            override fun appVersion() = "test"
            override fun supportedCapabilities() = listOf("state.dev", "state.bluetooth")
            override fun state() = StateData(watchRefs(), bluetoothEnabled = true, capabilities = supportedCapabilities())
        }
        val executor = CommandExecutor(CommandHandler { commands++; CommandResult.Ok(mapOf("accepted" to "true")) })
        startKoin { modules(module {
            single { store }; single { settings }; single { consent }
            single { CallerVerifier(inspector, store) }
            single { dispatcher }; single { hub }
            single<StateProvider> { provider }; single { executor }
        }) }
        service = Robolectric.buildService(BridgeService::class.java).create().get()
        api = IBridgeService.Stub.asInterface(service.onBind(null))
    }
    @After fun teardown() {
        if (::service.isInitialized) service.onDestroy()
        if (::scope.isInitialized) scope.cancel()
        stopKoin()
    }
    private fun authorize(record: ClientRecord = client) { store.approve(record); store.setMasterEnabled(true) }
    private fun hello() = BridgeJson.json.decodeFromString<BridgeHello>(api.handshake(BridgeJson.json.encodeToString(ClientHello())))
    private fun error(json: String) = BridgeJson.json.decodeFromString<ResultEnvelope>(json).error!!
    private fun execute(token: String, type: String, args: Map<String, String> = emptyMap()) =
        api.execute(token, BridgeJson.json.encodeToString(CommandEnvelope(type = type, args = args)))

    @Test fun freshHandshakeRequestsConsentWhilePrivilegedReadsStayDenied() {
        val pending = error(api.handshake(BridgeJson.json.encodeToString(ClientHello())))
        assertEquals(ErrorCode.CONSENT_PENDING, pending.code)
        assertEquals(1, store.pending.value.size)
        assertFalse(store.masterEnabled.value)
        assertEquals(ErrorCode.CONSENT_PENDING, error(api.getState("{}")).code)
        assertEquals(0, commands)
    }
    @Test fun concurrentConsentAndHandshakeDoNotHoldTrustWhileWaitingForConsent() {
        val verified = CountDownLatch(1)
        inspector.unknownVerified = { verified.countDown() }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }
        try {
            lateinit var request: java.util.concurrent.Future<String>
            synchronized(consent) {
                request = workers.submit<String> { api.handshake(BridgeJson.json.encodeToString(ClientHello())) }
                assertTrue(verified.await(2, TimeUnit.SECONDS))
                workers.submit<Boolean> { synchronized(store) { true } }.get(2, TimeUnit.SECONDS)
            }
            assertEquals(ErrorCode.CONSENT_PENDING, error(request.get(2, TimeUnit.SECONDS)).code)
        } finally { workers.shutdownNow() }
    }
    @Test fun grantReducedAfterAdmissionCannotDispatchUnderTheOldGrant() {
        authorize(client.copy(tier = "sensitive"))
        val token = hello().clientToken
        inspector.signerChecked = {
            inspector.signerChecked = {}
            store.approve(client.copy(tier = "normal"))
        }
        assertEquals(ErrorCode.NOT_AUTHORIZED, error(execute(token, CommandCatalog.WATCH_DISCONNECT)).code)
        assertEquals(0, commands)
    }
    @Test fun denialIsExplicitAcrossHandshakeStateReplayAndAction() {
        consent.onUnknownClient("plugin", "0102", null)
        consent.deny("plugin")
        val results = listOf(api.handshake(BridgeJson.json.encodeToString(ClientHello())), api.getState("{}"),
            api.getEventsSince(0, "boot"), execute("old", CommandCatalog.SYSTEM_PING))
        results.forEach {
            assertEquals(ErrorCode.ACCESS_DENIED, error(it).code)
            assertEquals("Denied in the Pebble app", error(it).message)
        }
        assertTrue(store.pending.value.isEmpty())
    }
    @Test fun unsupportedHandshakeCannotCreateConsentOrSession() {
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, error(api.handshake(BridgeJson.json.encodeToString(ClientHello(clientProtocol = 999)))).code)
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, error(api.handshake(BridgeJson.json.encodeToString(ClientHello(kind = "command")))).code)
        assertEquals(ErrorCode.INVALID_ARGS, error(api.handshake("garbage")).code)
        assertTrue(store.pending.value.isEmpty())
    }
    @Test fun replacementRetiresPriorTokenWithoutAffectingNewSession() {
        authorize()
        val first = hello()
        val next = hello()
        assertNotEquals(first.clientToken, next.clientToken)
        assertEquals(ErrorCode.NOT_AUTHORIZED, error(execute(first.clientToken, CommandCatalog.SYSTEM_PING)).code)
        assertTrue(BridgeJson.json.decodeFromString<ResultEnvelope>(execute(next.clientToken, CommandCatalog.SYSTEM_PING)).ok)
        assertEquals(1, commands)
    }
    @Test fun tokenFromAnotherVerifiedIdentityIsRejected() {
        authorize()
        val first = hello()
        store.approve(client.copy(packageName = "other"))
        inspector.packages = listOf("other")
        assertEquals(ErrorCode.NOT_AUTHORIZED, error(execute(first.clientToken, CommandCatalog.SYSTEM_PING)).code)
        assertEquals(0, commands)
    }
    @Test fun replayFiltersGrantsWhileCursorAcknowledgesAllScannedEvents() {
        authorize(client.copy(categories = setOf("connectivity")))
        dispatcher.emit("health", "health.updated", data = mapOf("steps_today" to "private"))
        dispatcher.emit("connectivity", "watch.connected", WatchRef("watch", "Watch", devEnabled = true, currentAppUuid = "private"))
        val batch = BridgeJson.json.decodeFromString<EventBatch>(api.getEventsSince(0, "boot"))
        assertEquals(listOf(2L), batch.events.map { it.seq })
        assertEquals(2L, batch.cursor)
        assertFalse(batch.historyLost)
        assertNull(batch.events.single().watch!!.currentAppUuid)
        assertNull(batch.events.single().watch!!.devEnabled)
    }
    @Test fun currentStateDoesNotExposeMetadataOutsideCurrentGrants() {
        authorize(client.copy(categories = setOf("connectivity")))
        val state = BridgeJson.json.decodeFromString<StateResult>(api.getState("{}")).data
        assertEquals(75, state.watches.single().battery)
        assertNull(state.watches.single().devEnabled)
        assertNull(state.watches.single().fwStatus)
        assertNull(state.watches.single().currentAppUuid)
        assertNull(state.bluetoothEnabled)
    }
    @Test fun systemStateDoesNotRequireAnUnrelatedConnectivityGrant() {
        authorize(client.copy(categories = setOf("system")))
        val state = BridgeJson.json.decodeFromString<StateResult>(api.getState("{}")).data
        assertNull(state.watches.single().battery)
        assertNull(state.watches.single().currentAppUuid)
        assertNotNull(state.watches.single().devEnabled)
        authorize(client.copy(categories = setOf("health")))
        assertEquals(ErrorCode.CATEGORY_DISABLED, error(api.getState("{}")).code)
    }
    @Test fun registrationAcknowledgementAndRevocationGoodbyeAreAuthoritative() {
        authorize()
        val session = hello()
        val goodbye = CountDownLatch(1)
        var reason: ErrorBody? = null
        val listener = object : IBridgeEventListener.Stub() {
            override fun onEvents(json: String) {}
            override fun onBridgeGoodbye(json: String) { reason = error(json); goodbye.countDown() }
        }
        assertNull(BridgeJson.json.decodeFromString<EventBatch>(api.getEventsSince(0, "boot")).subscriptionToken)
        api.registerEventListener(session.clientToken, listener, 0)
        assertEquals(session.clientToken, BridgeJson.json.decodeFromString<EventBatch>(api.getEventsSince(0, "boot")).subscriptionToken)
        store.revoke("plugin")
        assertTrue(goodbye.await(5, TimeUnit.SECONDS))
        assertEquals(ErrorCode.ACCESS_DENIED, reason!!.code)
        assertFalse(hub.registered(session.clientToken))
        assertEquals(ErrorCode.ACCESS_DENIED, error(execute(session.clientToken, CommandCatalog.SYSTEM_PING)).code)
    }
    @Test fun subscriptionsRequireReceivingGrantButOrdinaryActuationUsesCommandTier() {
        authorize(client.copy(categories = setOf("connectivity")))
        val session = hello()
        assertEquals(ErrorCode.CATEGORY_DISABLED, error(execute(session.clientToken, CommandCatalog.APPMESSAGE_SUBSCRIBE)).code)
        assertEquals(ErrorCode.CATEGORY_DISABLED, error(execute(session.clientToken, CommandCatalog.APPMESSAGE_SUBSCRIBE,
            mapOf("mode" to "replace", "enable" to "false", "subscriptions_json" to "[{\"uuid\":\"00000000-0000-0000-0000-000000000001\"}]"))).code)
        assertEquals(0, commands)
        for (disabled in listOf("false", "FALSE", "0", "off")) {
            assertTrue(BridgeJson.json.decodeFromString<ResultEnvelope>(execute(session.clientToken, CommandCatalog.APPMESSAGE_SUBSCRIBE, mapOf("enable" to disabled))).ok)
        }
        assertTrue(BridgeJson.json.decodeFromString<ResultEnvelope>(execute(session.clientToken, CommandCatalog.NOTIFICATION_SEND)).ok)
    }
}
