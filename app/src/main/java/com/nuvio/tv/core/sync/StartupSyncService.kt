package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"

@Singleton
class StartupSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val pluginSyncService: PluginSyncService,
    private val addonSyncService: AddonSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val librarySyncService: LibrarySyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val profileSyncService: ProfileSyncService,
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val libraryRepository: LibraryRepositoryImpl,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupPullJob: Job? = null
    private var lastPulledKey: String? = null
    
    @Volatile
    private var forceSyncRequested: Boolean = false
    
    @Volatile
    private var pendingResyncKey: String? = null

    init {
        scope.launch {
            authManager.authState.collect { state ->
                when (state) {
                    is AuthState.Anonymous -> {
                        val force = forceSyncRequested
                        val started = scheduleStartupPull(state.userId, force = force)
                        if (force && started) forceSyncRequested = false
                    }
                    is AuthState.FullAccount -> {
                        val force = forceSyncRequested
                        val started = scheduleStartupPull(state.userId, force = force)
                        if (force && started) forceSyncRequested = false
                    }
                    is AuthState.SignedOut -> {
                        startupPullJob?.cancel()
                        startupPullJob = null
                        lastPulledKey = null
                        forceSyncRequested = false
                        pendingResyncKey = null
                    }
                    is AuthState.Loading -> Unit
                }
            }
        }
    }

    fun requestSyncNow() {
        forceSyncRequested = true
        when (val state = authManager.authState.value) {
            is AuthState.Anonymous -> {
                val started = scheduleStartupPull(state.userId, force = true)
                if (started) forceSyncRequested = false
            }
            is AuthState.FullAccount -> {
                val started = scheduleStartupPull(state.userId, force = true)
                if (started) forceSyncRequested = false
            }
            else -> Unit
        }
    }

    private fun pullKey(userId: String): String {
        val profileId = profileManager.activeProfileId.value
        return "${userId}_p${profileId}"
    }

    private fun scheduleStartupPull(userId: String, force: Boolean = false): Boolean {
        val key = pullKey(userId)
        if (!force && lastPulledKey == key) return false
        
        // Nunca cancelamos una sincronización activa para evitar errores de escritura en DataStore.
        // En su lugar, programamos una sincronización de seguimiento cuando termine la actual.
        if (startupPullJob?.isActive == true) {
            if (force) pendingResyncKey = key
            return false
        }

        startupPullJob = scope.launch {
            val maxAttempts = 3
            repeat(maxAttempts) { index ->
                val attempt = index + 1
                Log.d(TAG, "Intento de sincronización inicial $attempt/$maxAttempts para la clave=$key")
                val result = pullRemoteData()
                if (result.isSuccess) {
                    lastPulledKey = key
                    Log.d(TAG, "Sincronización inicial completada para la clave=$key")
                    return@repeat
                }

                Log.w(TAG, "El intento $attempt de sincronización falló para la clave=$key", result.exceptionOrNull())
                if (attempt < maxAttempts) {
                    delay(3000)
                }
            }

            // Tras completar, verificamos si se solicitó una re-sincronización mientras corríamos
            val resyncKey = pendingResyncKey
            if (resyncKey != null) {
                pendingResyncKey = null
                if (resyncKey != lastPulledKey) {
                    Log.d(TAG, "Ejecutando re-sincronización pendiente para la clave=$resyncKey")
                    scheduleStartupPull(userId, force = true)
                }
            }
        }
        return true
    }

    private suspend fun pullRemoteData(): Result<Unit> {
        try {
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "Obteniendo datos remotos para el perfil $profileId")

            // Primero obtenemos la lista de perfiles para mantener la selección actualizada
            profileSyncService.pullFromRemote().getOrElse { throw it }
            Log.d(TAG, "Perfiles obtenidos desde la nube")

            // Sincronización de repositorios de plugins
            pluginManager.isSyncingFromRemote = true
            val remotePluginUrls = pluginSyncService.getRemoteRepoUrls().getOrElse { throw it }
            pluginManager.reconcileWithRemoteRepoUrls(
                remoteUrls = remotePluginUrls,
                removeMissingLocal = false
            )
            pluginManager.isSyncingFromRemote = false
            Log.d(TAG, "Se obtuvieron ${remotePluginUrls.size} repositorios de plugins para el perfil $profileId")

            // Sincronización de addons instalados
            addonRepository.isSyncingFromRemote = true
            val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonUrls(
                remoteUrls = remoteAddonUrls,
                removeMissingLocal = false
            )
            addonRepository.isSyncingFromRemote = false
            Log.d(TAG, "Se obtuvieron ${remoteAddonUrls.size} addons para el perfil $profileId")

            val isPrimaryProfile = profileManager.activeProfileId.value == 1
            val isTraktConnected = isPrimaryProfile && traktAuthDataStore.isAuthenticated.first()
            
            Log.d(TAG, "Sincronización de progreso: TraktConectado=$isTraktConnected PerfilPrincipal=$isPrimaryProfile")
            
            if (!isTraktConnected) {
                // Sincronizamos biblioteca y elementos vistos primero (son ligeros y críticos).
                // El progreso de reproducción se deja al final porque la tabla es grande y puede fallar;
                // un error allí no debe bloquear el resto de la sincronización.

                libraryRepository.isSyncingFromRemote = true
                try {
                    val remoteLibraryItems = librarySyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Se obtuvieron ${remoteLibraryItems.size} elementos de la biblioteca")
                    libraryPreferences.mergeRemoteItems(remoteLibraryItems)
                    libraryRepository.hasCompletedInitialPull = true
                    Log.d(TAG, "Biblioteca local reconciliada con ${remoteLibraryItems.size} elementos remotos")
                } catch (e: Exception) {
                    Log.e(TAG, "Error al obtener la biblioteca, continuando con el resto", e)
                } finally {
                    libraryRepository.isSyncingFromRemote = false
                }

                try {
                    val remoteWatchedItems = watchedItemsSyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Se obtuvieron ${remoteWatchedItems.size} elementos vistos")
                    watchedItemsPreferences.replaceWithRemoteItems(remoteWatchedItems)
                    watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
                    Log.d(TAG, "Elementos vistos reconciliados con ${remoteWatchedItems.size} remotos")
                } catch (e: Exception) {
                    Log.e(TAG, "Error al obtener elementos vistos, continuando con el resto", e)
                }

                watchProgressRepository.isSyncingFromRemote = true
                try {
                    val remoteEntries = watchProgressSyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Se obtuvieron ${remoteEntries.size} entradas de progreso de reproducción")
                    watchProgressPreferences.mergeRemoteEntries(remoteEntries.toMap())
                    watchProgressRepository.hasCompletedInitialPull = true
                    Log.d(TAG, "Progreso local mezclado con ${remoteEntries.size} entradas remotas")
                } catch (e: Exception) {
                    Log.e(TAG, "Error al obtener el progreso de reproducción, continuando", e)
                } finally {
                    watchProgressRepository.isSyncingFromRemote = false
                }
            } else {
                Log.d(TAG, "Omitiendo sincronización de biblioteca y progreso (Trakt está conectado)")
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            pluginManager.isSyncingFromRemote = false
            addonRepository.isSyncingFromRemote = false
            watchProgressRepository.isSyncingFromRemote = false
            libraryRepository.isSyncingFromRemote = false
            Log.e(TAG, "La sincronización inicial falló", e)
            return Result.failure(e)
        }
    }
}