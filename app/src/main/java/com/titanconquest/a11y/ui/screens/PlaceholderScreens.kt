package com.titanconquest.a11y.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// ── Hero Screen ───────────────────────────────────────────────────────────────

@Composable
fun HeroScreen() {
    PlaceholderScreen(
        title = "My Hero",
        description = "Hero stats and gear management. Coming soon.",
        a11yDescription = "My Hero screen. Displays your hero's stats, gear, and infusion options. Currently under development."
    )
}

// ── Locations Screen ──────────────────────────────────────────────────────────

@Composable
fun LocationsScreen() {
    PlaceholderScreen(
        title = "Locations",
        description = "Travel to a different area.",
        a11yDescription = "Locations screen. Lists all areas you can travel to. Currently under development."
    )
}

// ── Chat Screen ───────────────────────────────────────────────────────────────

@Composable
fun ChatScreen() {
    PlaceholderScreen(
        title = "Chat",
        description = "Global and clan chat messages.",
        a11yDescription = "Chat screen. Shows global and clan chat. Currently under development."
    )
}

// ── Bounties Screen ───────────────────────────────────────────────────────────

@Composable
fun BountiesScreen() {
    PlaceholderScreen(
        title = "Bounties",
        description = "Daily missions for Vanguard Marks and rewards.",
        a11yDescription = "Bounties screen. Shows your daily missions. Currently under development."
    )
}

// ── Shared placeholder ────────────────────────────────────────────────────────

@Composable
private fun PlaceholderScreen(
    title: String,
    description: String,
    a11yDescription: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .semantics { contentDescription = a11yDescription },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
