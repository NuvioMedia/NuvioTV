package com.nuvio.tv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nuvio.tv.domain.model.AppIconOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AppIconSettingsState(
    val selected: AppIconOption = AppIconOption.ORIGINAL,
    val pending: AppIconOption? = null,
    val changeFailed: Boolean = false
)

@Singleton
class AppIconManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val launcherClasses = mapOf(
        AppIconOption.ORIGINAL to "com.nuvio.tv.launcher.AppIconDefault",
        AppIconOption.ARCTIC_BLUE to "com.nuvio.tv.launcher.AppIconArcticBlue",
        AppIconOption.EMERALD to "com.nuvio.tv.launcher.AppIconEmerald",
        AppIconOption.ROSE_GOLD to "com.nuvio.tv.launcher.AppIconRoseGold",
        AppIconOption.COPPER to "com.nuvio.tv.launcher.AppIconCopper",
        AppIconOption.GRAPHITE to "com.nuvio.tv.launcher.AppIconGraphite"
    )

    private val _state = MutableStateFlow(AppIconSettingsState())
    val state: StateFlow<AppIconSettingsState> = _state.asStateFlow()

    init {
        restoreDefaultIfNeeded()
        _state.value = AppIconSettingsState(selected = currentOption())
    }

    fun select(option: AppIconOption): Boolean {
        val current = _state.value
        if (current.pending != null || current.selected == option) return false

        _state.value = current.copy(pending = option, changeFailed = false)
        val changed = activate(option)
        _state.value = if (changed) {
            AppIconSettingsState(selected = option)
        } else {
            current.copy(changeFailed = true)
        }
        return changed
    }

    fun clearFailure() {
        val current = _state.value
        if (!current.changeFailed) return
        _state.value = current.copy(changeFailed = false)
    }

    private fun currentOption(): AppIconOption {
        val packageManager = context.packageManager
        val explicitlyEnabled = launcherClasses.entries.firstOrNull { (_, className) ->
            packageManager.getComponentEnabledSetting(component(className)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (explicitlyEnabled != null) return explicitlyEnabled.key
        return AppIconOption.ORIGINAL
    }

    private fun restoreDefaultIfNeeded() {
        val packageManager = context.packageManager
        val hasEnabledComponent = launcherClasses.values.any { className ->
            packageManager.getComponentEnabledSetting(component(className)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (hasEnabledComponent) return

        val defaultClass = launcherClasses.getValue(AppIconOption.ORIGINAL)
        if (
            packageManager.getComponentEnabledSetting(component(defaultClass)) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) {
            return
        }
        packageManager.setComponentEnabledSetting(
            component(defaultClass),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun activate(option: AppIconOption): Boolean {
        val selectedClass = launcherClasses.getValue(option)
        val packageManager = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(
                    launcherClasses.values.map { className ->
                        PackageManager.ComponentEnabledSetting(
                            component(className),
                            if (className == selectedClass) {
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            } else {
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            },
                            PackageManager.DONT_KILL_APP
                        )
                    }
                )
            } else {
                packageManager.setComponentEnabledSetting(
                    component(selectedClass),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                launcherClasses.values
                    .filterNot { it == selectedClass }
                    .forEach { className ->
                        packageManager.setComponentEnabledSetting(
                            component(className),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
            }
        }.isSuccess
    }

    private fun component(className: String): ComponentName =
        ComponentName(context.packageName, className)
}
