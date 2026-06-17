package com.titanconquest.a11y.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.titanconquest.a11y.model.Bounty

@Composable
fun BountiesScreen(
    bounties: List<Bounty>,
    isLoading: Boolean,
    onLoad: () -> Unit,
    onAccept: (Bounty) -> Unit,
    onClaim: (Bounty) -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading bounties" }
            )
        }
        return
    }

    if (bounties.isEmpty()) {
        Box(
            Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No bounties available right now. Check back tomorrow or reach level 20.",
                modifier = Modifier.semantics {
                    contentDescription = "No bounties available. Bounties unlock at level 20 and reset daily."
                }
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val completed = bounties.filter { it.isCompleted }
        val inProgress = bounties.filter { it.isAccepted && !it.isCompleted }
        val available = bounties.filter { !it.isAccepted }

        if (completed.isNotEmpty()) {
            item { SectionHeader("Ready to Claim (${completed.size})") }
            items(completed, key = { it.id }) { BountyCard(it, onAccept, onClaim) }
        }
        if (inProgress.isNotEmpty()) {
            item { SectionHeader("In Progress (${inProgress.size})") }
            items(inProgress, key = { it.id }) { BountyCard(it, onAccept, onClaim) }
        }
        if (available.isNotEmpty()) {
            item { SectionHeader("Available (${available.size})") }
            items(available, key = { it.id }) { BountyCard(it, onAccept, onClaim) }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun BountyCard(bounty: Bounty, onAccept: (Bounty) -> Unit, onClaim: (Bounty) -> Unit) {
    val bgColor = when {
        bounty.isCompleted -> Color(0xFF1B5E20).copy(alpha = 0.1f)
        bounty.isAccepted  -> MaterialTheme.colorScheme.secondaryContainer
        else               -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = bounty.toContentDescription()
            },
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(bounty.description, style = MaterialTheme.typography.bodyLarge)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Reward: ${bounty.reward}", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(bounty.timeLimit, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Progress bar
            if (bounty.isAccepted) {
                LinearProgressIndicator(
                    progress = { bounty.progressPercent },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Progress: ${bounty.progress} of ${bounty.goal}"
                    }
                )
                Text(
                    "${bounty.progress} / ${bounty.goal}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action button
            when {
                bounty.isCompleted -> Button(
                    onClick = { onClaim(bounty) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "Claim reward for: ${bounty.description}" }
                ) { Text("Claim Reward") }

                !bounty.isAccepted -> OutlinedButton(
                    onClick = { onAccept(bounty) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "Accept bounty: ${bounty.description}" }
                ) { Text("Accept") }
            }
        }
    }
}
