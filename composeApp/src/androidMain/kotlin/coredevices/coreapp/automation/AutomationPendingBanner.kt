package coredevices.coreapp.automation

import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coredevices.coreapp.automation.trust.ConsentController
import theme.AppTheme

@Composable
fun AutomationPendingBanner(consent: ConsentController) {
    val pending by consent.pending.collectAsState()
    val context = LocalContext.current
    if (pending.isNotEmpty()) AppTheme {
        Surface(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pending.size} pending automation access request(s)", Modifier.weight(1f))
                TextButton(onClick = { context.startActivity(Intent(context, ConsentActivity::class.java)) }) {
                    Text("Review")
                }
            }
        }
    }
}
