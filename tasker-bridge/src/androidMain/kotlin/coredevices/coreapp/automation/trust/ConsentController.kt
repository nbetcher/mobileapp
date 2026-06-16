package coredevices.coreapp.automation.trust

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.ClientRecord
import kotlin.time.Clock

/**
 * Mediates user consent for automation clients (HLDD-001 §6). Records a pending request when an
 * unknown client appears and surfaces a notification deep-linking to the host app's review screen
 * (decoupled by intent action [ACTION_REVIEW]); applies the user's approve/deny/revoke. The state
 * (pending / approved / master toggle) is exposed for that UI to bind to.
 */
class ConsentController(
    private val context: Context,
    private val trustStore: ClientTrustStore,
) {
    private val logger = Logger.withTag("AutomationBridge")

    val pending get() = trustStore.pending
    val clients get() = trustStore.clients
    val masterEnabled get() = trustStore.masterEnabled
    val dangerousCommandsEnabled get() = trustStore.dangerousCommandsEnabled

    fun setMasterEnabled(on: Boolean) = trustStore.setMasterEnabled(on)
    fun setDangerousCommandsEnabled(on: Boolean) = trustStore.setDangerousCommandsEnabled(on)

    /** An unknown client handshaked — record it and notify the user to review. */
    fun onUnknownClient(pkg: String, certSha256Hex: String, installSource: String?) {
        trustStore.addPending(PendingClient(pkg, certSha256Hex, installSource))
        notifyReview(pkg)
    }

    /** Approve [pkg], pinning the cert seen at approval (HLDD-002 §5). */
    fun approve(pkg: String, label: String, certSha256Hex: String, categories: Set<String>, tier: String) {
        trustStore.approve(
            ClientRecord(
                packageName = pkg,
                certSha256 = certSha256Hex,
                label = label,
                categories = categories,
                tier = tier,
                approvedAtMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        logger.i { "approved client $pkg categories=$categories tier=$tier" }
    }

    fun deny(pkg: String) = trustStore.deny(pkg)
    fun revoke(pkg: String) = trustStore.revoke(pkg)

    private fun notifyReview(pkg: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Automation access", NotificationManager.IMPORTANCE_HIGH),
        )
        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent(ACTION_REVIEW).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Allow automation access?")
            .setContentText("$pkg wants to receive Pebble events and send commands")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            logger.w(e) { "cannot post consent notification (no POST_NOTIFICATIONS)" }
        }
    }

    companion object {
        const val ACTION_REVIEW = "coredevices.coreapp.automation.REVIEW_CLIENTS"
        private const val CHANNEL = "automation_consent"
        private const val NOTIF_ID = 0xA117
    }
}
