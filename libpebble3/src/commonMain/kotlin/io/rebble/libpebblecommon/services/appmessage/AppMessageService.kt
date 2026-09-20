package io.rebble.libpebblecommon.services.appmessage

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.AppCustomizationSetStockAppIconMessage
import io.rebble.libpebblecommon.packets.AppCustomizationSetStockAppTitleMessage
import io.rebble.libpebblecommon.packets.AppMessage
import io.rebble.libpebblecommon.packets.AppMessageTuple
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.atomicfu.atomic
import io.rebble.libpebblecommon.automation.AutomationAppMessageHook
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val APPMESSAGE_BUFFER_SIZE = 16
private val APPMESSAGE_TIMEOUT = 10.seconds

class AppMessageService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope,
    private val identifier: PebbleIdentifier,
) : ProtocolService, ConnectedPebble.AppMessages {
    private val logger = Logger.withTag("AppMessageService")
    private val receivedMessages = HashMap<Uuid, Channel<AppMessageData>>()
    override val transactionSequence: Iterator<UByte> = AppMessageTransactionSequence().iterator()
    private val mapAccessMutex = Mutex()

    // null/absent metadata retains the ordinary companion path. Published before companions start.
    private val taskerEligible = atomic<Set<Uuid>>(emptySet())
    fun setAutomationEligibility(uuid: Uuid, eligible: Boolean) {
        taskerEligible.value = if (eligible) taskerEligible.value + uuid else taskerEligible.value - uuid
    }

    fun init() {
        protocolHandler.inboundMessages.onEach {
            when (it) {
                is AppMessage.AppMessagePush -> {
                    val appMessageData = it.appMessageData()
                    val address = identifier.asString
                    val uuid = appMessageData.uuid.toString()
                    val owns = appMessageData.uuid in taskerEligible.value &&
                        AutomationAppMessageHook.hasAuthorizedOwnership(address, uuid)
                    val accepted = AutomationAppMessageHook.deliver(address, uuid, appMessageData.transactionId.toInt(), appMessageData.data)
                    if (owns) {
                        // Owned messages never enter a native/PKJS queue, so fallback companions
                        // cannot NACK or ACK them a second time. Revocation is rechecked at ACK.
                        val ack = accepted && AutomationAppMessageHook.hasAuthorizedOwnership(address, uuid)
                        sendAppMessageResult(if (ack) AppMessageResult.ACK(appMessageData.transactionId) else AppMessageResult.NACK(appMessageData.transactionId))
                    } else {
                        getReceivedMessagesChannel(it.uuid.get()).trySend(appMessageData)
                    }
                }
            }
        }.launchIn(scope)
    }

    /**
     * Send an AppMessage
     */
    override suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult = coroutineScope {
        val appMessage = AppMessage.AppMessagePush(
            transactionId = appMessageData.transactionId,
            uuid = appMessageData.uuid,
            tuples = appMessageData.data.toAppMessageTuples()
        )
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(APPMESSAGE_TIMEOUT) {
                protocolHandler.inboundMessages.first {
                    it is AppMessage && (it is AppMessage.AppMessageACK || it is AppMessage.AppMessageNACK)
                            && it.transactionId.get() == appMessageData.transactionId
                }
            } ?: run {
                logger.w { "Timed out sending AppMessage ${appMessageData.transactionId}" }
                AppMessage.AppMessageNACK(appMessageData.transactionId)
            }
        }
        try {
            protocolHandler.send(appMessage)
            when (val msg = result.await()) {
                is AppMessage.AppMessageACK -> msg.appMessageResult()
                is AppMessage.AppMessageNACK -> msg.appMessageResult()
                else -> throw IllegalStateException("Unexpected result: $result")
            }
        } finally {
            result.cancel()
        }
    }

    override suspend fun sendAppMessageResult(appMessageResult: AppMessageResult) {
        val appMessage = when (appMessageResult) {
            is AppMessageResult.ACK -> AppMessage.AppMessageACK(appMessageResult.transactionId)
            is AppMessageResult.NACK -> AppMessage.AppMessageNACK(appMessageResult.transactionId)
        }
        protocolHandler.send(appMessage)
    }

    suspend fun send(packet: AppCustomizationSetStockAppIconMessage) {
        protocolHandler.send(packet)
    }

    suspend fun send(packet: AppCustomizationSetStockAppTitleMessage) {
        protocolHandler.send(packet)
    }

    override fun inboundAppMessages(appUuid: Uuid): Flow<AppMessageData> {
        return suspend { getReceivedMessagesChannel(appUuid) }.asFlow().flatMapConcat { it.receiveAsFlow() }
    }

    private suspend fun getReceivedMessagesChannel(appUuid: Uuid): Channel<AppMessageData> {
        receivedMessages[appUuid]?.let { return it }

        return mapAccessMutex.withLock {
            receivedMessages.getOrPut(appUuid) { Channel(APPMESSAGE_BUFFER_SIZE) }
        }
    }
}

private fun Map<Int, Any>.toAppMessageTuples(): List<AppMessageTuple> {
    return map { (key, value) ->
        val k = key.toUInt()
        when (value) {
            is String -> AppMessageTuple.createString(k, value)
            is UByteArray -> AppMessageTuple.createUByteArray(k, value)
            is ByteArray -> AppMessageTuple.createUByteArray(k, value.asUByteArray())
            is Int -> AppMessageTuple.createInt(k, value)
            is UInt -> AppMessageTuple.createUInt(k, value)
            is Short -> AppMessageTuple.createShort(k, value)
            is UShort -> AppMessageTuple.createUShort(k, value)
            is Byte -> AppMessageTuple.createByte(k, value)
            is UByte -> AppMessageTuple.createUByte(k, value)
            is Boolean -> AppMessageTuple.createShort(k, if (value) 1 else 0)
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.simpleName}")
        }
    }
}
typealias AppMessageDictionary = Map<Int, Any>

data class AppMessageData(
    val transactionId: UByte,
    val uuid: Uuid,
    val data: AppMessageDictionary
)

sealed class AppMessageResult(val transactionId: UByte) {
    class ACK(transactionId: UByte) : AppMessageResult(transactionId)
    class NACK(transactionId: UByte) : AppMessageResult(transactionId)
}

private fun AppMessage.AppMessagePush.appMessageData(): AppMessageData {
    return AppMessageData(
        transactionId = transactionId.get(),
        uuid = uuid.get(),
        data = dictionary.list.associate { it.key.get().toInt() to it.getTypedData() }
    )
}

private fun AppMessage.AppMessageACK.appMessageResult() = AppMessageResult.ACK(transactionId.get())
private fun AppMessage.AppMessageNACK.appMessageResult() = AppMessageResult.NACK(transactionId.get())
