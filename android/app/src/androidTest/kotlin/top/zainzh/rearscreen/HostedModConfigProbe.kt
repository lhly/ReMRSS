package top.zainzh.rearscreen

import top.zainzh.rearscreen.platform.mods.ModConfigPage

/** Device probes read the actual route-owned page, never a second renderer or a mock model. */
internal fun MainActivity.hostedModConfig(): ModConfigPage? {
    val controller = nativeControllerField.get(this) ?: return null
    val compose = settingsUiField.get(controller) ?: return null
    return modConfigField.get(wallpaperModsField.get(compose)) as? ModConfigPage
}

internal fun AiRearWallpaperEditorActivity.hostedModConfig(): ModConfigPage? =
    modConfigField.get(standalonePagesField.get(this)) as? ModConfigPage

private val standalonePagesField = AiRearWallpaperEditorActivity::class.java.getDeclaredField("pages").apply { isAccessible = true }
private val nativeControllerField = MainActivity::class.java.getDeclaredField("nativeUi").apply { isAccessible = true }
private val settingsUiField = SettingsController::class.java.getDeclaredField("composeUi").apply { isAccessible = true }
private val wallpaperModsField = MiuixSettingsUi::class.java.getDeclaredField("wallpaperMods").apply { isAccessible = true }
private val modConfigField = WallpaperModNavigation::class.java.getDeclaredField("modConfig").apply { isAccessible = true }
