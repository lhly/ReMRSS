package top.zainzh.rearscreen

import android.content.Context
import java.io.File

/** Backups belong to AI works, not to the shared launcher task or the Mod package's directory. */
internal class AiRearWallpaperInstallations(
    private val base: File,
    private val readBinding: (String) -> String?,
) {
    data class Placement(val id: String, val modId: String? = null) {
        init {
            require(AiRearWallpaperFiles.validId(id))
            require(modId == null || modPattern.matches(modId))
        }
        val bindingKey get() = if (modId == null) AiRearWallpaperBinding.ACTIVE else "mod_wallpaper:$modId:$id"
        val titleKey get() = "$bindingKey:title"
    }

    data class Tap(val placement: Placement, val encoded: String, val tile: AiRearGrid.Tile)

    private val modRoot get() = File(base, "mod-wallpapers")
    private fun root(placement: Placement) =
        placement.modId?.let { File(File(modRoot, it), placement.id) } ?: base

    fun builtIn(): Placement? = AiRearWallpaperJournal.activeId(base)?.let(::Placement)

    fun forMod(modId: String): List<Placement> {
        require(modPattern.matches(modId))
        return File(modRoot, modId).listFiles().orEmpty().asSequence()
            .filter { it.isDirectory && AiRearWallpaperFiles.validId(it.name) }
            .mapNotNull { dir ->
                AiRearWallpaperJournal.activeId(dir)?.let { id ->
                    check(id == dir.name) { "WALLPAPER_BACKUP_INCOMPLETE" }
                    Placement(id, modId)
                }
            }.sortedBy { it.id }.toList()
    }

    private fun mods(): Sequence<Placement> = modRoot.listFiles().orEmpty().asSequence()
        .filter { it.isDirectory && modPattern.matches(it.name) }
        .flatMap { forMod(it.name).asSequence() }

    fun active(): List<Placement> = listOfNotNull(builtIn()) + mods().toList()

    fun journal(placement: Placement, store: AiRearWallpaperJournal.Store) =
        AiRearWallpaperJournal(root(placement), store)

    fun isActive(placement: Placement) = AiRearWallpaperJournal.activeId(root(placement)) == placement.id

    fun restoreMod(modId: String, store: AiRearWallpaperJournal.Store, restored: (Placement) -> Unit): Int {
        val works = forMod(modId)
        for (placement in works) {
            check(journal(placement, store).restore() == AiRearWallpaperJournal.State.ORIGINAL) {
                "WALLPAPER_RESTORE_CONFLICT"
            }
            restored(placement)
        }
        return works.size
    }

    fun canInstall(placement: Placement): Boolean =
        (placement.modId != null || builtIn() == null) && active().none { it.id == placement.id }

    fun binding(placement: Placement): AiRearWallpaperBinding? =
        AiRearWallpaperBinding.decode(readBinding(placement.bindingKey))?.takeIf {
            it.wallpaper == placement.id && (placement.modId != null || it.buttonCount == 6)
        }

    fun resolve(authority: String?, path: String?): Tap? {
        if (authority == null) return null
        for (placement in listOfNotNull(builtIn()).asSequence() + mods()) {
            val encoded = readBinding(placement.bindingKey) ?: continue
            val binding = AiRearWallpaperBinding.decode(encoded) ?: continue
            if (placement.modId == null && binding.buttonCount != 6) continue
            val tile = binding.tile(authority, path, placement.id) ?: continue
            return Tap(placement, encoded, tile)
        }
        return null
    }

    fun isCurrent(tap: Tap): Boolean = readBinding(tap.placement.bindingKey) == tap.encoded && isActive(tap.placement)

    fun hasLiveBinding(available: (String) -> Boolean = { true }): Boolean =
        builtIn()?.let { binding(it) != null } == true ||
            mods().any { binding(it) != null && available(checkNotNull(it.modId)) }

    companion object {
        private val modPattern = Regex("[a-z0-9][a-z0-9_.-]{2,63}")
        val writeLock = Any()
        fun modDraftPrefix(id: String) = "wallpaper_draft:$id:"
        fun forApp(context: Context) = AiRearWallpaperInstallations(File(context.noBackupFilesDir, "ai-rear-wallpaper")) {
            context.getSharedPreferences(AiRearWallpaperBinding.PREFS, Context.MODE_PRIVATE).getString(it, null)
        }
    }
}
