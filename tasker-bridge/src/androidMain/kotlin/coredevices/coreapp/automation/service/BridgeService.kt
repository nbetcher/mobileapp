package coredevices.coreapp.automation.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.BridgeHello
import coredevices.coreapp.automation.BridgeJson
import coredevices.coreapp.automation.ClientHello
import coredevices.coreapp.automation.ClientRecord
import coredevices.coreapp.automation.ErrorCode
import coredevices.coreapp.automation.EventBatch
import coredevices.coreapp.automation.Grants
import coredevices.coreapp.automation.IBridgeEventListener
import coredevices.coreapp.automation.IBridgeService
import coredevices.coreapp.automation.ResultEnvelope
import coredevices.coreapp.automation.StateData
import coredevices.coreapp.automation.StateResult
import coredevices.coreapp.automation.command.CommandExecutor
import coredevices.coreapp.automation.command.CommandTier
import coredevices.coreapp.automation.events.EventDispatcher
import coredevices.coreapp.automation.events.ListenerHub
import coredevices.coreapp.automation.trust.CallerVerifier
import coredevices.coreapp.automation.trust.ClientTrustStore
import coredevices.coreapp.automation.trust.ConsentController
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Exported AIDL endpoint of the bridge (HLDD-001 §8, HLDD-002 §3). Exported with NO permission
 * attribute — every transaction is authenticated in code via [CallerVerifier] (ADR-003).
 */
class BridgeService : Service(), KoinComponent {
    private val logger = Logger.withTag("AutomationBridge")
    private val verifier: CallerVerifier by inject()
    private val consent: ConsentController by inject()
    private val dispatcher: EventDispatcher by inject()
    private val listenerHub: ListenerHub by inject()
    private val stateProvider: StateProvider by inject()
    private val commandExecutor: CommandExecutor by inject()
    private val trustStore: ClientTrustStore by inject()

    // Service-owned scope for the synchronous-over-coroutine command bridge. Commands are blocked
    // on (so the Binder thread returns a String) but run on this scope's dispatcher, not the binder
    // pool. Bounded internally by CommandExecutor's withTimeout.
    private val commandScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("BridgeCommands"),
    )

    private val binder = object : IBridgeService.Stub() {

        override fun handshake(helloJson: String): String {
            val hello = runCatching { BridgeJson.json.decodeFromString<ClientHello>(helloJson) }.getOrNull()
                ?: return err(ErrorCode.INVALID_ARGS, "malformed hello")
            return when (val result = verifier.verify(Binder.getCallingUid())) {
                is CallerVerifier.Result.Verified -> {
                    val record = result.record
                    val token = "t${tokenCounter.incrementAndGet()}"
                    tokens[token] = record.packageName
                    BridgeJson.json.encodeToString(
                        BridgeHello(
                            bootId = dispatcher.bootId,
                            capabilities = CAPABILITIES,
                            grants = Grants(record.categories.toList(), record.tier, contentRedacted = true),
                            latestSeq = dispatcher.latestSeq,
                            appVersion = stateProvider.appVersion(),
                            clientToken = token,
                        ),
                    )
                }

                is CallerVerifier.Result.Unknown -> {
                    logger.i { "consent requested by ${result.packageName}" }
                    consent.onUnknownClient(result.packageName, result.certSha256Hex, result.installSource)
                    err(ErrorCode.CONSENT_PENDING, "awaiting user approval")
                }

                CallerVerifier.Result.CertMismatch -> err(ErrorCode.CERT_MISMATCH, "signing certificate changed")
                CallerVerifier.Result.NotAuthorized -> err(ErrorCode.NOT_AUTHORIZED, "not authorized")
            }
        }

        override fun getState(queryJson: String): String {
            verifiedOrNull() ?: return err(ErrorCode.NOT_AUTHORIZED, "not authorized")
            return BridgeJson.json.encodeToString(StateResult(data = StateData(stateProvider.watchRefs())))
        }

        override fun getEventsSince(fromSeq: Long, bootId: String): String {
            verifiedOrNull() ?: return err(ErrorCode.NOT_AUTHORIZED, "not authorized")
            val events = if (bootId == dispatcher.bootId) dispatcher.since(fromSeq) else emptyList()
            return BridgeJson.json.encodeToString(EventBatch(bootId = dispatcher.bootId, events = events))
        }

        override fun registerEventListener(clientToken: String, cb: IBridgeEventListener, fromSeq: Long) {
            val record = verifiedOrNull() ?: return
            if (tokens[clientToken] != record.packageName) return
            listenerHub.register(clientToken, cb, fromSeq)
        }

        override fun unregisterEventListener(clientToken: String) {
            listenerHub.unregister(clientToken)
        }

        override fun execute(clientToken: String, commandJson: String): String {
            // Re-verify the caller on every command (mirrors registerEventListener): a revoked or
            // master-off client is rejected mid-session, and the token must belong to this caller.
            val record = verifiedOrNull() ?: return err(ErrorCode.NOT_AUTHORIZED, "not authorized")
            if (tokens[clientToken] != record.packageName) {
                return err(ErrorCode.NOT_AUTHORIZED, "unknown or stale client token")
            }
            // Bridge the synchronous Binder call onto the command scope; the executor enforces the
            // allowlist, tier gate, dangerous toggle, rate limit, and its own per-command timeout.
            return runBlocking(commandScope.coroutineContext) {
                commandExecutor.execute(
                    clientToken = clientToken,
                    commandJson = commandJson,
                    grantedTier = CommandTier.fromGrant(record.tier),
                    dangerousEnabled = trustStore.dangerousCommandsEnabled.value,
                )
            }
        }

        private fun verifiedOrNull(): ClientRecord? =
            (verifier.verify(Binder.getCallingUid()) as? CallerVerifier.Result.Verified)?.record
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        commandScope.cancel()
        super.onDestroy()
    }

    private fun err(code: String, message: String): String =
        BridgeJson.json.encodeToString(ResultEnvelope.error(code, message))

    companion object {
        private val CAPABILITIES = listOf("events.core")
        private val tokens = ConcurrentHashMap<String, String>() // clientToken -> package
        private val tokenCounter = AtomicLong(0)
    }
}
