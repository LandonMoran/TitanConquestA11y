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
    preferredAttack: AttackType,
    onAttack: (Enemy) -> Unit,
    onRun: (Enemy) -> Unit,
    onUseSuper: (Enemy) -> Unit,
    onRefresh: () -> Unit,
    onSetAttackType: (AttackType) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(lastBattleResult) {
        lastBattleResult?.let { A11yAnnouncer.announce(context, it.toAnnouncement()) }
    }

    Column(Modifier.fillMaxSize()) {

        // ── Hero status card ───────────────────────────────────────────────────
        heroStats?.let { HeroStatusCard(it) }

        // ── Battle result banner ───────────────────────────────────────────────
        lastBattleResult?.let { BattleResultBanner(it) }

        // ── Error message ──────────────────────────────────────────────────────
        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Error: $it" }
            )
        }

        // ── Attack type selector ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AttackType.values().forEach { type ->
                FilterChip(
                    selected = preferredAttack == type,
                    onClick = { onSetAttackType(type) },
                    label = { Text(type.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = if (preferredAttack == type)
                                "${type.label} attack selected. ${type.description}"
                            else
                                "Switch to ${type.label} attack. ${type.description}"
                        }
                )
            }
        }

        // ── Enemy list header ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${enemies.size} ${if (enemies.size == 1) "enemy" else "enemies"}" +
                    heroStats?.location?.let { " at $it" }.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    contentDescription = "${enemies.size} enemies at ${heroStats?.location ?: "current location"}."
                }
            )
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.semantics { contentDescription = "Refresh enemy list" }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
        }

        // ── Content ────────────────────────────────────────────────────────────
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading enemies, please wait" }
                )
            }

            enemies.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No enemies here right now. Tap Refresh or travel to another location.",
                    modifier = Modifier.semantics {
                        contentDescription = "No enemies at this location. Use Refresh or go to Locations tab."
                    }
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Avenging enemies first — accessibility priority
                val avengers = enemies.filter { it.isAvenging }
                val normal   = enemies.filter { !it.isAvenging }

                if (avengers.isNotEmpty()) {
                    item {
                        Text(
                            "⚡ Avenging — Double Rewards",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics {
                                contentDescription = "Avenging enemies below give double XP and Drachma rewards."
                            }
                        )
                    }
                    items(avengers, key = { it.id }) { enemy ->
                        EnemyCard(enemy, preferredAttack, onAttack, onRun, onUseSuper)
                    }
                }
                if (normal.isNotEmpty()) {
                    if (avengers.isNotEmpty()) item {
                        Text("Enemies", style = MaterialTheme.typography.labelLarge,
                             modifier = Modifier.padding(top = 4.dp))
                    }
                    items(normal, key = { it.id }) { enemy ->
                        EnemyCard(enemy, preferredAttack, onAttack, onRun, onUseSuper)
                    }
                }
            }
        }
    }
}

// ── Hero Status Card ──────────────────────────────────────────────────────────

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
                Column {
                    Text(hero.name, style = MaterialTheme.typography.titleLarge)
                    Text(hero.heroClass.label,
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Lv ${hero.level}", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))

            // HP bar — colour changes by percentage
            val hpColor = when {
                hero.hpPercent < 0.25f -> MaterialTheme.colorScheme.error
                hero.hpPercent < 0.5f  -> Color(0xFFF57C00)
                else                   -> Color(0xFF388E3C)
            }
            Column(modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "HP: ${hero.hp} of ${hero.maxHp}, ${(hero.hpPercent * 100).toInt()} percent"
            }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("HP", style = MaterialTheme.typography.labelSmall)
                    Text("${hero.hp} / ${hero.maxHp}", style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(
                    progress = { hero.hpPercent },
                    modifier = Modifier.fillMaxWidth(),
                    color = hpColor
                )
            }

            Spacer(Modifier.height(4.dp))

            // XP bar
            Column(modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "XP: ${hero.xp} of ${hero.xpToNextLevel} to next level, ${(hero.xpPercent * 100).toInt()} percent"
            }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("XP", style = MaterialTheme.typography.labelSmall)
                    Text("${hero.xp} / ${hero.xpToNextLevel}", style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(
                    progress = { hero.xpPercent },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip("Power",   hero.power.toString())
                StatChip("Drachma", hero.drachma.toString())
                StatChip("LP",      hero.locationPoints.toString())
            }
        }
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

// ── Enemy Card ────────────────────────────────────────────────────────────────

@Composable
fun EnemyCard(
    enemy: Enemy,
    preferredAttack: AttackType,
    onAttack: (Enemy) -> Unit,
    onRun: (Enemy) -> Unit,
    onUseSuper: (Enemy) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enemy.isAvenging) MaterialTheme.colorScheme.errorContainer
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            // Name + avenging badge
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(enemy.displayName, style = MaterialTheme.typography.titleMedium)
                if (enemy.isAvenging) {
                    Text("×2 REWARDS",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error)
                }
            }

            // Enemy HP bar
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Enemy HP: ${enemy.hp} of ${enemy.maxHp}"
            }) {
                LinearProgressIndicator(
                    progress = { enemy.hpPercent },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFEF5350)
                )
                Text("${enemy.hp} / ${enemy.maxHp} HP",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(12.dp))

            // Attack button — label reflects current attack type
            Button(
                onClick = { onAttack(enemy) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics {
                        contentDescription = "${preferredAttack.label} attack on ${enemy.displayName}. " +
                            "HP: ${enemy.hp} of ${enemy.maxHp}." +
                            if (enemy.isAvenging) " Double rewards." else ""
                    }
            ) {
                Text("${preferredAttack.label} Attack")
            }

            Spacer(Modifier.height(6.dp))

            // Super + Run row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onUseSuper(enemy) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics {
                            contentDescription = "Use Super ability on ${enemy.displayName}. " +
                                "Deals 10× primary damage."
                        }
                ) { Text("Super") }

                OutlinedButton(
                    onClick = { onRun(enemy) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics {
                            contentDescription = "Run from ${enemy.displayName}. Your HP will be fully restored."
                        }
                ) { Text("Run") }
            }
        }
    }
}

// ── Battle Result Banner ──────────────────────────────────────────────────────

@Composable
fun BattleResultBanner(result: BattleResult) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = when {
            result.enemyDefeated -> Color(0xFF388E3C)
            result.playerRan     -> MaterialTheme.colorScheme.surfaceVariant
            else                 -> MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = result.toAnnouncement(),
            modifier = Modifier
                .padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = if (result.enemyDefeated) Color.White
                    else MaterialTheme.colorScheme.onSurface
        )
    }
}
