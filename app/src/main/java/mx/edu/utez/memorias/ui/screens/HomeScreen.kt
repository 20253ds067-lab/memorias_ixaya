package mx.edu.utez.memorias.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import mx.edu.utez.memorias.ui.components.BottomNavBar
import mx.edu.utez.memorias.ui.components.MemoryCard
import mx.edu.utez.memorias.ui.components.rememberShakeDetector
import mx.edu.utez.memorias.ui.viewmodels.HomeViewModel
import mx.edu.utez.memorias.workers.LikeNotificationWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToAddMemory: () -> Unit,
    onNavigateToDetail: (Int) -> Unit,
    onLoggedOut: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val checkNotifications = {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<LikeNotificationWorker>().build()
        )
    }

    LaunchedEffect(Unit) {
        checkNotifications()
    }

    // Detector de agitación con acelerómetro
    rememberShakeDetector {
        viewModel.onShake()
        checkNotifications()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (state.selectedTab == 0) "Explorar Memorias" else "Mi Álbum")
                        Text(
                            text = "Hola, ${state.session.nombres}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.refresh()
                        checkNotifications()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Recargar")
                    }
                    IconButton(onClick = {
                        viewModel.logout()
                        onLoggedOut()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Cerrar Sesión")
                    }
                }
            )
        },
        bottomBar = {
            BottomNavBar(
                selectedTab = state.selectedTab,
                onTabSelected = viewModel::onTabSelected
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToAddMemory) {
                Icon(Icons.Default.Add, contentDescription = "Nuevo Recuerdo")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Banner de sincronización cuando se detecta el sensor acelerómetro
            AnimatedVisibility(
                visible = state.showSyncBanner,
                enter = slideInVertically(),
                exit = slideOutVertically()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "¡Dispositivo agitado! Sincronizando recuerdos...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            if (state.memories.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.selectedTab == 0) "No hay recuerdos aún. ¡Sé el primero!"
                               else "Aún no has agregado recuerdos a tu álbum.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Cuadrícula de 2 columnas usando LazyColumn + .chunked(2)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.memories.chunked(2), key = { pair -> pair.map { it.id }.joinToString("_") }) { rowPairs ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            for (memory in rowPairs) {
                                Box(modifier = Modifier.weight(1f)) {
                                    MemoryCard(
                                        memory = memory,
                                        onClick = { onNavigateToDetail(memory.id) }
                                    )
                                }
                            }
                            if (rowPairs.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
