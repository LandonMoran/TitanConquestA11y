package com.titanconquest.a11y

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.titanconquest.a11y.ui.screens.*
import com.titanconquest.a11y.ui.theme.TitanConquestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TitanConquestTheme {
                TitanConquestApp()
            }
        }
    }
}

sealed class Screen(val route: String, val label: String, val icon: ImageVector, val a11y: String) {
    object Patrol    : Screen("patrol",    "Patrol",    Icons.Default.Star,   "Patrol — battle enemies")
    object Hero      : Screen("hero",      "Hero",      Icons.Default.Person, "My Hero — stats and gear")
    object Locations : Screen("locations", "Locations", Icons.Default.Place,  "Locations — travel to new areas")
    object Chat      : Screen("chat",      "Chat",      Icons.Default.Email,  "Chat — global and clan messages")
    object Bounties  : Screen("bounties",  "Bounties",  Icons.Default.List,   "Bounties — daily missions")
}

val GAME_SCREENS = listOf(Screen.Patrol, Screen.Hero, Screen.Locations, Screen.Chat, Screen.Bounties)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitanConquestApp() {
    val vm: GameViewModel = viewModel()
    val state by vm.state.collectAsState()
    val navController = rememberNavController()

    // Show login screen if not authenticated
    if (!state.isLoggedIn) {
        LoginScreen(
            isLoading = state.isLoading,
            errorMessage = state.loginError,
            onLogin = { username, password -> vm.login(username, password) }
        )
        return
    }

    // Status snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Titan Conquest",
                        modifier = Modifier.semantics {
                            contentDescription = "Titan Conquest — Accessible Client. " +
                                (state.hero?.let { "Playing as ${it.name}, Level ${it.level}." } ?: "")
                        }
                    )
                },
                actions = {
                    TextButton(
                        onClick = { vm.logout() },
                        modifier = Modifier.semantics { contentDescription = "Log out" }
                    ) { Text("Logout") }
                }
            )
        },
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val current = navBackStackEntry?.destination
            NavigationBar {
                GAME_SCREENS.forEach { screen ->
                    val selected = current?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (selected) "${screen.a11y}, current"
                                                 else screen.a11y
                        }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Patrol.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Patrol.route) {
                PatrolScreen(
                    heroStats = state.hero,
                    enemies = state.enemies,
                    lastBattleResult = state.lastBattleResult,
                    isLoading = state.isLoading,
                    errorMessage = state.patrolError,
                    onStrike = { vm.strike(it) },
                    onRun = { vm.runFromBattle(it) },
                    onUseSuper = { vm.useSuper(it) },
                    onRefresh = { vm.refreshPatrol() }
                )
            }
            composable(Screen.Hero.route) {
                HeroScreen(
                    hero = state.hero,
                    gear = state.gear,
                    onLoadGear = { vm.loadGear() }
                )
            }
            composable(Screen.Locations.route) {
                LocationsScreen(
                    locations = state.locations,
                    currentLocation = state.hero?.location ?: "Unknown",
                    isLoading = state.isLoading,
                    onLoadLocations = { vm.loadLocations() },
                    onTravel = { vm.travel(it) }
                )
            }
            composable(Screen.Chat.route) {
                ChatScreen(
                    globalMessages = state.globalChat,
                    clanMessages = state.clanChat,
                    onLoadChat = { clan -> vm.loadChat(clan) },
                    onSend = { msg, clan -> vm.sendChat(msg, clan) }
                )
            }
            composable(Screen.Bounties.route) {
                BountiesScreen(
                    bounties = state.bounties,
                    isLoading = state.isLoading,
                    onLoad = { vm.loadBounties() },
                    onAccept = { vm.acceptBounty(it) },
                    onClaim = { vm.claimBounty(it) }
                )
            }
        }
    }
}
