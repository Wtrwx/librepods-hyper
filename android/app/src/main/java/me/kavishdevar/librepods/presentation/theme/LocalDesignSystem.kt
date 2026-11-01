package me.kavishdevar.librepods.presentation.theme

import android.content.SharedPreferences
import androidx.compose.runtime.compositionLocalOf

enum class DesignSystem(val preferenceValue: String) {
    Miuix("miuix"),
    Apple("apple"),
    Material("material")
}

const val DESIGN_SYSTEM_PREF = "design_system"

fun readDesignSystem(preferences: SharedPreferences): DesignSystem {
    val saved = preferences.getString(DESIGN_SYSTEM_PREF, null)
    if (saved != null) return DesignSystem.entries.find { it.preferenceValue == saved } ?: DesignSystem.Miuix
    // Keep an explicitly saved appearance choice when upgrading from LibrePods.
    return if (preferences.contains("m3e_enabled")) {
        if (preferences.getBoolean("m3e_enabled", true)) DesignSystem.Material else DesignSystem.Apple
    } else DesignSystem.Miuix
}

val LocalDesignSystem = compositionLocalOf {
    DesignSystem.Miuix
}
