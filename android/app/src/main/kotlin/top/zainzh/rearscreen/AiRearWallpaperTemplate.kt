package top.zainzh.rearscreen

/** The signed Mod container supplies data only. App bindings are always chosen by the user. */
internal class AiRearWallpaperTemplate private constructor(val allApps: Boolean, val background: ByteArray) {
    companion object {
        const val SIX_APPS = "assets/rear-wallpaper/six-apps.png"
        const val APPS_AND_LIST = "assets/rear-wallpaper/apps-and-list.png"
        fun fromVerifiedAssets(assets: Map<String, ByteArray>): AiRearWallpaperTemplate {
            val six = assets[SIX_APPS]
            val list = assets[APPS_AND_LIST]
            require((six != null) xor (list != null)) { "One rear wallpaper template asset is required" }
            val background = checkNotNull(six ?: list)
            require(background.isNotEmpty())
            return AiRearWallpaperTemplate(list != null, background)
        }
    }
}
