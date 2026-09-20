package coredevices.coreapp.automation.trust

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import coredevices.coreapp.automation.ClientRecord
import kotlin.time.Clock

class ConsentController(
    private val context: Context,
    private val trustStore: ClientTrustStore,
    private val inspector: PackageInspector = AndroidPackageInspector(context.packageManager),
) {
    val pending get() = trustStore.pending
    val clients get() = trustStore.clients
    val denied get() = trustStore.denied
    val masterEnabled get() = trustStore.masterEnabled
    val dangerousCommandsEnabled get() = trustStore.dangerousCommandsEnabled
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun setMasterEnabled(on: Boolean) = trustStore.setMasterEnabled(on)
    fun setDangerousCommandsEnabled(on: Boolean) = trustStore.setDangerousCommandsEnabled(on)

    @Synchronized fun onUnknownClient(pkg: String, certSha256Hex: String, installSource: String?) {
        trustStore.addPending(PendingClient(pkg, certSha256Hex, installSource))
        val request = pending.value.firstOrNull { it.packageName == pkg && it.certSha256Hex == certSha256Hex } ?: return
        if (!request.alerted && notifyReview()) trustStore.markAlerted(pkg, certSha256Hex)
    }

    @Synchronized fun approve(pkg: String, label: String, certSha256Hex: String, categories: Set<String>, tier: String): Boolean {
        val current = inspector.signingCertSha256(pkg)?.toHex() ?: return false
        if (current != certSha256Hex) {
            onUnknownClient(pkg, current, inspector.installSource(pkg))
            return false
        }
        trustStore.approve(ClientRecord(pkg, current, label, categories, tier, Clock.System.now().toEpochMilliseconds()))
        reconcileNotifications()
        return true
    }

    @Synchronized fun deny(pkg: String) {
        trustStore.deny(pkg)
        reconcileNotifications()
    }
    @Synchronized fun revoke(pkg: String) {
        trustStore.revoke(pkg)
        reconcileNotifications()
    }
    @Synchronized fun reconsider(pkg: String) {
        val cert = inspector.signingCertSha256(pkg)?.toHex() ?: return
        trustStore.reconsider(pkg)
        trustStore.addPending(PendingClient(pkg, cert, inspector.installSource(pkg), alerted = true))
        reconcileNotifications()
    }

    fun notificationsAvailable(): Boolean {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Automation access", NotificationManager.IMPORTANCE_HIGH))
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    @Synchronized fun reconcileNotifications() {
        if (pending.value.isEmpty()) manager.cancel(NOTIF_ID)
        else if (manager.activeNotifications.any { it.id == NOTIF_ID }) notifyReview()
    }

    private fun notifyReview(): Boolean {
        if (!notificationsAvailable()) return false
        val count = pending.value.size
        if (count == 0) return false
        val pi = PendingIntent.getActivity(context, 0,
            Intent(ACTION_REVIEW).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Allow automation access?")
            .setContentText(if (count == 1) "${pending.value.first().packageName} is requesting access" else "$count apps are requesting access")
            .setContentIntent(pi).setOnlyAlertOnce(true).setAutoCancel(true).build()
        return runCatching { manager.notify(NOTIF_ID, notification); true }.getOrDefault(false)
    }

    companion object {
        const val ACTION_REVIEW = "coredevices.coreapp.automation.REVIEW_CLIENTS"
        private const val CHANNEL = "automation_consent"
        private const val NOTIF_ID = 0xA117
    }
}
