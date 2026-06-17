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
    onStrike: (Enemy) -> Unit,
    onRun: (Enemy) -> Unit,
    onUseSuper: (Enemy) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    // Announce battle results to TalkBack when they arrive
    LaunchedEffect(lastBattleResult) {
        lastBattleResult?.let {
            A11yAnnouncer.announce(context, it.toAnnouncement())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Hero status bar ────────────────────────────────────────────────
        heroStats?.let { hero ->
            HeroStatusCard(hero)
        }

        // ── Last battle result banner ──────────────────────────────────────
        lastBattleResult?.let { result ->
            BattleResultBanner(result)
        }

        // ── Enemy list header ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Enemies — ${heroStats?.location ?: ""}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    contentDescription = "Enemy list. Current location: ${heroStats?.location ?: "unknown"}."
                }
            )
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.semantics { contentDescription = A11yLabels.BUTTON_REFRESH }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
        }

        // ── Enemy list ─────────────────────────────────────────────────────
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = A11yLabels.LOADING }
                )
            }
        } else if (enemies.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No enemies here right now. Try refreshing or travel to another location.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics {
                        contentDescription = "No enemies currently present. Tap Refresh to check again, or go to Locations to travel."
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

// ── Hero Status Card ──────────────────────────────────────────────────────────

@Composable
fun HeroStatusCard(hero: HeroStats) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                // Single cohesive description for TalkBack instead of reading
                // each child individually
                contentDescription = hero.toContentDescription()
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(hero.name, style = MaterialTheme.typography.titleLarge)
                Text("Level ${hero.level}", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))

            // HP Bar
            LabeledProgressBar(
                label = A11yLabels.hpBarLabel(hero.hp, hero.maxHp),
                progress = hero.hpPercent,
                color = when {
                    hero.hpPercent < 0.25f -> MaterialTheme.colorScheme.error
                    hero.hpPercent < 0.5f  -> Color(0xFFF57C00) // Orange
                    else                   -> Color(0xFF388E3C) // Green
                }
            )
            Spacer(Modifier.height(4.dp))

            // XP Bar
            LabeledProgressBar(
                label = A11yLabels.xpBarLabel(hero.xp, hero.xpToNextLevel),
                progress = hero.xpPercent,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip("Power", hero.power.toString())
                StatChip("Drachma", hero.drachma.toString())
                StatChip("LP", hero.locationPoints.toString())
            }
        }
    }
}

@Composable
fun LabeledProgressBar(label: String, progress: Float, color: Color) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {
        contentDescription = label
    }) {
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

// ── Enemy Card ────────────────────────────────────────────────────────────────

@Composable
fun EnemyCard(
    enemy: Enemy,
    onStrike: () -> Unit,
    onRun: () -> Unit,
    onUseSuper: () -> Unit
) {
    val avengingColor = if (enemy.isAvenging)
        MaterialTheme.colorScheme.errorContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = avengingColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Enemy name + HP
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = enemy.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
                if (enemy.isAvenging) {
                    Text(
                        text = "AVENGE ×2",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = "HP: ${enemy.hp} / ${enemy.maxHp}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Battle action buttons — large touch targets, clear labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStrike,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .semantics {
                            contentDescription = A11yLabels.enemyAttackLabel(
                                enemy.name, enemy.tier,
                                enemy.hp, enemy.maxHp,
                                enemy.isAvenging
                            )
                        }
                ) {
                    Text("Strike")
                }

                OutlinedButton(
                    onClick = onUseSuper,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .semantics { contentDescription = A11yLabels.ACTION_SUPER }
                ) {
                    Text("Super")
                }

                OutlinedButton(
                    onClick = onRun,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .semantics { contentDescription = A11yLabels.ACTION_RUN }
                ) {
                    Text("Run")
                }
            }
        }
    }
}

// ── Battle Result Banner ──────────────────────────────────────────────────────

@Composable
fun BattleResultBanner(result: BattleResult) {
    val bgColor = when {
        result.enemyDefeated -> Color(0xFF388E3C)  // Green — victory
        result.playerRan     -> MaterialTheme.colorScheme.surfaceVariant
        else                 -> MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = bgColor,
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
