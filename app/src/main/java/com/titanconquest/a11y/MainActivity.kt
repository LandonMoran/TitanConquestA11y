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

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val a11yDescription: String
) {
    object Login    : Screen("login",     "Login",     Icons.Default.Lock,     "Login screen")
    object Patrol   : Screen("patrol",    "Patrol",    Icons.Default.Star,     "Patrol — battle enemies")
    object Hero     : Screen("hero",      "Hero",      Icons.Default.Person,   "My Hero — stats and gear")
    object Locations: Screen("locations", "Locations", Icons.Default.Place,    "Locations — travel")
    object Chat     : Screen("chat",      "Chat",      Icons.Default.Email,    "Chat — messages")
    object Bounties : Screen("bounties",  "Bounties",  Icons.Default.List,     "Bounties — daily missions")
}

val gameScreens = listOf(
    Screen.Patrol, Screen.Hero, Screen.Locations, Screen.Chat, Screen.Bounties
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitanConquestApp() {
    val navController = rememberNavController()
    var isLoggedIn by remember { mutableStateOf(false) }

    if (!isLoggedIn) {
        // Show login screen full-screen, no nav bar
        LoginScreen(
            isLoading = false,
            errorMessage = null,
            onLogin = { _, _ ->
                isLoggedIn = true
                // TODO: pass to ViewModel for real auth
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Titan Conquest",
                        modifier = Modifier.semantics {
                            contentDescription = "Titan Conquest Accessible Client"
                        }
                    )
                }
            )
        },
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            NavigationBar {
                gameScreens.forEach { screen ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == screen.route } == true

                    NavigationBarItem(
                        icon = {
                            Icon(screen.icon, contentDescription = null)
                        },
                        label = { Text(screen.label) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (selected)
                                "${screen.a11yDescription}, current tab"
                            else
                                screen.a11yDescription
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Patrol.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Patrol.route) {
                PatrolScreen(
                    heroStats = null,       // wired up via ViewModel in next step
                    enemies = emptyList(),
                    lastBattleResult = null,
                    isLoading = false,
                    onStrike = {},
                    onRun = {},
                    onUseSuper = {},
                    onRefresh = {}
                )
            }
            composable(Screen.Hero.route) {
                HeroScreen()
            }
            composable(Screen.Locations.route) {
                LocationsScreen()
            }
            composable(Screen.Chat.route) {
                ChatScreen()
            }
            composable(Screen.Bounties.route) {
                BountiesScreen()
            }
        }
    }
}
