package top.zainzh.rearscreen

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.platform.mods.InstalledMod
import top.zainzh.rearscreen.platform.mods.ModPackageStore
import top.zainzh.rearscreen.platform.mods.ModSettingsActivity
import top.zainzh.rearscreen.AiRearWallpaperInstallations.Placement
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

class AiRearWallpaperEditorActivity : ComponentActivity() {
    private data class Slot(val id: String, val title: String, val modified: Long, val available: Boolean)
    private data class ReplaceConfirmation(val placement: Placement, val legacyTicket: String?)
    private var service by mutableStateOf<ITaskService?>(null)
    private var builtInDraft by mutableStateOf(AiRearShortcutDraft())
    private var modDraft by mutableStateOf(AiRearShortcutDraft())
    private val entryModId get() = intent.getStringExtra(EXTRA_MOD_ID)
    private var modId by mutableStateOf<String?>(null)
    private var themes by mutableStateOf(emptyList<InstalledMod>())
    private var modTheme by mutableStateOf<AiRearModWallpaperTheme?>(null)
    private var templateBackground by mutableStateOf<Bitmap?>(null)
    private var themeSequence = 0
    private var themeLoading by mutableStateOf(false)
    private var choosingTheme by mutableStateOf(false)
    private val shortcuts get() = if (modId != null) modDraft else builtInDraft
    private val currentThemeName get() = if (modId == null) getString(R.string.ai_rear_builtin_theme)
        else themes.firstOrNull { it.id == modId }?.name ?: modId.orEmpty()
    private val grid get() = shortcuts.grid
    private var background by mutableStateOf<Bitmap?>(null)
    private var slots by mutableStateOf<List<Slot>?>(null)
    private var selected by mutableStateOf<String?>(null)
    private var apps by mutableStateOf(emptyList<AiRearApp>())
    private var loadingApps by mutableStateOf(true)
    private var choosingApps by mutableStateOf(false)
    private var choosingWork by mutableStateOf(false)
    private var appearance by mutableStateOf(false)
    private var previewViewport by mutableStateOf(AiRearWallpaperArt.referenceViewport)
    private var message by mutableStateOf("")
    private var loading by mutableStateOf(false)
    private var backgroundBusy by mutableStateOf(false)
    private var applying by mutableStateOf(false)
    private var checkingWork by mutableStateOf(false)
    private var confirmReplace by mutableStateOf<ReplaceConfirmation?>(null)
    private var activePlacements by mutableStateOf(emptyList<Placement>())
    private val activePlacement get() = activePlacements.firstOrNull { it.modId == modId && (modId == null || it.id == selected) }
    private var preparing by mutableStateOf(false)
    private var allowUnlocked by mutableStateOf(false)
    private var sequence = 0
    private val prefs by lazy { getSharedPreferences(AiRearWallpaperBinding.PREFS, Context.MODE_PRIVATE) }
    private val wallpaperFile by lazy { File(filesDir, "ai-rear-background.jpg") }
    private val installations by lazy { AiRearWallpaperInstallations.forApp(this) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!AiRearWallpaperSupport.shellReady(this@AiRearWallpaperEditorActivity)) return
            service = ITaskService.Stub.asInterface(binder)
            loadSlots()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            sequence++
            service = null
            confirmReplace = null
            slots = null
            loading = false
        }
    }
    private val chooseImage = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        if (uri == null || backgroundBusy) return@registerForActivityResult
        lifecycleScope.launch {
            backgroundBusy = true
            try {
                background = withContext(Dispatchers.IO) {
                    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = maxOf(1f, maxOf(info.size.width, info.size.height) / 1600f)
                        decoder.setTargetSize(maxOf(1, (info.size.width / scale).toInt()),
                            maxOf(1, (info.size.height / scale).toInt()))
                    }
                    saveBackground(decoded)
                    decoded
                }
            } catch (_: Exception) {
                message = getString(R.string.ai_rear_image_error)
            } finally { backgroundBusy = false }
        }
    }

    private fun saveBackground(decoded: Bitmap) {
        val staged = File.createTempFile("ai-background-", ".jpg", filesDir)
        try {
            FileOutputStream(staged).use {
                check(decoded.compress(Bitmap.CompressFormat.JPEG, 92, it))
                it.fd.sync()
            }
            java.nio.file.Files.move(staged.toPath(), wallpaperFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.delete() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AiRearWallpaperSupport.supported()) { finish(); return }
        builtInDraft = AiRearShortcutDraft.fromGrid(
            AiRearGrid(List(6) { prefs.getString("app_$it", null) }, prefs.getBoolean("all_apps", false)),
        )
        modId = savedInstanceState?.takeIf { it.containsKey("theme_mod") }?.getString("theme_mod") ?: entryModId
        selected = savedInstanceState?.getString("selected")
        choosingTheme = savedInstanceState?.getBoolean("choosing_theme") == true
        choosingApps = savedInstanceState?.getBoolean("choosing_apps") == true ||
            (savedInstanceState?.getInt("picking", -1) ?: -1) in 0..5
        choosingWork = savedInstanceState?.getBoolean("choosing_work") == true
        appearance = savedInstanceState?.getBoolean("appearance") == true
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { AiRearAppCatalog.list(this@AiRearWallpaperEditorActivity) }
                    .getOrDefault(emptyList()) to BitmapFactory.decodeFile(wallpaperFile.path)
            }
            apps = loaded.first
            loadingApps = false
            background = loaded.second
        }
        if (!TaskServiceManager.isRoot(this)) TaskServiceManager.acquire(this, connection)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!applying && !preparing) back()
            }
        })
        miuixContent {
            AiRearArtworkScope {
                // Keep the studio's reading position and icons when returning from a picker.
                val studioScroll = rememberLazyListState()
                val workScroll = rememberLazyGridState()
                when {
                    choosingApps && (modId == null || modTheme != null) -> AiRearShortcutEditor(shortcuts, apps, loadingApps, ::back,
                        limit = modTheme?.buttons ?: 6, allowAllApps = modTheme?.art.isNullOrEmpty(), update = ::saveShortcuts)
                    choosingTheme -> ThemeChooser()
                    choosingWork -> Works(workScroll)
                    else -> Editor(studioScroll)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        allowUnlocked = AiRearWallpaperSupport.unlockedLaunchEnabled(this)
        refreshThemes()
    }

    private fun back() {
        if (applying || preparing) return
        when {
            appearance -> appearance = false
            choosingApps -> choosingApps = false
            choosingTheme -> choosingTheme = false
            choosingWork -> choosingWork = false
            else -> finish()
        }
    }

    private suspend fun refreshPlacements() {
        withContext(Dispatchers.IO) { runCatching { installations.active() } }
            .onSuccess { activePlacements = it }
            .onFailure { message = getString(R.string.ai_rear_write_error) }
    }

    private fun refreshThemes() {
        lifecycleScope.launch {
            refreshPlacements()
            withContext(Dispatchers.IO) { runCatching { ModPackageStore.list(this@AiRearWallpaperEditorActivity) } }
                .onSuccess { records -> themes = records.filter { it.wallpaperTheme != null } }
                .onFailure { message = getString(R.string.ai_rear_theme_unreadable) }
            if (modId != null && selected == null) {
                selected = activePlacements.firstOrNull { it.modId == modId }?.id
            }
            loadThemeArtwork()
        }
    }

    private fun selectTheme(id: String?, workId: String? = null) {
        modId = id
        selected = workId ?: activePlacements.firstOrNull { it.modId == id }?.id
        choosingTheme = false
        message = ""
        loadThemeArtwork()
    }

    private fun loadThemeArtwork() {
        val request = ++themeSequence
        val id = modId
        modTheme = null
        templateBackground = null
        themeLoading = id != null
        if (id == null) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val art = ModPackageStore.wallpaperArtwork(this@AiRearWallpaperEditorActivity, id)
                val image = art?.takeIf { it.art.isEmpty() }?.let {
                    checkNotNull(BitmapFactory.decodeByteArray(it.backdrop, 0, it.backdrop.size))
                }
                art to image
            } }
            if (request != themeSequence || modId != id) return@launch
            themeLoading = false
            result.onSuccess { (art, image) ->
                modTheme = art
                templateBackground = image
                if (art != null) readModDraft(id, art)
            }.onFailure { message = getString(R.string.ai_rear_theme_unreadable) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected)
        outState.putString("theme_mod", modId)
        outState.putBoolean("choosing_apps", choosingApps)
        outState.putBoolean("choosing_theme", choosingTheme)
        outState.putBoolean("choosing_work", choosingWork)
        outState.putBoolean("appearance", appearance)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        TaskServiceManager.release(connection)
        super.onDestroy()
    }

    private fun modDraftKey(id: String, work: String? = selected) =
        AiRearWallpaperInstallations.modDraftPrefix(id) + (work?.let { "work:$it" } ?: "new")

    private fun readModDraft(id: String, art: AiRearModWallpaperTheme, fallback: AiRearGrid? = null) {
        val saved = prefs.getString(modDraftKey(id), null)
        val current = selected?.let { installations.binding(Placement(it, id))?.grid }
        val old = if (saved == null) current ?: fallback else runCatching {
            val value = JSONObject(saved)
            val apps = value.getJSONArray("apps")
            AiRearGrid(List(6) { if (apps.isNull(it)) null else apps.getString(it) }, value.getBoolean("allApps"))
        }.getOrNull() ?: current ?: fallback
        modDraft = AiRearShortcutDraft.fromGrid(AiRearGrid(
            List(6) { if (it < art.buttons) old?.apps?.get(it) else null },
            art.art.isEmpty() && (old?.allApps ?: art.defaultAllApps),
        ))
    }

    private fun saveShortcuts(value: AiRearShortcutDraft) {
        val savedGrid = value.grid
        val id = modId
        if (id != null) {
            modDraft = value
            val draft = JSONObject().put("apps", JSONArray(savedGrid.apps)).put("allApps", savedGrid.allApps)
            prefs.edit().putString(modDraftKey(id), draft.toString()).apply()
        } else {
            builtInDraft = value
            prefs.edit().also { editor ->
                savedGrid.apps.forEachIndexed { index, app -> editor.putString("app_$index", app) }
                editor.putBoolean("all_apps", savedGrid.allApps)
            }.apply()
        }
    }

    private fun loadSlots() {
        val remote = service ?: return
        val request = ++sequence
        loading = true
        message = ""
        lifecycleScope.launch {
            val (result, area) = withContext(Dispatchers.IO) {
                val area = runCatching {
                    AiRearWallpaperArchive.Viewport.fromCast(RearCastGeometry.decode(remote.rearCastViewport))
                }.getOrNull()
                val rows = runCatching {
                    val values = JSONArray(remote.listAiRearWallpapers(resources.configuration.locales[0].toLanguageTag()))
                    List(values.length()) { i ->
                        val row = values.getJSONObject(i)
                        Slot(row.getString("id"), row.getString("title"), row.getLong("modified"), row.getBoolean("available"))
                    }
                }
                rows to area
            }
            if (request != sequence) return@launch
            loading = false
            if (area != null) previewViewport = area
            result.onSuccess {
                slots = it
                if (activePlacement == null && it.none { item -> item.id == selected && item.available }) selected = null
            }.onFailure {
                slots = null
                message = getString(R.string.ai_rear_read_error)
            }
        }
    }

    private fun thumbnail(remote: ITaskService, slot: Slot): Bitmap? {
        val media = File.createTempFile("ai-rear-thumb-", ".media", cacheDir)
        try {
            val kind = AiRearWallpaperConnection(remote).write(slot.id, true, media)
            return when (kind) {
                "video" -> MediaMetadataRetriever().use {
                    it.setDataSource(media.path)
                    it.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 224)
                }
                "image" -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(media)) { decoder, info, _ ->
                    val scale = maxOf(1f, maxOf(info.size.width, info.size.height) / 320f)
                    decoder.setTargetSize(maxOf(1, (info.size.width / scale).toInt()),
                        maxOf(1, (info.size.height / scale).toInt()))
                }
                else -> null
            }
        } finally { media.delete() }
    }

    private fun checkLegacyOwner(ticket: String?) {
        val owner = ticket?.substringBefore('\n') ?: return
        if (owner == packageName) return // An earlier install of this same variant may have lost its journal.
        try {
            packageManager.getPackageInfo(owner, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        error("WALLPAPER_LEGACY_OWNER_INSTALLED")
    }

    private fun requestReplacement() {
        if (!AiRearWallpaperSupport.shellReady(this) || checkingWork || applying || backgroundBusy || preparing) return
        val remote = service ?: return
        val placement = selected?.let { Placement(it, modId) } ?: return
        checkingWork = true
        message = ""
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        synchronized(AiRearWallpaperInstallations.writeLock) {
                            check(installations.canInstall(placement)) { "WALLPAPER_USED_BY_OTHER_THEME" }
                            val ticket = AiRearWallpaperConnection(remote).inspectReplacement(placement.id)
                            checkLegacyOwner(ticket)
                            ReplaceConfirmation(placement, ticket)
                        }
                    }
                }
                if (service != remote || selected != placement.id || modId != placement.modId) return@launch
                result.onSuccess { confirmReplace = it }
                    .onFailure { message = wallpaperError(it, R.string.ai_rear_read_error) }
            } finally {
                checkingWork = false
            }
        }
    }

    private fun wallpaperError(failure: Throwable, fallback: Int): String = getString(when (failure.message) {
        "WALLPAPER_USED_BY_OTHER_THEME" -> R.string.ai_rear_theme_occupied
        "WALLPAPER_LEGACY_OWNER_INSTALLED" -> R.string.ai_rear_legacy_owner_installed
        "WALLPAPER_LEGACY_BACKUP_INVALID" -> R.string.ai_rear_legacy_backup_invalid
        "WALLPAPER_LEGACY_UNVERIFIED" -> R.string.ai_rear_legacy_unverified
        "WALLPAPER_ADOPTION_CHANGED" -> R.string.ai_rear_legacy_changed
        "WALLPAPER_ADOPTION_NOT_WRITTEN" -> R.string.ai_rear_legacy_not_written
        else -> fallback
    })

    private fun changeWallpaper(restore: Boolean, confirmation: ReplaceConfirmation? = null) {
        if (!AiRearWallpaperSupport.shellReady(this) || applying || checkingWork || backgroundBusy || preparing) return
        val remote = service ?: return
        val owner = modId
        val placement = if (restore) activePlacement else selected?.let { Placement(it, owner) }
        placement ?: return
        if (!restore && confirmation?.placement != placement) return
        val legacyTicket = confirmation?.legacyTicket
        val options = apps.associateBy { it.component }
        val artwork = modTheme
        val art = artwork?.takeIf { it.art.isNotEmpty() }
        val template = templateBackground
        val title = slots?.firstOrNull { it.id == placement.id }?.let(::workTitle).orEmpty()
        val configuration = grid
        fun targetsValid(canCast: (String) -> Boolean): Boolean =
            if (owner == null) configuration.isReady(canCast) else artwork?.validGrid(configuration, canCast) == true
        if (!restore && !targetsValid { options[it]?.canCast == true }) return
        applying = true
        message = ""
        lifecycleScope.launch {
            val (result, keepStandby) = withContext(Dispatchers.IO) {
                synchronized(AiRearWallpaperInstallations.writeLock) {
                    val result = runCatching {
                        val journal = installations.journal(placement, AiRearWallpaperConnection(remote))
                        if (restore) {
                            check(installations.isActive(placement))
                            if (owner == null) check(prefs.edit().remove(placement.bindingKey).commit())
                            val restored = journal.restore() == AiRearWallpaperJournal.State.ORIGINAL
                            if (restored && owner != null)
                                prefs.edit().remove(placement.bindingKey).remove(placement.titleKey).apply()
                            restored
                        } else {
                            check(installations.canInstall(placement)) { "WALLPAPER_USED_BY_OTHER_THEME" }
                            checkLegacyOwner(legacyTicket)
                            if (owner != null) check(ModPackageStore.usableWallpaperTheme(this@AiRearWallpaperEditorActivity, owner) != null)
                            val binding = java.util.UUID.randomUUID().toString().replace("-", "")
                            check(targetsValid {
                                AiRearAppCatalog.isCastTarget(this@AiRearWallpaperEditorActivity,
                                    ComponentName.unflattenFromString(it), packageName)
                            })
                            val viewport = AiRearWallpaperArchive.Viewport.fromCast(
                                RearCastGeometry.decode(remote.rearCastViewport),
                            )
                            val badges = configuration.apps.filterNotNull().distinct().mapNotNull { id ->
                                options[id]?.let { item ->
                                    val icon = if (art != null) null else runCatching {
                                        val target = requireNotNull(ComponentName.unflattenFromString(item.component))
                                        encodedIcon(packageManager.getActivityIcon(target))
                                    }.getOrNull()
                                    id to AiRearWallpaperArchive.Badge(item.label, icon)
                                }
                            }.toMap()
                            val backdrop = when {
                                template != null -> croppedBackground(viewport, template)
                                owner == null && wallpaperFile.isFile -> croppedBackground(viewport,
                                    checkNotNull(BitmapFactory.decodeFile(wallpaperFile.path)))
                                else -> null
                            }
                            if (owner != null) check(prefs.edit().putString(placement.titleKey, title).commit())
                            val renderer = AiRearWallpaperJournal.Renderer { original, destination ->
                                AiRearWallpaperArchive.write(original, destination, viewport, configuration, badges,
                                    AiRearWallpaperArchive.Badge(getString(R.string.ai_rear_all_apps),
                                        getDrawable(R.drawable.ic_ai_rear_all_apps)?.let(::encodedIcon)),
                                    getString(R.string.ai_rear_empty_slot), backdrop,
                                    AiRearWallpaperArchive.Activation(packageName,
                                        AiRearWallpaperActivationActivity::class.java.name, binding), art)
                            }
                            if (legacyTicket == null) journal.apply(placement.id, renderer)
                            else journal.adopt(placement.id, legacyTicket, renderer)
                            check(prefs.edit().putString(placement.bindingKey,
                                AiRearWallpaperBinding(placement.id, binding, configuration, artwork?.buttons ?: 6).encode()).commit())
                            true
                        }
                    }
                    result to runCatching {
                        installations.hasLiveBinding { ModPackageStore.usableWallpaperTheme(this@AiRearWallpaperEditorActivity, it) != null }
                    }.getOrDefault(true)
                }
            }
            refreshPlacements()
            applying = false
            if (restore && !keepStandby) RearWallpaperStandby.disarm()
            message = result.fold(onSuccess = { ok ->
                when {
                    !ok -> getString(R.string.ai_rear_conflict)
                    restore -> getString(R.string.ai_rear_restored)
                    else -> getString(R.string.ai_rear_written_work, title.ifBlank { placement.id.takeLast(6) })
                }
            }, onFailure = {
                android.util.Log.w("AiRearWallpaper", (if (restore) "restore" else "apply") +
                    " failed: " + it.javaClass.simpleName +
                    (it.message?.takeIf { code -> code.matches(Regex("[A-Z_]{4,60}")) }?.let { code -> " " + code } ?: ""))
                wallpaperError(it, R.string.ai_rear_write_error)
            })
        }
    }

    private fun prepareShortcuts() {
        val remote = service ?: return
        val placement = activePlacement ?: return
        val standby = RearWallpaperStandby.readiness()
        if (preparing || applying || standby.value != RearWallpaperStandby.Readiness.UNPREPARED) return
        if (!RearCastMode.useVirtual(this)) {
            message = getString(R.string.ai_rear_virtual_required)
            return
        }
        preparing = true
        message = ""
        lifecycleScope.launch {
            try {
                val launched = withContext(Dispatchers.IO) {
                    runCatching { prepareHost(remote, placement) }.getOrDefault(false)
                }
                val acknowledged = launched && withTimeoutOrNull(8_000L) {
                    standby.first { it == RearWallpaperStandby.Readiness.PREPARED }
                } != null
                if (!acknowledged && standby.value != RearWallpaperStandby.Readiness.PREPARED)
                    message = getString(R.string.ai_rear_prepare_failed)
            } finally {
                preparing = false
            }
        }
    }

    private fun prepareHost(remote: ITaskService, placement: Placement): Boolean {
        val binding = installations.binding(placement) ?: return false
        if (installations.journal(placement, AiRearWallpaperConnection(remote)).state() != AiRearWallpaperJournal.State.APPLIED)
            return false
        if (placement.modId != null && ModPackageStore.usableWallpaperTheme(this, placement.modId) == null) return false
        val lock = getSystemService(KeyguardManager::class.java)
        fun current(): Boolean = !lock.isDeviceLocked && !isFinishing && !isDestroyed &&
            TaskServiceManager.isConnectedServiceCurrent(remote) && AiRearWallpaperSupport.shellReady(this) &&
            installations.binding(placement) == binding && installations.isActive(placement)
        if (!current()) return false
        val panel = JSONObject(remote.rearDisplayState)
        val rear = panel.optInt("selectedId", -1)
        val identity = panel.optString("selectedUnique")
        if (rear <= 0 || panel.optString("reason") != "secondary_internal_panel" || !identity.startsWith("local:"))
            return false
        fun official(): Boolean = RearDisplayCastOccupancy.query(remote, packageName, rear).disposition ==
            RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER
        if (!official() || RearScreenKeeperService.isPhysicalCastRunning() ||
            JSONObject(remote.rearVirtualCastStatus).optString("state") != "stopped") return false
        if (!current() || !remote.wakeRearDisplayForScene(rear)) return false
        val after = JSONObject(remote.rearDisplayState)
        if (after.optInt("selectedId", -1) != rear || after.optString("selectedUnique") != identity ||
            !official() || !current()) return false
        // Static own component; no wallpaper strings or external extras reach this command.
        return remote.executeShellCommand("am start -W --display $rear --windowingMode 1 " +
            "-n ${packageName}/${VirtualRearHostActivity::class.java.name} --ei ${RearWallpaperStandby.REAR} $rear " +
            "--ez ${RearWallpaperStandby.PREPARE} true")
    }

    private fun encodedIcon(icon: android.graphics.drawable.Drawable): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        check(icon.toBitmap(64, 64).compress(Bitmap.CompressFormat.PNG, 100, bytes))
        return bytes.toByteArray()
    }

    private fun croppedBackground(v: AiRearWallpaperArchive.Viewport, original: Bitmap): ByteArray {
        val ratio = maxOf(v.width.toFloat() / original.width, v.height.toFloat() / original.height)
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            val left = (v.width - original.width * ratio) / 2
            val top = (v.height - original.height * ratio) / 2
            drawBitmap(original, null, android.graphics.RectF(left, top,
                left + original.width * ratio, top + original.height * ratio),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        val out = java.io.ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
        return out.toByteArray()
    }

    @Composable
    private fun Editor(scroll: androidx.compose.foundation.lazy.LazyListState) {
        val standby by RearWallpaperStandby.readiness().collectAsState()
        val appLookup = remember(apps) { apps.associateBy { it.component } }
        val active = activePlacement
        val work = slots?.firstOrNull { it.id == (active?.id ?: selected) }
        val workLabel = work?.let { AiRearStudioWork(workTitle(it), workDetail(it)) }
            ?: active?.let { AiRearStudioWork(getString(R.string.ai_rear_backed_up), it.id.takeLast(6)) }
        val id = modId
        val model = AiRearStudioModel(grid, appLookup, previewViewport,
            if (id == null) background else templateBackground, workLabel,
            service != null, active != null, standby == RearWallpaperStandby.Readiness.PREPARED,
            applying, preparing || standby == RearWallpaperStandby.Readiness.PREPARING,
            backgroundBusy, allowUnlocked, message, isModTheme = id != null, modTheme = modTheme,
            loadingTheme = themeLoading, checkingWork = checkingWork)
        AiRearWallpaperStudio(model, scroll, ::back, { choosingApps = true },
            {
                AiRearWallpaperSupport.setUnlockedLaunchEnabled(this, it)
                allowUnlocked = it
            },
            action = { action ->
                when (action) {
                    AiRearStudioAction.APPEARANCE -> if (id == null) appearance = true
                    AiRearStudioAction.PICK_WORK -> {
                        choosingWork = true
                        if (slots == null && !loading) loadSlots()
                    }
                    AiRearStudioAction.REPLACE -> requestReplacement()
                    AiRearStudioAction.PREPARE -> prepareShortcuts()
                    AiRearStudioAction.DISARM -> RearWallpaperStandby.disarm()
                    AiRearStudioAction.RESTORE -> changeWallpaper(true)
                }
            },
            themeSelector = {
                AppCard {
                    AppRow(getString(R.string.ai_rear_theme), currentThemeName,
                        Modifier.testTag("ai_rear_theme_selector"),
                        enabled = !model.busy,
                        action = if (entryModId == null) ({ choosingTheme = true }) else null)
                }
            },
            thumbnail = { modifier -> WorkThumbnail(work, modifier) })
        if (id == null) AppearanceSheet()
        val replacement = confirmReplace
        val legacy = replacement?.legacyTicket != null
        WindowDialog(show = replacement != null,
            title = getString(if (legacy) R.string.ai_rear_legacy_title else R.string.ai_rear_backup_replace),
            onDismissRequest = { confirmReplace = null }) {
            Text(getString(if (legacy) R.string.ai_rear_legacy_confirm else R.string.ai_rear_grid_confirm),
                color = AppPalette.text())
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(getString(android.R.string.cancel), { confirmReplace = null }, modifier = Modifier.weight(1f),
                    minHeight = 48.dp)
                TextButton(getString(if (legacy) R.string.ai_rear_legacy_continue else android.R.string.ok), {
                    if (replacement != null && replacement == confirmReplace) {
                        confirmReplace = null
                        changeWallpaper(false, replacement)
                    }
                }, modifier = Modifier.weight(1f), minHeight = 48.dp, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }

    @Composable
    private fun ThemeChooser() {
        PageFrame(getString(R.string.ai_rear_theme_works), ::back, "ai_rear_theme_back") { modifier, inset ->
            Column(modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())
                .padding(top = inset + 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (activePlacements.isNotEmpty()) AppCard {
                    AppSection(getString(R.string.ai_rear_configured_works))
                    activePlacements.forEach { placement ->
                        val work = slots?.firstOrNull { it.id == placement.id }
                        val title = work?.let(::workTitle) ?: prefs.getString(placement.titleKey, null)
                            ?.takeIf(String::isNotBlank) ?: placement.id.takeLast(6)
                        val theme = placement.modId?.let { id -> themes.firstOrNull { it.id == id } }
                        val name = theme?.name ?: placement.modId ?: getString(R.string.ai_rear_builtin_theme)
                        val detail = if (theme?.wallpaperTheme == ModWallpaperThemeKind.DRAWN_CONTROLS && !theme.enabled)
                            getString(R.string.ai_rear_theme_paused_detail, name) else name
                        AppRow(title, detail, Modifier.testTag("ai_rear_configured_${placement.id}"),
                            action = { selectTheme(placement.modId, placement.id) }, trailing = if (work == null) null else ({
                                WorkThumbnail(work, Modifier.size(width = 56.dp, height = 38.dp).clip(RoundedCornerShape(8.dp)))
                            }))
                    }
                }
                AppCard {
                    AppSection(getString(R.string.ai_rear_available_themes))
                    AppRow(getString(R.string.ai_rear_builtin_theme), null, Modifier.testTag("ai_rear_theme_builtin"),
                        action = { selectTheme(null) })
                    themes.forEach { theme ->
                        val enabled = theme.enabled || theme.wallpaperTheme == ModWallpaperThemeKind.GRID_TEMPLATE
                        AppRow(theme.name, if (enabled) theme.authorName else getString(R.string.ai_rear_theme_enable_in_modules),
                            Modifier.testTag("ai_rear_theme_${theme.id}"),
                            action = if (enabled) ({ selectTheme(theme.id) }) else ({
                                startActivity(ModSettingsActivity.open(this@AiRearWallpaperEditorActivity, theme.id))
                            }))
                    }
                    AppDivider()
                    AppRow(getString(R.string.ai_rear_install_in_modules), null, action = {
                        startActivity(ModSettingsActivity.open(this@AiRearWallpaperEditorActivity))
                    })
                }
            }
        }
    }

    @Composable
    private fun AppearanceSheet() {
        WindowBottomSheet(show = appearance, title = getString(R.string.ai_rear_appearance),
            onDismissRequest = { appearance = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())) {
                AppRow(getString(R.string.ai_rear_choose_image), null, enabled = !backgroundBusy && !applying,
                    action = {
                        appearance = false
                        chooseImage.launch(arrayOf("image/*"))
                    })
                if (background != null) {
                    AppDivider()
                    AppRow(getString(R.string.ai_rear_reset_image), null, enabled = !backgroundBusy && !applying,
                        action = {
                            if (!wallpaperFile.exists() || wallpaperFile.delete()) background = null
                            else message = getString(R.string.ai_rear_image_error)
                            appearance = false
                        })
                }
            }
        }
    }

    @Composable
    private fun Works(scroll: LazyGridState) {
        PageFrame(getString(R.string.ai_rear_pick_wallpaper), ::back, "ai_rear_work_back") { modifier, inset ->
            LazyVerticalGrid(GridCells.Adaptive(140.dp), modifier.testTag("ai_rear_works"), state = scroll,
                contentPadding = PaddingValues(20.dp, inset + 10.dp, 20.dp, 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(getString(R.string.ai_rear_pick_hint), fontSize = 14.sp, color = AppPalette.secondary())
                        val detail = when {
                            service == null -> getString(R.string.ai_rear_connect)
                            loading -> getString(R.string.ai_rear_loading)
                            slots?.isEmpty() == true -> getString(R.string.ai_rear_no_wallpapers)
                            slots == null -> message
                            else -> ""
                        }
                        if (detail.isNotBlank()) Text(detail, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            fontSize = 14.sp, color = AppPalette.text())
                        if (service != null && !loading) TextButton(getString(R.string.ai_rear_refresh), ::loadSlots,
                            minHeight = 48.dp)
                    }
                }
                gridItems(slots.orEmpty(), key = { it.id }) { slot ->
                    val occupied = activePlacements.any { it.id == slot.id && it.modId != modId }
                    AppCard(Modifier.testTag("ai_rear_work_${slot.id}").aiRearPressable(slot.available && !occupied && !applying) {
                        val previous = grid
                        selected = slot.id
                        val owner = modId
                        val art = modTheme
                        if (owner != null && art != null) readModDraft(owner, art, previous)
                        message = ""
                        choosingWork = false
                    }) {
                        val label = workTitle(slot)
                        WorkThumbnail(slot, Modifier.fillMaxWidth().aspectRatio(1.65f)
                            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)))
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, color = AppPalette.text(), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(workDetail(slot), color = AppPalette.secondary(), fontSize = 12.sp)
                            if (occupied) Text(getString(R.string.ai_rear_theme_occupied), color = AppPalette.secondary(), fontSize = 12.sp)
                            else if (!slot.available || slot.id == selected)
                                Text(getString(if (slot.available) R.string.ai_rear_selected else R.string.ai_rear_unavailable),
                                    color = if (slot.available) AppPalette.accent() else AppPalette.secondary(), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    private fun workTitle(slot: Slot): String = slot.title.ifEmpty { getString(R.string.ai_rear_fallback_title) }

    @Composable
    private fun workDetail(slot: Slot): String {
        val date = remember(slot.modified) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(slot.modified))
        }
        return getString(R.string.ai_rear_identity, date, slot.id.takeLast(6))
    }

    @Composable
    private fun WorkThumbnail(slot: Slot?, modifier: Modifier) {
        var picture by remember(slot?.id, slot?.modified) { mutableStateOf<Bitmap?>(null) }
        val remote = service
        LaunchedEffect(remote, slot?.id, slot?.modified) {
            if (remote != null && slot?.available == true) picture = withContext(thumbnailWorker) {
                runCatching { thumbnail(remote, slot) }.getOrNull()
            }
        }
        if (picture != null) Image(picture!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
        else Box(modifier.background(AppPalette.background()), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_ai_rear_all_apps), null, Modifier.size(20.dp), AppPalette.secondary())
        }
    }

    companion object {
        private const val EXTRA_MOD_ID = "wallpaper_theme_mod_id"
        internal fun supportsThemes() = AiRearWallpaperSupport.supported()
        internal fun open(context: Context, modId: String? = null) =
            Intent(context, AiRearWallpaperEditorActivity::class.java).putExtra(EXTRA_MOD_ID, modId)

        private val thumbnailWorker = Dispatchers.IO.limitedParallelism(2)
    }
}
