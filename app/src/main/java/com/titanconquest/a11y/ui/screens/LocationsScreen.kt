package com.titanconquest.a11y.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.titanconquest.a11y.model.Location

@Composable
fun LocationsScreen(
    locations: List<Location>,
    currentLocation: String,
    isLoading: Boolean,
    onLoadLocations: () -> Unit,
    onTravel: (Location) -> Unit
) {
    LaunchedEffect(Unit) { onLoadLocations() }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Current: $currentLocation",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(16.dp)
                .semantics { contentDescription = "Current location: $currentLocation" }
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading locations" }
                )
            }
        } else if (locations.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No locations loaded. Pull to refresh.",
                    modifier = Modifier.semantics {
                        contentDescription = "No locations available. Refresh to try again."
                    }
                )
            }
        } else {
            val unlocked = locations.filter { it.isUnlocked }
            val locked   = locations.filter { !it.isUnlocked }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (unlocked.isNotEmpty()) {
                    item {
                        Text("Available Locations", style = MaterialTheme.typography.titleMedium,
                             modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(unlocked, key = { it.id }) { loc ->
                        LocationCard(
                            location = loc,
                            isCurrent = loc.name == currentLocation,
                            onTravel = { onTravel(loc) }
                        )
                    }
                }
                if (locked.isNotEmpty()) {
                    item {
                        Text("Locked Locations", style = MaterialTheme.typography.titleMedium,
                             modifier = Modifier.padding(vertical = 4.dp),
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(locked, key = { it.id }) { loc ->
                        LocationCard(location = loc, isCurrent = false, onTravel = {})
                    }
                }
            }
        }
    }
}

@Composable
fun LocationCard(location: Location, isCurrent: Boolean, onTravel: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = location.toContentDescription()
            },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrent -> MaterialTheme.colorScheme.primaryContainer
                !location.isUnlocked -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(location.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        isCurrent -> "Current location"
                        !location.isUnlocked -> "Requires ${location.locationPointsRequired} LP"
                        else -> "${location.enemyCount} enemies • Level ${location.recommendedLevel}+"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (location.isUnlocked && !isCurrent) {
                Button(
                    onClick = onTravel,
                    modifier = Modifier
                        .height(48.dp)
                        .semantics { contentDescription = "Travel to ${location.name}" }
                ) {
                    Text("Travel")
                }
            }
        }
    }
}
