package mx.edu.utez.memorias.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mx.edu.utez.memorias.data.local.UserPreferencesRepository
import mx.edu.utez.memorias.data.repository.MemoryRepository
import java.io.File

data class AddEditMemoryUiState(
    val titulo: String = "",
    val lugar: String = "",
    val descripcion: String = "",
    val latitud: Double = 18.8504, // Default Morelos/Cuernavaca
    val longitud: Double = -99.2008,
    val imageFile: File? = null,
    val existingImageFilename: String? = null,
    val isEditMode: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class AddEditMemoryViewModel(
    private val memoryRepository: MemoryRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditMemoryUiState())
    val uiState: StateFlow<AddEditMemoryUiState> = _uiState.asStateFlow()

    private var editingMemoryId: Int? = null

    fun loadMemory(memoryId: Int) {
        editingMemoryId = memoryId
        viewModelScope.launch {
            memoryRepository.observeMemoryById(memoryId).first()?.let { memory ->
                _uiState.update {
                    it.copy(
                        titulo = memory.titulo,
                        lugar = memory.lugar,
                        descripcion = memory.descripcion,
                        latitud = memory.latitud,
                        longitud = memory.longitud,
                        existingImageFilename = memory.imageFilename,
                        imageFile = memory.localImagePath?.let { path -> File(path).takeIf { f -> f.exists() } },
                        isEditMode = true
                    )
                }
            }
        }
    }

    fun onTituloChange(v: String) = _uiState.update { it.copy(titulo = v, errorMessage = null) }
    fun onLugarChange(v: String) = _uiState.update { it.copy(lugar = v, errorMessage = null) }
    fun onDescripcionChange(v: String) = _uiState.update { it.copy(descripcion = v, errorMessage = null) }
    fun onLocationChange(lat: Double, lon: Double) = _uiState.update { it.copy(latitud = lat, longitud = lon) }
    fun onImageSelected(file: File) = _uiState.update { it.copy(imageFile = file) }

    fun save(onSuccess: () -> Unit) {
        val s = _uiState.value
        if (s.titulo.isBlank() || s.lugar.isBlank() || s.descripcion.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Completa los campos obligatorios") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val session = userPreferencesRepository.userSessionFlow.first()
            val id = editingMemoryId
            if (id != null && id > 0) {
                memoryRepository.updateMemory(
                    memoryId = id,
                    userMatricula = session.matricula,
                    titulo = s.titulo,
                    lugar = s.lugar,
                    latitud = s.latitud,
                    longitud = s.longitud,
                    descripcion = s.descripcion
                )
            } else {
                memoryRepository.createMemory(
                    userMatricula = session.matricula,
                    titulo = s.titulo,
                    lugar = s.lugar,
                    latitud = s.latitud,
                    longitud = s.longitud,
                    descripcion = s.descripcion,
                    imageFile = s.imageFile
                )
            }
            _uiState.update { it.copy(isLoading = false) }
            onSuccess()
        }
    }
}
