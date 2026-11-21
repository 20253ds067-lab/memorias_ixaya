package mx.edu.utez.memorias.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import mx.edu.utez.memorias.data.local.UserPreferencesRepository
import mx.edu.utez.memorias.data.local.UserSession
import mx.edu.utez.memorias.data.local.entity.MemoryEntity
import mx.edu.utez.memorias.data.repository.AuthRepository
import mx.edu.utez.memorias.data.repository.MemoryRepository

data class HomeUiState(
    val selectedTab: Int = 0,
    val memories: List<MemoryEntity> = emptyList(),
    val isRefreshing: Boolean = false,
    val showSyncBanner: Boolean = false,
    val session: UserSession = UserSession()
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val memoryRepository: MemoryRepository,
    private val authRepository: AuthRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(0)
    private val _isRefreshing = MutableStateFlow(false)
    private val _showSyncBanner = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        _selectedTab,
        _isRefreshing,
        _showSyncBanner,
        userPreferencesRepository.userSessionFlow
    ) { tab, refreshing, banner, session ->
        Tuple4(tab, refreshing, banner, session)
    }.flatMapLatest { (tab, refreshing, banner, session) ->
        val memoriesFlow = if (tab == 0) {
            memoryRepository.observeAllMemories()
        } else {
            memoryRepository.observeMemoriesByUser(session.matricula)
        }
        memoriesFlow.map { list ->
            HomeUiState(
                selectedTab = tab,
                memories = list,
                isRefreshing = refreshing,
                showSyncBanner = banner,
                session = session
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    init {
        refresh()
    }

    fun onTabSelected(index: Int) {
        _selectedTab.value = index
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            memoryRepository.syncPendingOfflineMemories()
            memoryRepository.refreshMemoriesFromRemote()
            _isRefreshing.value = false
        }
    }

    fun onShake() {
        viewModelScope.launch {
            _showSyncBanner.value = true
            memoryRepository.syncPendingOfflineMemories()
            memoryRepository.refreshMemoriesFromRemote()
            delay(2500)
            _showSyncBanner.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
