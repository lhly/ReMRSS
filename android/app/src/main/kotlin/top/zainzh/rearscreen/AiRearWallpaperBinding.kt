package top.zainzh.rearscreen

import org.json.JSONArray
import org.json.JSONObject

/** Only the choices baked into the applied wallpaper can be used by its activation URI. */
internal data class AiRearWallpaperBinding(val wallpaper: String, val token: String, val grid: AiRearGrid,
                                         val buttonCount: Int = 6) {
    init {
        require(AiRearWallpaperFiles.validId(wallpaper) && tokenPattern.matches(token))
        require(when (buttonCount) {
            6 -> grid.isReady { true }
            in 1..5 -> AiRearModWallpaperTheme.validControls(grid, buttonCount) { true }
            else -> false
        })
        grid.apps.filterNotNull().forEach(VirtualCastRequest::component)
    }

    fun encode(): String = JSONObject().put("wallpaper", wallpaper).put("token", token)
        .put("allApps", grid.allApps).put("apps", JSONArray(grid.apps))
        .also { if (buttonCount != 6) it.put("buttonCount", buttonCount) }.toString()

    fun tile(authority: String?, path: String?, installedWallpaper: String?): AiRearGrid.Tile? {
        if (authority != token || installedWallpaper != wallpaper || path?.length != 2 || path[0] != '/')
            return null
        val slot = path[1] - '0'
        return slot.takeIf { it in 0 until buttonCount }?.let(grid::tile)
    }

    companion object {
        const val PREFS = "ai_rear_wallpaper"
        const val ACTIVE = "active_grid"
        private val tokenPattern = Regex("[a-f0-9]{32}")

        fun decode(value: String?): AiRearWallpaperBinding? = runCatching {
            val json = JSONObject(value ?: return null)
            val apps = json.getJSONArray("apps")
            require(apps.length() == 6)
            val slots = List(6) { if (apps.isNull(it)) null else apps.getString(it) }
            AiRearWallpaperBinding(json.getString("wallpaper"), json.getString("token"),
                AiRearGrid(slots, json.get("allApps") as Boolean),
                if (json.has("buttonCount")) json.get("buttonCount") as Int else 6)
        }.getOrNull()
    }
}
