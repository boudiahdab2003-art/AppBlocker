package com.appblocker.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.appblocker.R

/**
 * The launcher-icon switcher (Profile ▸ App icon). Each option is an `<activity-alias>` in the
 * manifest carrying its own icon; exactly one alias is enabled at a time, so the launcher shows
 * exactly one AppBlocker entry. MainActivity itself is never disabled.
 */
object AppIcons {

    data class IconOption(
        val id: String,
        val label: String,
        val aliasClass: String,
        val previewRes: Int,
    )

    val OPTIONS = listOf(
        IconOption("halo", "Halo glow", "com.appblocker.alias.IconHalo", R.drawable.ic_launcher_art),
        IconOption("violet", "Violet night", "com.appblocker.alias.IconViolet", R.drawable.ic_launcher_art_violet),
        IconOption("black", "Pure black", "com.appblocker.alias.IconBlack", R.drawable.ic_launcher_art_black),
        IconOption("light", "Daylight", "com.appblocker.alias.IconLight", R.drawable.ic_launcher_art_light),
        IconOption("solid", "Bold silhouette", "com.appblocker.alias.IconSolid", R.drawable.ic_launcher_art_solid),
        IconOption("lock", "Shield & lock", "com.appblocker.alias.IconLock", R.drawable.ic_launcher_art_lock),
    )

    /** The currently active icon (from the saved pref; "halo" is the manifest default). */
    fun current(context: Context): IconOption =
        OPTIONS.firstOrNull { it.id == SettingsStore.appIcon(context) } ?: OPTIONS.first()

    /** Enables [option]'s alias and disables the rest — the launcher entry moves with it. */
    fun apply(context: Context, option: IconOption) {
        val pm = context.packageManager
        OPTIONS.forEach { opt ->
            val state =
                if (opt.id == option.id) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, opt.aliasClass),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
        SettingsStore.setAppIcon(context, option.id)
    }
}
