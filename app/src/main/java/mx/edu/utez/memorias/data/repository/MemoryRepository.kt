package mx.edu.utez.memorias.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import mx.edu.utez.memorias.data.local.UserPreferencesRepository
import mx.edu.utez.memorias.data.local.dao.EditHistoryDao
import mx.edu.utez.memorias.data.local.dao.LikeDao
import mx.edu.utez.memorias.data.local.dao.MemoryDao
import mx.edu.utez.memorias.data.local.dao.UserDao
import mx.edu.utez.memorias.data.local.entity.EditHistoryEntity
import mx.edu.utez.memorias.data.local.entity.LikeEntity
import mx.edu.utez.memorias.data.local.entity.MemoryEntity
import mx.edu.utez.memorias.data.remote.ApiService
import mx.edu.utez.memorias.data.remote.dto.LikeToggleRequest
import mx.edu.utez.memorias.data.remote.dto.UpdateMemoryRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant

class MemoryRepository(
    private val apiService: ApiService,
    private val memoryDao: MemoryDao,
    private val userDao: UserDao,
    private val likeDao: LikeDao,
    private val editHistoryDao: EditHistoryDao,
    private val preferencesRepository: UserPreferencesRepository
) {
    fun observeAllMemories(): Flow<List<MemoryEntity>> = memoryDao.observeAllMemories()

    fun observeMemoriesByUser(matricula: String): Flow<List<MemoryEntity>> =
        memoryDao.observeMemoriesByUser(matricula)

    fun observeMemoryById(id: Int): Flow<MemoryEntity?> = memoryDao.observeMemoryById(id)

    fun observeLikeCount(memoryId: Int): Flow<Int> = likeDao.observeLikeCount(memoryId)

    fun observeIsLikedByUser(memoryId: Int, matricula: String): Flow<Boolean> =
        likeDao.observeIsLikedByUser(memoryId, matricula)

    fun observeEditHistory(memoryId: Int): Flow<List<EditHistoryEntity>> =
        editHistoryDao.observeHistoryForMemory(memoryId)

    suspend fun refreshMemoriesFromRemote() {
        try {
            val response = apiService.getMemories()
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.forEach { dto ->
                    val existing = memoryDao.getMemoryByRemoteId(dto.id)
                    val entity = MemoryEntity(
                        id = existing?.id ?: 0,
                        userMatricula = dto.userMatricula,
                        titulo = dto.titulo,
                        lugar = dto.lugar,
                        latitud = dto.latitud,
                        longitud = dto.longitud,
                        descripcion = dto.descripcion,
                        imageFilename = dto.imageFilename,
                        timestamp = dto.timestamp,
                        isDeleted = dto.isDeleted,
                        isSynced = true,
                        localImagePath = existing?.localImagePath,
                        remoteId = dto.id
                    )
                    memoryDao.insertMemory(entity)
                }
            }
        } catch (_: Exception) {
            // Modo offline: mantiene la caché local de Room
        }
    }

    suspend fun refreshEditHistory(memoryId: Int) {
        try {
            val memory = memoryDao.getMemoryById(memoryId) ?: return
            val remoteId = memory.remoteId ?: memoryId
            val response = apiService.getEditHistory(remoteId)
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.forEach { dto ->
                    editHistoryDao.insertHistory(
                        EditHistoryEntity(
                            id = dto.id,
                            memoryId = memoryId,
                            editedAt = dto.editedAt,
                            changeSummary = dto.changeSummary
                        )
                    )
                }
            }
        } catch (_: Exception) { }
    }

    suspend fun toggleLike(memoryId: Int, matricula: String) {
        try {
            val isLiked = likeDao.getLike(memoryId, matricula) != null
            if (isLiked) {
                likeDao.deleteLike(memoryId, matricula)
            } else {
                likeDao.insertLike(
                    LikeEntity(
                        memoryId = memoryId,
                        likerMatricula = matricula,
                        createdAt = Instant.now().toString(),
                        notified = 0
                    )
                )
            }

            val memory = memoryDao.getMemoryById(memoryId)
            val remoteId = memory?.remoteId ?: memoryId
            apiService.toggleLike(remoteId, LikeToggleRequest(matricula))
        } catch (_: Exception) { }
    }

    suspend fun logicalDeleteMemory(memoryId: Int, matricula: String): Result<Unit> {
        return try {
            memoryDao.logicalDeleteMemory(memoryId, isSynced = false)
            val memory = memoryDao.getMemoryById(memoryId)
            val remoteId = memory?.remoteId ?: memoryId
            val response = apiService.deleteMemory(remoteId, matricula)
            if (response.isSuccessful) {
                memoryDao.logicalDeleteMemory(memoryId, isSynced = true)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            // En modo offline se mantiene eliminado lógicamente y SyncMemoriesWorker sincronizará después
            Result.success(Unit)
        }
    }

    suspend fun createMemory(
        userMatricula: String,
        titulo: String,
        lugar: String,
        latitud: Double,
        longitud: Double,
        descripcion: String,
        imageFile: File?
    ): Result<MemoryEntity> {
        val now = Instant.now().toString()
        var entity = MemoryEntity(
            userMatricula = userMatricula,
            titulo = titulo,
            lugar = lugar,
            latitud = latitud,
            longitud = longitud,
            descripcion = descripcion,
            imageFilename = null,
            timestamp = now,
            isDeleted = 0,
            isSynced = false,
            localImagePath = imageFile?.absolutePath,
            remoteId = null
        )
        val localId = memoryDao.insertMemory(entity).toInt()
        entity = entity.copy(id = localId)

        try {
            val partMatricula = userMatricula.toRequestBody("text/plain".toMediaTypeOrNull())
            val partTitulo = titulo.toRequestBody("text/plain".toMediaTypeOrNull())
            val partLugar = lugar.toRequestBody("text/plain".toMediaTypeOrNull())
            val partLat = latitud.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val partLon = longitud.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val partDesc = descripcion.toRequestBody("text/plain".toMediaTypeOrNull())
            val partTimestamp = now.toRequestBody("text/plain".toMediaTypeOrNull())

            val partImage = imageFile?.let { file ->
                val reqBody = file.asRequestBody("image/*".toMediaTypeOrNull())
                MultipartBody.Part.createFormData("image", file.name, reqBody)
            }

            val response = apiService.createMemory(
                userMatricula = partMatricula,
                titulo = partTitulo,
                lugar = partLugar,
                latitud = partLat,
                longitud = partLon,
                descripcion = partDesc,
                timestamp = partTimestamp,
                image = partImage
            )

            if (response.isSuccessful && response.body() != null) {
                val dto = response.body()!!
                entity = entity.copy(
                    remoteId = dto.id,
                    imageFilename = dto.imageFilename,
                    isSynced = true
                )
                memoryDao.updateMemory(entity)
            }
        } catch (_: Exception) {
            // Guardado exitoso offline
        }

        return Result.success(entity)
    }

    suspend fun updateMemory(
        memoryId: Int,
        userMatricula: String? = null,
        titulo: String,
        lugar: String,
        latitud: Double,
        longitud: Double,
        descripcion: String
    ): Result<Unit> {
        val memory = memoryDao.getMemoryById(memoryId) ?: return Result.failure(Exception("Recuerdo no encontrado"))
        val updated = memory.copy(
            titulo = titulo,
            lugar = lugar,
            latitud = latitud,
            longitud = longitud,
            descripcion = descripcion,
            isSynced = false
        )
        memoryDao.updateMemory(updated)

        // Registrar cambio inmediatamente en historial local
        val changes = mutableListOf<String>()
        if (titulo != memory.titulo) changes.add("Título: '$titulo'")
        if (lugar != memory.lugar) changes.add("Lugar: '$lugar'")
        if (descripcion != memory.descripcion) changes.add("Descripción modificada")
        val summary = if (changes.isNotEmpty()) changes.joinToString(" | ") else "Detalles actualizados"

        val now = Instant.now().toString()
        editHistoryDao.insertHistory(
            EditHistoryEntity(
                memoryId = memoryId,
                editedAt = now,
                changeSummary = summary
            )
        )

        try {
            val matricula = userMatricula ?: preferencesRepository.userSessionFlow.first().matricula
            val remoteId = memory.remoteId ?: memoryId
            val response = apiService.updateMemory(
                memoryId = remoteId,
                userMatricula = matricula,
                request = UpdateMemoryRequest(
                    userMatricula = matricula,
                    titulo = titulo,
                    lugar = lugar,
                    latitud = latitud,
                    longitud = longitud,
                    descripcion = descripcion,
                    changeSummary = summary
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val dto = response.body()!!
                memoryDao.updateMemory(
                    updated.copy(
                        remoteId = dto.id,
                        imageFilename = dto.imageFilename ?: updated.imageFilename,
                        isSynced = true
                    )
                )
                refreshEditHistory(memoryId)
            }
        } catch (_: Exception) { }

        return Result.success(Unit)
    }

    suspend fun syncPendingOfflineMemories() {
        val unsynced = memoryDao.getUnsyncedMemories()
        for (memory in unsynced) {
            try {
                if (memory.isDeleted == 1) {
                    val remoteId = memory.remoteId ?: memory.id
                    val resp = apiService.deleteMemory(remoteId, memory.userMatricula)
                    if (resp.isSuccessful) {
                        memoryDao.logicalDeleteMemory(memory.id, isSynced = true)
                    }
                } else if (memory.remoteId == null) {
                    val partMatricula = memory.userMatricula.toRequestBody("text/plain".toMediaTypeOrNull())
                    val partTitulo = memory.titulo.toRequestBody("text/plain".toMediaTypeOrNull())
                    val partLugar = memory.lugar.toRequestBody("text/plain".toMediaTypeOrNull())
                    val partLat = memory.latitud.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                    val partLon = memory.longitud.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                    val partDesc = memory.descripcion.toRequestBody("text/plain".toMediaTypeOrNull())
                    val partTimestamp = memory.timestamp.toRequestBody("text/plain".toMediaTypeOrNull())

                    val imageFile = memory.localImagePath?.let { File(it) }
                    val partImage = if (imageFile != null && imageFile.exists()) {
                        val reqBody = imageFile.asRequestBody("image/*".toMediaTypeOrNull())
                        MultipartBody.Part.createFormData("image", imageFile.name, reqBody)
                    } else null

                    val response = apiService.createMemory(
                        userMatricula = partMatricula,
                        titulo = partTitulo,
                        lugar = partLugar,
                        latitud = partLat,
                        longitud = partLon,
                        descripcion = partDesc,
                        timestamp = partTimestamp,
                        image = partImage
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val dto = response.body()!!
                        memoryDao.updateMemory(
                            memory.copy(
                                remoteId = dto.id,
                                imageFilename = dto.imageFilename,
                                isSynced = true
                            )
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }
}
