package com.example.timelock.ui.apps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.timelock.data.local.AppDatabase
import com.example.timelock.data.local.entity.RestrictedAppEntity
import com.example.timelock.domain.model.AppInfo
import com.example.timelock.domain.usecase.GetInstalledAppsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RestrictedAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val restrictedAppDao = db.restrictedAppDao()
    private val getInstalledAppsUseCase = GetInstalledAppsUseCase(application, restrictedAppDao)

    // 預設開啟系統預載 App (因為 Chrome/YouTube 常為預設必備限制對象)
    val showSystemApps = MutableStateFlow(true)
    val searchQuery = MutableStateFlow("")

    val appsList: StateFlow<List<AppInfo>> = showSystemApps
        .flatMapLatest { includeSystem ->
            getInstalledAppsUseCase.execute(includeSystem)
        }
        .combine(searchQuery) { apps, query ->
            if (query.isBlank()) {
                apps
            } else {
                val q = query.trim().lowercase()
                apps.filter {
                    it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun toggleShowSystemApps(show: Boolean) {
        showSystemApps.value = show
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun toggleRestriction(app: AppInfo) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (app.pendingUnlockUntil != null && app.pendingUnlockUntil > now) {
                // 目前處於 10 分鐘冷靜期中，點擊即為「撤銷解除」，立即恢復完全受限
                restrictedAppDao.setPendingUnlock(app.packageName, null)
            } else if (app.isRestricted) {
                // 目前是已限制狀態，啟動 10 分鐘冷靜延遲解除
                val unlockTargetTime = now + 10 * 60 * 1000L // 10 分鐘
                restrictedAppDao.setPendingUnlock(app.packageName, unlockTargetTime)
            } else {
                // 目前未限制，點擊立即加入受限名單
                restrictedAppDao.insertApp(
                    RestrictedAppEntity(
                        packageName = app.packageName,
                        appName = app.appName,
                        isRestricted = true,
                        addedAt = now,
                        pendingUnlockUntil = null
                    )
                )
            }
        }
    }
}
