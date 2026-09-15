package com.icegood.findmeinwood.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.NetworkId

/**
 * FR-8.2/FR-8.3: networks heard around us with name, owner and policy — never a member
 * count. OPEN networks warn that the secret is readable by anyone in range.
 */
@Composable
fun DiscoverScreen(context: android.content.Context, onJoin: (NetworkId) -> Unit) {
    val discovered by SessionBus.discovered.collectAsState()
    val pending by SessionBus.pendingJoins.collectAsState()
    val sas by SessionBus.ownerSas.collectAsState()
    val note by SessionBus.ownerNote.collectAsState()
    val outcome by SessionBus.joinOutcome.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Networks around you", style = MaterialTheme.typography.titleMedium)
        if (discovered.isEmpty()) {
            Text(
                "Listening for HELLO frames… (start a session so your phone can hear others)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        discovered.forEach { net ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(net.name ?: "(hidden)", style = MaterialTheme.typography.titleMedium)
                    Text("policy ${net.policy} · transports ${net.activeTransports.joinToString()}",
                        style = MaterialTheme.typography.labelSmall)
                    if (net.policy == JoinPolicy.OPEN) {
                        Text(
                            "OPEN: the network secret is readable by anyone in range.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onJoin(net.networkId)
                        }) { Text("Ask to join") }
                    }
                }
            }
        }

        if (sas != null || note != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sas?.let { Text("SAS $it — compare with the joiner", style = MaterialTheme.typography.bodyMedium) }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (pending.isNotEmpty()) {
            Text("Join requests (you are the owner)", style = MaterialTheme.typography.titleMedium)
            pending.forEach { req ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(req.joinerNick ?: "member ${req.joinerId.hex().take(8)}",
                            style = MaterialTheme.typography.titleSmall)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { DiscoveryBridge.approve(req.joinerId) }) { Text("Accept") }
                            OutlinedButton(onClick = { DiscoveryBridge.reject(req.joinerId) }) { Text("Reject") }
                        }
                    }
                }
            }
        }

        outcome?.let { result ->
            LaunchedEffect(result) {
                when (result) {
                    is com.icegood.findmeinwood.core.session.JoinOutcome.Joined -> {
                        val joined = ProfileStore.join(ProfileStore.joinCodeOf(result.name, result.secret))
                        if (joined is JoinResult.Ok) {
                            ProfileStore.add(context, joined.profile)
                            Prefs.setSecretFor(context, joined.profile.networkIdHex, result.secret)
                            // FR-8.4: unverified until the human compares the SAS
                            Prefs.setUnverified(context, joined.profile.networkIdHex, true)
                        }
                    }
                    is com.icegood.findmeinwood.core.session.JoinOutcome.Rejected -> Unit
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    when (result) {
                        is com.icegood.findmeinwood.core.session.JoinOutcome.Joined ->
                            Text("Joined “${result.name}” — SAS ${result.sas} (compare with the owner)")
                        is com.icegood.findmeinwood.core.session.JoinOutcome.Rejected ->
                            Text("Join rejected: ${result.reason}")
                    }
                }
            }
        }
    }
}

private fun com.icegood.findmeinwood.core.model.MemberId.hex(): String =
    bytes.joinToString("") { "%02x".format(it) }
