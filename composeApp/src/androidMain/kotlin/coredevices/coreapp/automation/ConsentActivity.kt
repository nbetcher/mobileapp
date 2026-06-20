package coredevices.coreapp.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.coreapp.automation.trust.ConsentController
import org.koin.android.ext.android.inject
import theme.AppTheme

/**
 * Host-app "Automation access" screen (HLDD-001 §6, §10, PLAN §5.4). Bound to the bridge's
 * [ConsentController] (clients + master switch) and [AutomationSettings] (event categories +
 * notification content), both injected. Reached from the Pebble app's Settings > Automation entry and
 * from the bridge's consent notification (REVIEW_CLIENTS).
 *
 * Themed with the app's [AppTheme] and laid out in a [Scaffold] (so it sits below the status bar, not
 * edge-to-edge). Surfaces: the master toggle, the shared event-category toggles, the notification-content
 * + redaction opt-ins (content default OFF), the dangerous-commands toggle, and approve/deny/revoke of
 * automation clients with a per-client command tier.
 */
class ConsentActivity : ComponentActivity() {
    private val consent: ConsentController by inject()
    private val settings: AutomationSettings by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                ConsentScreen(consent, settings, onBack = { finish() })
            }
        }
    }
}

private val DEFAULT_GRANT = setOf("connectivity", "apps", "media")

/** Command tiers the user can grant a client at approval (value -> label). Events are not tier-gated. */
private val TIERS = listOf("normal" to "Normal", "sensitive" to "Sensitive", "dangerous" to "Dangerous")

private fun categoryLabel(id: String): String = when (id) {
    AutomationSettings.CATEGORY_CONNECTIVITY -> "Connectivity (connect, battery)"
    AutomationSettings.CATEGORY_NOTIFICATIONS -> "Notifications"
    AutomationSettings.CATEGORY_APPS -> "Apps & watchfaces"
    AutomationSettings.CATEGORY_TIMELINE -> "Timeline actions (pins, calendar)"
    AutomationSettings.CATEGORY_MEDIA -> "Media controls"
    AutomationSettings.CATEGORY_CALLS -> "Calls"
    AutomationSettings.CATEGORY_HEALTH -> "Health"
    AutomationSettings.CATEGORY_SYSTEM -> "System & errors"
    else -> id
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentScreen(consent: ConsentController, settings: AutomationSettings, onBack: () -> Unit) {
    val master by consent.masterEnabled.collectAsState()
    val pending by consent.pending.collectAsState()
    val approved by consent.clients.collectAsState()
    val categories by settings.categories.collectAsState()
    val contentOn by settings.notificationContentEnabled.collectAsState()
    val redact by settings.redactNotificationContent.collectAsState()
    val dangerousOn by consent.dangerousCommandsEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Automation access") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable automation bridge", Modifier.weight(1f))
                Switch(checked = master, onCheckedChange = { consent.setMasterEnabled(it) })
            }
            Spacer(Modifier.height(16.dp))

            // --- Shared event categories (PLAN §5.4). Disabled categories never leave the app. --
            Text("Shared event categories", style = MaterialTheme.typography.titleMedium)
            Text(
                "Only the categories you enable are sent to automation clients.",
                style = MaterialTheme.typography.bodySmall,
            )
            AutomationSettings.CATEGORIES.forEach { cat ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(categoryLabel(cat), Modifier.weight(1f))
                    Switch(
                        checked = categories[cat] ?: true,
                        enabled = master,
                        onCheckedChange = { settings.setCategoryEnabled(cat, it) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // --- Notification content (default OFF; contents leave this app when enabled). ------
            Text("Notification content", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Share notification text", Modifier.weight(1f))
                Switch(
                    checked = contentOn,
                    enabled = master,
                    onCheckedChange = { settings.setNotificationContentEnabled(it) },
                )
            }
            if (contentOn) {
                Text(
                    "Notification title and body will leave this app and reach the automation client.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Redact body (send title + package only)", Modifier.weight(1f))
                    Switch(
                        checked = redact,
                        enabled = master,
                        onCheckedChange = { settings.setRedactNotificationContent(it) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // --- Commands (PLAN §5.5). Dangerous-tier commands need THIS toggle AND a per-client
            // dangerous grant; normal/sensitive only need the per-client tier set at approval. -----
            Text("Commands", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow dangerous commands")
                    Text(
                        "Lets dangerous-tier clients run destructive actions (e.g. toggling the " +
                            "developer connection). Off by default; also requires granting a client the " +
                            "dangerous tier below.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = dangerousOn,
                    enabled = master,
                    onCheckedChange = { consent.setDangerousCommandsEnabled(it) },
                )
            }
            Spacer(Modifier.height(16.dp))

            Text("Pending requests", style = MaterialTheme.typography.titleMedium)
            if (pending.isEmpty()) Text("None")
            pending.forEach { p ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.packageName, style = MaterialTheme.typography.bodyLarge)
                        Text("cert ${p.certSha256Hex.take(16)}…", style = MaterialTheme.typography.bodySmall)
                        Text("source: ${p.installSource ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        // Command tier this client may invoke (events are NOT tier-gated). A clear
                        // single-choice selector — distinct from the Approve/Deny action buttons below —
                        // defaulting to Normal. Higher tiers are opt-in; dangerous also needs the toggle above.
                        Text("Command tier", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        var tier by remember(p.packageName) { mutableStateOf("normal") }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            TIERS.forEachIndexed { index, pair ->
                                SegmentedButton(
                                    selected = tier == pair.first,
                                    onClick = { tier = pair.first },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TIERS.size),
                                ) { Text(pair.second) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Button(onClick = {
                                consent.approve(p.packageName, p.packageName, p.certSha256Hex, DEFAULT_GRANT, tier)
                            }) { Text("Approve") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { consent.deny(p.packageName) }) { Text("Deny") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Approved clients", style = MaterialTheme.typography.titleMedium)
            if (approved.isEmpty()) Text("None")
            approved.values.forEach { c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                        Text("${c.categories.joinToString()} · ${c.tier}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { consent.revoke(c.packageName) }) { Text("Revoke") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
