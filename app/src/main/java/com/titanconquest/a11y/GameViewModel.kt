package com.titanconquest.a11y

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanconquest.a11y.model.*
import com.titanconquest.a11y.network.LoginException
import com.titanconquest.a11y.network.TitanConquestClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GameUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val loginError: String? = null,
    val hero: HeroStats? = null,
    val enemies: List<Enemy> = emptyList(),
    val lastBattleResult: BattleResult? = null,
    val preferredAttack: AttackType = AttackType.PRIMARY,
    val locations: List<Location> = emptyList(),
    val bounties: List<Bounty> = emptyList(),
    val missions: List<Bounty> = emptyList(),
    val globalChat: List<ChatMessage> = emptyList(),
    val clanChat: List<ChatMessage> = emptyList(),
    val gear: List<GearItem> = emptyList(),
    val statusMessage: String? = null,
    val patrolError: String? = null
)

class GameViewModel : ViewModel() {

    val client = TitanConquestClient()

    private val _state = MutableStateFlow(GameUiState())
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    // ── Auth ──────────────────────────────────────────────────────────────────

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, loginError = null)
            try {
                client.login(username, password)
                _state.value = _state.value.copy(isLoggedIn = true, isLoading = false)
                refreshPatrol()
            } catch (e: LoginException) {
                _state.value = _state.value.copy(isLoading = false, loginError = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    loginError = "Connection error: ${e.message}"
                )
            }
        }
    }

    fun logout() {
        client.logout()
        _state.value = GameUiState()
    }

    // ── Attack type preference ────────────────────────────────────────────────

    fun setAttackType(type: AttackType) {
        _state.value = _state.value.copy(preferredAttack = type)
    }

    // ── Patrol ────────────────────────────────────────────────────────────────

    fun refreshPatrol() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, patrolError = null)
            try {
                val (hero, enemies) = client.fetchPatrol()
                _state.value = _state.value.copy(
                    hero = hero ?: _state.value.hero,
                    enemies = enemies,
                    isLoading = false,
                    lastBattleResult = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    patrolError = "Could not load patrol: ${e.message}"
                )
            }
        }
    }

    fun attack(enemy: Enemy) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val result = when (_state.value.preferredAttack) {
                    AttackType.PRIMARY -> client.primaryAttack(enemy.id)
                    AttackType.SPECIAL -> client.specialAttack(enemy.id)
                    AttackType.HEAVY   -> client.heavyAttack(enemy.id)
                }
                applyBattleResult(result, enemy)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = "Attack failed: ${e.message}"
                )
            }
        }
    }

    fun useSuper(enemy: Enemy) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val result = client.useSuper(enemy.id)
                applyBattleResult(result, enemy)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = "Super failed: ${e.message}"
                )
            }
        }
    }

    fun run(enemy: Enemy) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val result = client.runFromBattle(enemy.id)
                val updatedHero = _state.value.hero?.copy(hp = result.heroHpAfter)
                _state.value = _state.value.copy(
                    isLoading = false,
                    lastBattleResult = result,
                    hero = updatedHero
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = "Run failed: ${e.message}"
                )
            }
        }
    }

    private suspend fun applyBattleResult(result: BattleResult, enemy: Enemy) {
        val newEnemies = if (result.enemyDefeated) {
            _state.value.enemies.filter { it.id != enemy.id }
        } else {
            _state.value.enemies.map {
                if (it.id == enemy.id) it.copy(hp = result.enemyHpAfter) else it
            }
        }
        val updatedHero = _state.value.hero?.copy(
            hp = result.heroHpAfter.takeIf { it > 0 } ?: _state.value.hero!!.hp,
            xp = _state.value.hero!!.xp + result.xpGained,
            drachma = _state.value.hero!!.drachma + result.drachmaGained
        )
        _state.value = _state.value.copy(
            isLoading = false,
            lastBattleResult = result,
            enemies = newEnemies,
            hero = updatedHero
        )
        if (newEnemies.isEmpty() && result.enemyDefeated) {
            delay(1500)
            refreshPatrol()
        }
    }

    // ── Locations ─────────────────────────────────────────────────────────────

    fun loadLocations() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val locs = client.fetchLocations()
                _state.value = _state.value.copy(isLoading = false, locations = locs)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = "Could not load locations: ${e.message}"
                )
            }
        }
    }

    fun travel(location: Location) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                client.travel(location.id)
                _state.value = _state.value.copy(
                    isLoading = false,
                    hero = _state.value.hero?.copy(location = location.name),
                    enemies = emptyList(),
                    statusMessage = "Traveled to ${location.name}"
                )
                delay(500)
                refreshPatrol()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = "Travel failed: ${e.message}"
                )
            }
        }
    }

    // ── Bounties ──────────────────────────────────────────────────────────────

    fun loadBounties() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val bounties = client.fetchBounties()
                _state.value = _state.value.copy(isLoading = false, bounties = bounties)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = "Could not load bounties: ${e.message}"
                )
            }
        }
    }

    fun acceptBounty(bounty: Bounty) {
        viewModelScope.launch {
            try {
                client.acceptBounty(bounty.id)
                loadBounties()
            } catch (e: Exception) {
                _state.value = _state.value.copy(statusMessage = "Failed: ${e.message}")
            }
        }
    }

    fun claimBounty(bounty: Bounty) {
        viewModelScope.launch {
            try {
                client.claimBounty(bounty.id)
                _state.value = _state.value.copy(statusMessage = "Reward claimed!")
                loadBounties()
            } catch (e: Exception) {
                _state.value = _state.value.copy(statusMessage = "Failed: ${e.message}")
            }
        }
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    fun loadChat(clan: Boolean = false) {
        viewModelScope.launch {
            try {
                val msgs = client.fetchChat(clan)
                if (clan) _state.value = _state.value.copy(clanChat = msgs)
                else      _state.value = _state.value.copy(globalChat = msgs)
            } catch (e: Exception) {
                _state.value = _state.value.copy(statusMessage = "Chat error: ${e.message}")
            }
        }
    }

    fun sendChat(message: String, clan: Boolean = false) {
        viewModelScope.launch {
            try {
                client.sendChat(message, clan)
                delay(300)
                loadChat(clan)
            } catch (e: Exception) {
                _state.value = _state.value.copy(statusMessage = "Send failed: ${e.message}")
            }
        }
    }

    // ── Gear ──────────────────────────────────────────────────────────────────

    fun loadGear() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val gear = client.fetchGear()
                _state.value = _state.value.copy(isLoading = false, gear = gear)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = "Could not load gear: ${e.message}"
                )
            }
        }
    }

    fun clearStatus() {
        _state.value = _state.value.copy(statusMessage = null)
    }
}
