package com.example.timelock.domain.usecase

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.timelock.data.local.dao.RestrictedAppDao
import com.example.timelock.domain.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class GetInstalledAppsUseCase(
    private val context: Context,
    private val restrictedAppDao: RestrictedAppDao
) {
    /**
     * 取得具有 Launcher Intent 的應用程式清單。
     * @param includeSystemApps 是否包含系統預載的 Launcher App (如 YouTube、Chrome 等)
     */
    fun execute(includeSystemApps: Boolean): Flow<List<AppInfo>> {
        return restrictedAppDao.getAllRestrictedAppsFlow().map { restrictedList ->
            val restrictedPackageSet = restrictedList.map { it.packageName }.toSet()
            getInstalledLauncherApps(includeSystemApps, restrictedPackageSet)
        }
    }

    private suspend fun getInstalledLauncherApps(
        includeSystemApps: Boolean,
        restrictedPackageSet: Set<String>
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                mainIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(mainIntent, 0)
        }

        val apps = mutableListOf<AppInfo>()
        val seenPackages = mutableSetOf<String>()

        for (resolveInfo in resolveInfoList) {
            val packageName = resolveInfo.activityInfo.packageName
            if (packageName == context.packageName || seenPackages.contains(packageName)) {
                continue
            }
            seenPackages.add(packageName)

            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                // 根據開關過濾純系統 App
                if (!includeSystemApps && isSystemApp) {
                    continue
                }

                val appName = packageManager.getApplicationLabel(appInfo).toString()
                val isRestricted = restrictedPackageSet.contains(packageName)

                apps.add(
                    AppInfo(
                        packageName = packageName,
                        appName = appName,
                        isSystemApp = isSystemApp,
                        isRestricted = isRestricted
                    )
                )
            } catch (e: Exception) {
                // 忽略獲取失敗的 App
            }
        }

        // 排序：已在監控名單中的排最前面，其次按 App 名稱字母排序
        apps.sortedWith(
            compareByDescending<AppInfo> { it.isRestricted }
                .thenBy { it.appName.lowercase() }
        )
    }
}
