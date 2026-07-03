package dev.zig.notificationfilter.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zig.notificationfilter.ui.theme.ZigGreen
import dev.zig.notificationfilter.ui.theme.ZigOnGreen

@Composable
fun TermsAndConditionsScreen(onAccepted: () -> Unit) {
    val context = LocalContext.current
    BackHandler { (context as? ComponentActivity)?.finish() }

    // Surface sets both the background colour and LocalContentColor so that Text composables
    // with no explicit colour use the correct on-surface token in light and dark mode.
    // enableEdgeToEdge() draws under system bars; windowInsetsPadding keeps content clear of them.
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 24.dp, bottom = 16.dp),
            ) {
                Text(
                    text = "Before You Begin",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "ZiG is built for privacy — not data collection. Please read this before continuing.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(36.dp))

                TermsBullet(
                    icon = Icons.Default.Lock,
                    title = "No internet access",
                    body = "ZiG cannot communicate with the internet. This restriction is enforced at the application level — not just a setting that can be toggled.",
                )
                TermsBullet(
                    icon = Icons.Default.Phone,
                    title = "100% on-device AI",
                    body = "Every classification decision runs locally on your phone using an embedded model. Your notifications never leave your device.",
                )
                TermsBullet(
                    icon = Icons.Default.Person,
                    title = "Your preferences stay on your phone",
                    body = "When you allow or block a notification, that decision is stored locally to make ZiG smarter for you specifically. This data is never uploaded, shared, or transmitted anywhere.",
                )
                TermsBullet(
                    icon = Icons.Default.Delete,
                    title = "Automatic expiry",
                    body = "All stored notification records — including your allow/block decisions — are automatically deleted after 30 days.",
                )
                TermsBullet(
                    icon = Icons.Default.CheckCircle,
                    title = "No analytics, no ads, no crash reporting",
                    body = "Nothing is tracked. There are no third-party SDKs, no remote logging, and no telemetry of any kind.",
                )
            }

            HorizontalDivider()
            Button(
                onClick = onAccepted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZigGreen,
                    contentColor = ZigOnGreen,
                ),
            ) {
                Text("I Agree", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun TermsBullet(icon: ImageVector, title: String, body: String) {
    Row(modifier = Modifier.padding(bottom = 28.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ZigGreen,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
