package com.icegood.findmeinwood.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Every member of the running network with the name and links from their own card, so a
 * phone knows how each member can be reached even before hearing them. On the master
 * this is also the admin page: join requests, and removing members.
 */
@Composable
internal fun MembersScreen(onBack: () -> Unit) {
    val book by SessionBus.book.collectAsState()
    val profile by SessionBus.profile.collectAsState()
    val peers by SessionBus.peers.collectAsState()
    val labels by MemberNames.labels.collectAsState()
    var removing by remember { mutableStateOf<BookMember?>(null) }
    val now = System.currentTimeMillis()

    fun nameOf(m: BookMember) = labels[MemberNaming.key(m.id)] ?: m.name
        ?: "member ${m.id.bytes.take(3).joinToString("") { "%02x".format(it) }}"

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Members of “${profile?.name ?: "network"}”", style = MaterialTheme.typography.titleMedium)
        }
        val b = book
        if (b == null) {
            Text("Use a network to see its members.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val master = b.members.firstOrNull { it.isMaster }
        Text(
            when {
                b.iAmMaster -> "You manage this network: only you can let people in or remove them."
                master != null -> "Managed by ${nameOf(master)}: only they can let people in or remove them."
                else -> "The member list has not reached this phone yet: it comes from any member nearby."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (b.iAmMaster) JoinRequests(profile?.name)
        b.members.forEach { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        nameOf(m) + listOfNotNull(
                            "master".takeIf { m.isMaster },
                            "you".takeIf { m.isMe },
                            "removed".takeIf { m.removed },
                        ).joinToString("") { " · $it" },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (m.removed) Color.Gray else Color.Unspecified,
                    )
                    if (!m.removed) {
                        Text(UiText.offers(m.offers), style = MaterialTheme.typography.bodySmall)
                        if (!m.isMe) Text(
                            UiText.heard(peers[m.id]?.lastSeenMs, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                    if (b.iAmMaster && !m.isMe && !m.removed) {
                        OutlinedButton(onClick = { removing = m }) { Text("Remove") }
                    }
                }
            }
        }
    }
    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${nameOf(m)}?") },
            text = {
                Text(
                    "Every member's phone will ignore them from now on. They keep the network " +
                        "password, so change networks if they must not read positions any more.",
                )
            },
            confirmButton = {
                TextButton(onClick = { SessionService.removeMember(m.id); removing = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
