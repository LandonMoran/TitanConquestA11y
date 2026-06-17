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
import com.titanconquest.a11y.model.*

@Composable
fun HeroScreen(
    hero: HeroStats?,
    gear: List<GearItem>,
    onLoadGear: () -> Unit
) {
    LaunchedEffect(Unit) { onLoadGear() }

    if (hero == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading hero stats" }
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Stats card ────────────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = hero.toContentDescription()
                    }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(hero.name, style = MaterialTheme.typography.headlineSmall)
                        Text("Lv ${hero.level}", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "${hero.heroClass.label} — ${hero.heroClass.bonusDesc}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()

                    StatRow("HP",      "${hero.hp} / ${hero.maxHp}")
                    StatRow("Attack",  "${hero.attack}%")
                    StatRow("Defense", "${hero.defense}%")
                    StatRow("Power",   hero.power.toString())
                    HorizontalDivider()
                    StatRow("Drachma",       hero.drachma.toString())
                    StatRow("Ancient Coins", hero.ancientCoins.toString())
                    StatRow("Vanguard Marks",hero.vanguardMarks.toString())
                    StatRow("Clan Marks",    hero.clanMarks.toString())
                    HorizontalDivider()
                    StatRow("Location",       hero.location)
                    StatRow("Location Points",hero.locationPoints.toString())
                }
            }
        }

        // ── XP progress ───────────────────────────────────────────────────────
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("XP Progress", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { hero.xpPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "XP: ${hero.xp} of ${hero.xpToNextLevel}. " +
                                    "${(hero.xpPercent * 100).toInt()} percent to level ${hero.level + 1}."
                            }
                    )
                    Text(
                        "${hero.xp} / ${hero.xpToNextLevel} XP",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Gear ─────────────────────────────────────────────────────────────
        item {
            Text("Equipped Gear", style = MaterialTheme.typography.titleMedium,
                 modifier = Modifier.padding(vertical = 4.dp))
        }

        if (gear.isEmpty()) {
            item {
                Text(
                    "No gear loaded. Visiting this screen will load your inventory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = "No gear data available. Please refresh."
                    }
                )
            }
        } else {
            items(gear.filter { it.isEquipped }) { item ->
                GearCard(item)
            }
            item {
                Text("Inventory", style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.padding(vertical = 4.dp))
            }
            items(gear.filter { !it.isEquipped }) { item ->
                GearCard(item)
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: $value"
            },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun GearCard(item: GearItem) {
    val rarityColor = Color(item.rarity.color)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = item.toContentDescription()
            },
        colors = CardDefaults.cardColors(
            containerColor = rarityColor.copy(alpha = 0.1f)
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(item.rarity.label, style = MaterialTheme.typography.labelSmall,
                     color = rarityColor)
            }
            Text("${item.slot.label}${item.setName?.let { " • $it set" } ?: ""}",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.perks.isNotEmpty()) {
                Text(
                    item.perks.joinToString("  ") { it.description },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (item.infusionLevel > 0) {
                Text("INF ${item.infusionLevel}", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}
