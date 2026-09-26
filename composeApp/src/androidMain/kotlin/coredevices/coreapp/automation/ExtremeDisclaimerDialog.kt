package coredevices.coreapp.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

private const val COUNTDOWN_SECONDS = 10
private val WarningRed = Color(0xFFB71C1C)

/**
 * Must be accepted before a client can be granted the extremely-dangerous tier. Accept unlocks only
 * after [COUNTDOWN_SECONDS] of the dialog being visible and focused: the countdown pauses whenever the
 * app is backgrounded or loses window focus. Cancel works at any time; tapping outside does nothing.
 */
@Composable
internal fun ExtremeDisclaimerDialog(onAccept: () -> Unit, onCancel: () -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnClickOutside = false)) {
        // Read inside the dialog: while it is showing, the dialog's own window is the focused one.
        val focused = LocalWindowInfo.current.isWindowFocused
        val running = focused && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
        var remaining by remember { mutableIntStateOf(COUNTDOWN_SECONDS) }
        LaunchedEffect(running) {
            while (running && remaining > 0) {
                delay(1_000)
                remaining--
            }
        }
        val view = LocalView.current
        DisposableEffect(view) {
            val previous = view.filterTouchesWhenObscured
            view.filterTouchesWhenObscured = true
            onDispose { view.filterTouchesWhenObscured = previous }
        }
        Surface(color = WarningRed, contentColor = Color.White, shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(24.dp)) {
                Text("WARNING: Please read!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Text(
                    "These are functions that either expose potentially very sensitive personal data to the " +
                        "Automation invoking it; allow it to permanently destroy user and system data without " +
                        "capability of restoration; or, in extreme edge cases, may leave your Pebble watch in an " +
                        "unrecoverable state (bricked).",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                        disabledContentColor = Color.White.copy(alpha = 0.5f),
                    )
                    OutlinedButton(onClick = onCancel, colors = colors) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onAccept, enabled = remaining == 0, colors = colors) {
                        Text(if (remaining > 0) "Accept ($remaining)" else "Accept")
                    }
                }
            }
        }
    }
}
