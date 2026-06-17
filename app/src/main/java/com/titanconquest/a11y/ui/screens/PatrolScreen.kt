package com.titanconquest.a11y.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.titanconquest.a11y.accessibility.A11yAnnouncer
import com.titanconquest.a11y.accessibility.A11yLabels
import com.titanconquest.a11y.model.*

@Composable
fun PatrolScreen(
    heroStats: HeroStats?,
    enemies: List<Enemy>,
    lastBattleResult: BattleResult?,
    isLoading: Boolean,
    errorMessage: String?,
    onStrike: (Enemy) -> Unit,
    onRun: (Enemy) -> Unit,
    onUseSuper: (Enemy) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(lastBattleResult) {
        lastBattleResult?.let { A11yAnnouncer.announce(context, it.toAnnouncement()) }
    }

    Column(Modifier.fillMaxSize()) {
        heroStats?.let { HeroStatusCard(it) }

        lastBattleResult?.let { BattleResultBanner(it) }

        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Error: $it" }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Enemies${heroStats?.location?.let { " — $it" } ?: ""}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    contentDescription = "Enemy list at ${heroStats?.location ?: "current location"}. ${enemies.size} enemies present."
                }
            )
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.semantics { contentDescription = "Refresh enemies" }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading, please wait" }
                )
            }
        } else if (enemies.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No enemies here right now. Tap Refresh to check again, or travel to another location.",
                    modifier = Modifier.semantics {
                        contentDescription = "No enemies at this location. Use the Refresh button or travel to a new area."
                    }
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(enemies, key = { it.id }) { enemy ->
                    EnemyCard(
                        enemy = enemy,
                        onStrike = { onStrike(enemy) },
                        onRun = { onRun(enemy) },
                        onUseSuper = { onUseSuper(enemy) }
                    )
                }
            }
        }
    }
}

@Composable
fun HeroStatusCard(hero: HeroStats) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = hero.toContentDescription()
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(hero.name, style = MaterialTheme.typography.titleLarge)
                Text("Lv ${hero.level}", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            LabeledProgressBar(
                label = "HP: ${hero.hp} / ${hero.maxHp}",
                progress = hero.hpPercent,
                color = when {
                    hero.hpPercent < 0.25f -> MaterialTheme.colorScheme.error
                    hero.hpPercent < 0.5f  -> Color(0xFFF57C00)
                    else                   -> Color(0xFF388E3C)
                }
            )
            Spacer(Modifier.height(4.dp))
            LabeledProgressBar(
                label = "XP: ${hero.xp} / ${hero.xpToNextLevel}",
                progress = hero.xpPercent,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip("Power",   hero.power.toString())
                StatChip("Gold",    hero.drachma.toString())
                StatChip("LP",      hero.locationPoints.toString())
            }
        }
    }
}

@Composable
fun LabeledProgressBar(label: String, progress: Float, color: Color) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = color
        )
    }
}

@Composable
fun StatChip(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = "$label: $value" }
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EnemyCard(enemy: Enemy, onStrike: () -> Unit, onRun: () -> Unit, onUseSuper: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enemy.isAvenging) MaterialTheme.colorScheme.errorContainer
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(enemy.displayName, style = MaterialTheme.typography.titleMedium)
                if (enemy.isAvenging) {
                    Text("AVENGE ×2", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error)
                }
            }
            LinearProgressIndicator(
                progress = { enemy.hpPercent },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics {
                    contentDescription = "Enemy HP: ${enemy.hp} of ${enemy.maxHp}"
                },
                color = Color(0xFFEF5350)
            )
            Text("HP: ${enemy.hp} / ${enemy.maxHp}", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStrike,
                    modifier = Modifier.weight(1f).height(56.dp).semantics {
                        contentDescription = A11yLabels.enemyAttackLabel(
                            enemy.name, enemy.tier, enemy.hp, enemy.maxHp, enemy.isAvenging)
                    }
                ) { Text("Strike") }
                OutlinedButton(
                    onClick = onUseSuper,
                    modifier = Modifier.weight(1f).height(56.dp).semantics {
                        contentDescription = "Use super ability on ${enemy.displayName}"
                    }
                ) { Text("Super") }
                OutlinedButton(
                    onClick = onRun,
                    modifier = Modifier.weight(1f).height(56.dp).semantics {
                        contentDescription = "Run from ${enemy.displayName}. HP will restore."
                    }
                ) { Text("Run") }
            }
        }
    }
}

@Composable
fun BattleResultBanner(result: BattleResult) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = if (result.enemyDefeated) Color(0xFF388E3C)
                else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = result.toAnnouncement(),
            modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = if (result.enemyDefeated) Color.White else MaterialTheme.colorScheme.onSurface
        )
    }
}
