package top.zainzh.rearscreen

import android.os.Bundle

/** Exposes the production Home entry on an emulator, without granting a wallpaper backend. */
class FixtureWallpaperSettingsActivity : MainActivity() {
    override fun hasWallpaperEditor() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val restoredRoute = if (savedInstanceState == null && intent.getBooleanExtra("restore_works_route", false)) {
            Bundle().apply {
                putInt("native_route_version", 2)
                putInt("native_page", WALLPAPER_WORKS_ROUTE)
            }
        } else savedInstanceState
        super.onCreate(restoredRoute)
    }
}
