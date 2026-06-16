package coredevices.coreapp.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.coreapp.automation.trust.ConsentController
import org.koin.android.ext.android.inject

/**
 * Host-app review screen for automation client consent (HLDD-001 §6, §10). Bound to the bridge's
 * [ConsentController] (injected). Launched by the bridge's consent notification (ACTION_REVIEW).
 *
 * Basic Phase-1 UI: master toggle, approve/deny pending, revoke approved. Approve grants a default
 * non-sensitive category set; granular per-category grants and the notification-content/health
 * opt-ins are a later refinement.
 */
class ConsentActivity : ComponentActivity() {
    private val consent: ConsentController by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ConsentScreen(consent) }
    }
}

private val DEFAULT_GRANT = setOf("connectivity", "apps", "media")

@Composable
private fun ConsentScreen(consent: ConsentController) {
    val master by consent.masterEnabled.collectAsState()
    val pending by consent.pending.collectAsState()
    val approved by consent.clients.collectAsState()

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                Text("Automation access", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable automation bridge", Modifier.weight(1f))
                    Switch(checked = master, onCheckedChange = { consent.setMasterEnabled(it) })
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
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Button(onClick = {
                                    consent.approve(p.packageName, p.packageName, p.certSha256Hex, DEFAULT_GRANT, "normal")
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
            }
        }
    }
}
