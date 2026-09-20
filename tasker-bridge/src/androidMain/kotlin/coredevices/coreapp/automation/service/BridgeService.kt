package coredevices.coreapp.automation.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import coredevices.coreapp.automation.*
import coredevices.coreapp.automation.command.CommandCatalog
import coredevices.coreapp.automation.command.CommandExecutor
import coredevices.coreapp.automation.command.CommandTier
import coredevices.coreapp.automation.events.EventDispatcher
import coredevices.coreapp.automation.events.ListenerHub
import coredevices.coreapp.automation.events.EventAccessPolicy
import coredevices.coreapp.automation.trust.CallerVerifier
import coredevices.coreapp.automation.trust.ClientTrustStore
import coredevices.coreapp.automation.trust.ConsentController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BridgeService : Service(), KoinComponent {
    private val verifier: CallerVerifier by inject()
    private val consent: ConsentController by inject()
    private val dispatcher: EventDispatcher by inject()
    private val listenerHub: ListenerHub by inject()
    private val stateProvider: StateProvider by inject()
    private val commandExecutor: CommandExecutor by inject()
    private val trustStore: ClientTrustStore by inject()
    private val settings: AutomationSettings by inject()
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ClientSessions { session -> listenerHub.revoke(session.token) }

    private fun authorized(session: ClientSessions.Session): Boolean =
        sessions.find(session.token) == session &&
            (verifier.verify(session.uid) as? CallerVerifier.Result.Verified)?.record == session.client

    private val binder = object : IBridgeService.Stub() {
        override fun handshake(helloJson: String): String {
            var pending: CallerVerifier.Result.Unknown? = null
            val reply = synchronized(trustStore) {
                synchronized(settings) { handshakeWithStablePolicy(helloJson) { pending = it } }
            }
            // Consent callbacks take their own lock before the trust store.
            pending?.let { consent.onUnknownClient(it.packageName, it.certSha256Hex, it.installSource) }
            return reply
        }
        private fun handshakeWithStablePolicy(helloJson: String, pending: (CallerVerifier.Result.Unknown) -> Unit): String {
            val hello = runCatching { BridgeJson.json.decodeFromString<ClientHello>(helloJson) }.getOrNull()
                ?: return err(ErrorCode.INVALID_ARGS, "Malformed hello")
            if (hello.v != 1 || hello.kind != "hello" || hello.clientProtocol != 1)
                return err(ErrorCode.UNSUPPORTED_VERSION, "Update both Pebble and its automation plugin")
            val uid = Binder.getCallingUid()
            return when (val result = verifier.verify(uid)) {
                is CallerVerifier.Result.Verified -> {
                    val record = result.record
                    val session = sessions.replace(uid, record)
                    BridgeJson.json.encodeToString(BridgeHello(
                        bootId = dispatcher.bootId,
                        capabilities = capabilities(),
                        grants = Grants(record.categories.filter(settings::isCategoryEnabled), record.tier,
                            !settings.notificationContentEnabled.value || settings.redactNotificationContent.value),
                        latestSeq = dispatcher.latestSeq,
                        appVersion = stateProvider.appVersion(),
                        clientToken = session.token,
                        authorityId = "${trustStore.authorityRevision}:${trustStore.clientAuthorityRevision(record.packageName)}:${settings.authorityRevision}",
                    ))
                }
                is CallerVerifier.Result.Unknown -> {
                    pending(result)
                    err(ErrorCode.CONSENT_PENDING, "Review the access request in the Pebble app")
                }
                else -> verificationError(result)
            }
        }

        override fun getState(queryJson: String): String {
            val verification = verifier.verify(Binder.getCallingUid())
            val record = (verification as? CallerVerifier.Result.Verified)?.record ?: return verificationError(verification)
            val categories = record.categories.filter(settings::isCategoryEnabled).toSet()
            if (categories.none { it in setOf("connectivity", "system", "apps") })
                return err(ErrorCode.CATEGORY_DISABLED, "Watch state access is disabled in the Pebble app")
            val state = stateProvider.state()
            return BridgeJson.json.encodeToString(StateResult(data = state.copy(
                watches = state.watches.mapNotNull { EventAccessPolicy.watch(it, categories) },
                bluetoothEnabled = state.bluetoothEnabled.takeIf { "system" in categories })))
        }

        override fun getEventsSince(fromSeq: Long, bootId: String): String {
            val uid = Binder.getCallingUid()
            val verification = verifier.verify(uid)
            val record = (verification as? CallerVerifier.Result.Verified)?.record ?: return verificationError(verification)
            val session = sessions.forClient(uid, record)
            val cursor = if (bootId == dispatcher.bootId) fromSeq else 0L
            // Long.MAX_VALUE requests registration proof only, never a replay payload.
            val batch = dispatcher.snapshot(cursor, transform = {
                EventAccessPolicy.event(it, record.categories.filter(settings::isCategoryEnabled).toSet())
            }).copy(
                subscriptionToken = session?.token?.takeIf(listenerHub::registered))
            return BridgeJson.json.encodeToString(batch)
        }

        override fun registerEventListener(clientToken: String, cb: IBridgeEventListener, fromSeq: Long) {
            val uid = Binder.getCallingUid()
            val session = sessions.find(clientToken) ?: return
            if (session.uid != uid || !authorized(session)) return
            listenerHub.register(clientToken, cb, fromSeq,
                authorized = { authorized(session) },
                permitted = { it.category in session.client.categories },
                closed = { sessions.remove(clientToken) }, ownerPackage = session.client.packageName,
                transform = { EventAccessPolicy.event(it, session.client.categories.filter(settings::isCategoryEnabled).toSet()) },
                goodbyeReason = { verificationError(verifier.verify(session.uid)) })
        }

        override fun unregisterEventListener(clientToken: String) {
            val session = sessions.find(clientToken) ?: return
            if (session.uid != Binder.getCallingUid()) return
            sessions.remove(clientToken)
        }

        override fun execute(clientToken: String, commandJson: String): String {
            val verification = verifier.verify(Binder.getCallingUid())
            val record = (verification as? CallerVerifier.Result.Verified)?.record ?: return verificationError(verification)
            val session = sessions.find(clientToken)
            if (session == null || session.uid != Binder.getCallingUid() || session.client != record)
                return err(ErrorCode.NOT_AUTHORIZED, "The automation session expired; reconnect to Pebble")
            val command = runCatching { BridgeJson.json.decodeFromString<CommandEnvelope>(commandJson) }.getOrNull()
            if (command?.type == CommandCatalog.APPMESSAGE_SUBSCRIBE &&
                !(command.args["mode"] == null && command.args["enable"]?.trim()?.lowercase() in setOf("false", "0", "off")) &&
                ("apps" !in record.categories || !settings.isCategoryEnabled("apps")))
                return err(ErrorCode.CATEGORY_DISABLED, "AppMessage receiving access is disabled in the Pebble app")
            return runBlocking(commandScope.coroutineContext) {
                if (!authorized(session)) return@runBlocking err(ErrorCode.NOT_AUTHORIZED,
                    "Automation access changed before the command started")
                commandExecutor.execute(clientToken = clientToken, commandJson = commandJson,
                    grantedTier = CommandTier.fromGrant(record.tier),
                    dangerousEnabled = trustStore.dangerousCommandsEnabled.value,
                    clientIdentity = record.packageName)
            }
        }
    }

    private fun capabilities(): List<String> = listOf("events.core", "events.notifications", "events.health",
        "events.cursor", "events.registration_ack", "events.registration_ack_only", "events.paged", "appmessages.replace_subscriptions", "commands.core", "commands.sensitive", "commands.dangerous", "appmessages") +
        CommandCatalog.types.map { "command.$it" } + CommandCatalog.globalTypes.map { "command.global.$it" } +
        stateProvider.supportedCapabilities()

    private fun verificationError(result: CallerVerifier.Result): String = when (result) {
        CallerVerifier.Result.Denied -> err(ErrorCode.ACCESS_DENIED, "Denied in the Pebble app")
        CallerVerifier.Result.CertMismatch -> err(ErrorCode.CERT_MISMATCH, "Pebble rejected this plugin's signing certificate")
        is CallerVerifier.Result.Unknown -> err(ErrorCode.CONSENT_PENDING, "Review access in the Pebble app")
        else -> err(ErrorCode.NOT_AUTHORIZED, if (!trustStore.masterEnabled.value) "Automation is disabled in the Pebble app" else "Not authorized in the Pebble app")
    }
    private fun err(code: String, message: String): String = BridgeJson.json.encodeToString(ResultEnvelope.error(code, message))
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        sessions.clear()
        commandScope.cancel()
        super.onDestroy()
    }
}
