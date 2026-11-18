package top.zainzh.rearscreen

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.platform.mods.ModPackageReader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

class AiRearWallpaperEditorActivity : ComponentActivity() {
    private data class Slot(val id: String, val title: String, val modified: Long, val available: Boolean)
    private var service by mutableStateOf<ITaskService?>(null)
    private var grid by mutableStateOf(AiRearGrid())
    private var background by mutableStateOf<Bitmap?>(null)
    private var slots by mutableStateOf<List<Slot>?>(null)
    private var selected by mutableStateOf<String?>(null)
    private var apps by mutableStateOf(emptyList<AiRearApp>())
    private var picking by mutableStateOf<Int?>(null)
    private var message by mutableStateOf("")
    private var loading by mutableStateOf(false)
    private var backgroundBusy by mutableStateOf(false)
    private var applying by mutableStateOf(false)
    private var confirmReplace by mutableStateOf(false)
    private var activeSlot by mutableStateOf<String?>(null)
    private var ready by mutableStateOf(false)
    private var preparing by mutableStateOf(false)
    private val journalRoot by lazy { File(noBackupFilesDir, "ai-rear-wallpaper") }
    private var sequence = 0
    private val prefs by lazy { getSharedPreferences(AiRearWallpaperBinding.PREFS, Context.MODE_PRIVATE) }
    private val wallpaperFile by lazy { File(filesDir, "ai-rear-background.jpg") }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!AiRearWallpaperSupport.shellReady(this@AiRearWallpaperEditorActivity)) return
            service = ITaskService.Stub.asInterface(binder)
            loadSlots()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            sequence++
            service = null
            slots = null
            ready = false
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

    private val chooseTemplate = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        if (uri == null || backgroundBusy || applying || preparing) return@registerForActivityResult
        lifecycleScope.launch {
            backgroundBusy = true
            try {
                val (draft, image) = withContext(Dispatchers.IO) {
                    val verified = checkNotNull(contentResolver.openInputStream(uri)).use(ModPackageReader::read)
                    val template = AiRearWallpaperTemplate.fromVerifiedAssets(verified.payloads)
                    val pixels = template.background
                    // ModPackageReader has already verified the asset signature, type and dimensions.
                    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(pixels, 0, pixels.size))
                    saveBackground(bitmap)
                    template to bitmap
                }
                save(grid.copy(allApps = draft.allApps))
                background = image
                message = getString(R.string.ai_rear_template_loaded)
            } catch (_: Exception) {
                message = getString(R.string.ai_rear_template_error)
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
        grid = AiRearGrid(List(6) { prefs.getString("app_$it", null) }, prefs.getBoolean("all_apps", false))
        selected = savedInstanceState?.getString("selected")
        picking = savedInstanceState?.getInt("picking", -1)?.takeIf { it in 0..5 }
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { AiRearAppCatalog.list(this@AiRearWallpaperEditorActivity) }
                    .getOrDefault(emptyList()) to BitmapFactory.decodeFile(wallpaperFile.path)
            }
            apps = loaded.first
            background = loaded.second
        }
        if (!TaskServiceManager.isRoot(this)) TaskServiceManager.acquire(this, connection)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!applying && !preparing) back()
            }
        })
        miuixContent { picking?.let { Apps(it) } ?: Editor() }
    }

    override fun onResume() {
        super.onResume()
        ready = RearWallpaperStandby.isParked()
        readJournalId()
    }

    private fun back() {
        if (applying || preparing) return
        if (picking != null) picking = null else finish()
    }

    private fun journal(remote: ITaskService) =
        AiRearWallpaperJournal(journalRoot, AiRearWallpaperConnection(remote))

    private fun readJournalId() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AiRearWallpaperJournal.activeId(journalRoot)
                }
            }
            result.onSuccess { activeSlot = it }.onFailure { message = getString(R.string.ai_rear_write_error) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected)
        outState.putInt("picking", picking ?: -1)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        TaskServiceManager.release(connection)
        super.onDestroy()
    }

    private fun save(value: AiRearGrid) {
        grid = value
        prefs.edit().also { editor ->
            value.apps.forEachIndexed { index, app -> editor.putString("app_$index", app) }
            editor.putBoolean("all_apps", value.allApps)
        }.apply()
    }

    private fun loadSlots() {
        val remote = service ?: return
        val request = ++sequence
        loading = true
        message = ""
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rows = JSONArray(remote.listAiRearWallpapers(resources.configuration.locales[0].toLanguageTag()))
                    List(rows.length()) { i ->
                        val row = rows.getJSONObject(i)
                        Slot(row.getString("id"), row.getString("title"), row.getLong("modified"), row.getBoolean("available"))
                    }
                }
            }
            if (request != sequence) return@launch
            loading = false
            result.onSuccess {
                slots = it
                if (it.none { item -> item.id == selected && item.available }) selected = null
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

    private fun changeWallpaper(restore: Boolean) {
        if (!AiRearWallpaperSupport.shellReady(this) || applying || backgroundBusy || preparing) return
        val remote = service ?: return
        val id = (if (restore) activeSlot else selected) ?: return
        val options = apps.associateBy { it.component }
        if (!restore && !grid.isReady { options[it]?.canCast == true }) return
        val configuration = grid
        applying = true
        message = ""
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val journal = journal(remote)
                    if (restore) {
                        // Revoke activation immediately, including when Shizuku drops or a newer file conflicts.
                        check(prefs.edit().remove(AiRearWallpaperBinding.ACTIVE)
                            .commit())
                        journal.restore() != AiRearWallpaperJournal.State.CONFLICT
                    } else {
                        val binding = java.util.UUID.randomUUID().toString().replace("-", "")
                        check(configuration.isReady {
                            AiRearAppCatalog.isCastTarget(this@AiRearWallpaperEditorActivity,
                                ComponentName.unflattenFromString(it), packageName)
                        })
                        val viewport = AiRearWallpaperArchive.Viewport.fromCast(
                            RearCastGeometry.decode(remote.rearCastViewport),
                        )
                        val badges = configuration.apps.filterNotNull().distinct().mapNotNull { id ->
                            options[id]?.let { item ->
                                val icon = runCatching {
                                    val target = requireNotNull(ComponentName.unflattenFromString(item.component))
                                    encodedIcon(packageManager.getActivityIcon(target))
                                }.getOrNull()
                                id to AiRearWallpaperArchive.Badge(item.label, icon)
                            }
                        }.toMap()
                        val backdrop = if (wallpaperFile.isFile) croppedBackground(viewport) else null
                        journal.apply(id) { original, destination ->
                            AiRearWallpaperArchive.write(original, destination, viewport, configuration, badges,
                                AiRearWallpaperArchive.Badge(getString(R.string.ai_rear_all_apps),
                                    getDrawable(R.drawable.ic_ai_rear_all_apps)?.let(::encodedIcon)),
                                getString(R.string.ai_rear_empty_slot), backdrop,
                                AiRearWallpaperArchive.Activation(packageName,
                                    AiRearWallpaperActivationActivity::class.java.name, binding))
                        }
                        check(prefs.edit().putString(AiRearWallpaperBinding.ACTIVE,
                            AiRearWallpaperBinding(id, binding, configuration).encode()).commit())
                        true
                    }
                }
            }
            applying = false
            if (restore) {
                RearWallpaperStandby.disarm()
                ready = false
            }
            readJournalId()
            message = result.fold(onSuccess = { ok ->
                getString(when {
                    !ok -> R.string.ai_rear_conflict
                    restore -> R.string.ai_rear_restored
                    else -> R.string.ai_rear_written
                })
            }, onFailure = {
                // Dedicated diagnostic: never log wallpaper paths, token, installed apps or payload.
                android.util.Log.w("AiRearWallpaper", (if (restore) "restore" else "apply") +
                    " failed: " + it.javaClass.simpleName +
                    (it.message?.takeIf { code -> code.matches(Regex("[A-Z_]{4,60}")) }?.let { code -> " " + code } ?: ""))
                getString(R.string.ai_rear_write_error)
            })
        }
    }

    private fun prepareShortcuts() {
        val remote = service ?: return
        val id = activeSlot ?: return
        if (preparing || applying || RearWallpaperStandby.ownsHost()) return
        if (!RearCastMode.useVirtual(this)) {
            message = getString(R.string.ai_rear_virtual_required)
            return
        }
        preparing = true
        message = ""
        lifecycleScope.launch {
            try {
                val launched = withContext(Dispatchers.IO) {
                    runCatching { prepareHost(remote, id) }.getOrDefault(false)
                }
                ready = launched && withTimeoutOrNull(6_000L) {
                    while (!RearWallpaperStandby.isParked()) delay(80)
                    true
                } == true
                if (!ready) message = getString(R.string.ai_rear_prepare_failed)
            } finally {
                preparing = false
            }
        }
    }

    private fun prepareHost(remote: ITaskService, id: String): Boolean {
        val binding = prefs.getString(AiRearWallpaperBinding.ACTIVE, null)
        if (AiRearWallpaperBinding.decode(binding)?.wallpaper != id ||
            journal(remote).state() != AiRearWallpaperJournal.State.APPLIED) return false
        val lock = getSystemService(KeyguardManager::class.java)
        fun current(): Boolean = !lock.isDeviceLocked && !isFinishing && !isDestroyed &&
            TaskServiceManager.isConnectedServiceCurrent(remote) && AiRearWallpaperSupport.shellReady(this) &&
            prefs.getString(AiRearWallpaperBinding.ACTIVE, null) == binding
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

    private fun croppedBackground(v: AiRearWallpaperArchive.Viewport): ByteArray {
        val original = BitmapFactory.decodeFile(wallpaperFile.path) ?: error("Invalid background")
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
    private fun Editor() {
        val appLookup = remember(apps) { apps.associateBy { it.component } }
        PageFrame(getString(R.string.ai_rear_title), ::back, "ai_rear_back") { modifier, inset ->
            LazyColumn(modifier.testTag("ai_rear_editor"),
                contentPadding = PaddingValues(20.dp, inset + 12.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("preview") {
                    AppCard {
                        AppSection(getString(R.string.ai_rear_preview))
                        GridPreview(appLookup)
                        AppRow(getString(R.string.ai_rear_choose_image), null, enabled = !backgroundBusy && !applying,
                            action = { chooseImage.launch(arrayOf("image/*")) })
                        AppRow(getString(R.string.ai_rear_choose_template),
                            getString(R.string.ai_rear_template_help), enabled = !backgroundBusy && !applying && !preparing,
                            action = { chooseTemplate.launch(arrayOf("application/zip", "application/octet-stream")) })
                        if (background != null) AppRow(getString(R.string.ai_rear_reset_image), null,
                            enabled = !backgroundBusy && !applying, action = {
                                if (!wallpaperFile.exists() || wallpaperFile.delete()) background = null
                                else message = getString(R.string.ai_rear_image_error)
                            })
                    }
                }
                item("buttons") {
                    AppCard {
                        AppSection(getString(R.string.ai_rear_button_settings))
                        AppToggle(getString(R.string.ai_rear_all_apps), getString(R.string.ai_rear_all_apps_hint),
                            grid.allApps, enabled = !applying, onChange = { save(grid.copy(allApps = it)) })
                        repeat(6) { index ->
                            if (index != 5 || !grid.allApps) {
                                AppDivider()
                                AppRow(getString(R.string.ai_rear_slot, index + 1),
                                    appLookup[grid.apps[index]]?.label ?: getString(R.string.ai_rear_empty_slot),
                                    enabled = !applying, action = { picking = index })
                            }
                        }
                    }
                }
                item("slots") {
                    AppCard {
                        AppSection(getString(R.string.ai_rear_pick_wallpaper))
                        Text(getString(R.string.ai_rear_pick_hint), Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            color = AppPalette.secondary(), fontSize = 13.sp)
                        if (service == null) Text(getString(R.string.ai_rear_connect), Modifier.padding(14.dp),
                            color = AppPalette.secondary(), fontSize = 13.sp)
                        else if (loading) Text(getString(R.string.ai_rear_loading), Modifier.padding(14.dp), color = AppPalette.secondary())
                        else if (slots?.isEmpty() == true) Text(getString(R.string.ai_rear_no_wallpapers), Modifier.padding(14.dp),
                            color = AppPalette.secondary())
                        if (service != null && !loading) TextButton(getString(R.string.ai_rear_refresh), ::loadSlots,
                            modifier = Modifier.padding(horizontal = 10.dp), minHeight = 48.dp)
                    }
                }
                items(slots.orEmpty(), key = { it.id }) { slot -> SlotRow(slot) }
                if (message.isNotEmpty()) item("message") {
                    Text(message, Modifier.padding(14.dp), color = AppPalette.secondary())
                }
                item("apply") {
                    val valid = grid.isReady { appLookup[it]?.canCast == true }
                    AppCard {
                        AppSection(getString(R.string.ai_rear_grid_cast_title))
                        Text(getString(R.string.ai_rear_grid_cast_help), Modifier.padding(14.dp),
                            color = AppPalette.secondary(), fontSize = 13.sp)
                        if (activeSlot != null) {
                            AppRow(getString(R.string.ai_rear_prepare),
                                getString(if (ready) R.string.ai_rear_prepared else R.string.ai_rear_prepare_hint),
                                enabled = !ready && !applying && !preparing && service != null,
                                action = ::prepareShortcuts)
                            if (ready) {
                                AppDivider()
                                AppRow(getString(R.string.ai_rear_disarm), null,
                                    enabled = !applying && !preparing, action = {
                                        RearWallpaperStandby.disarm()
                                        ready = false
                                    })
                            }
                            AppDivider()
                            AppRow(getString(R.string.ai_rear_restore), activeSlot?.takeLast(6),
                                enabled = !applying && !preparing && service != null,
                                action = { changeWallpaper(true) })
                        } else {
                            if (!valid) Text(getString(R.string.ai_rear_complete_grid),
                                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                color = AppPalette.secondary(), fontSize = 13.sp)
                            AppRow(getString(R.string.ai_rear_backup_replace), null,
                                enabled = selected != null && service != null && valid && !applying && !backgroundBusy,
                                action = { confirmReplace = true })
                        }
                    }
                }
            }
        }
        WindowDialog(show = confirmReplace, title = getString(R.string.ai_rear_backup_replace),
            onDismissRequest = { confirmReplace = false }) {
            Text(getString(R.string.ai_rear_grid_confirm), color = AppPalette.text())
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(getString(android.R.string.cancel), { confirmReplace = false }, modifier = Modifier.weight(1f))
                TextButton(getString(android.R.string.ok), {
                    confirmReplace = false
                    changeWallpaper(false)
                }, modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun SlotRow(slot: Slot) {
        var picture by remember(slot.id, slot.modified) { mutableStateOf<Bitmap?>(null) }
        val remote = service
        LaunchedEffect(remote, slot.id, slot.modified) {
            if (remote != null && slot.available) picture = withContext(thumbnailWorker) {
                runCatching { thumbnail(remote, slot) }.getOrNull()
            }
        }
        val date = remember(slot.modified) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(slot.modified))
        }
        val label = slot.title.ifEmpty { getString(R.string.ai_rear_fallback_title) }
        AppCard(Modifier.testTag("ai_rear_work_${slot.id}")) {
            if (picture != null) Image(picture!!.asImageBitmap(), null,
                Modifier.fillMaxWidth().aspectRatio(1.62f), contentScale = ContentScale.Crop)
            AppRow(label, getString(R.string.ai_rear_identity, date, slot.id.takeLast(6)),
                enabled = slot.available && !applying, action = if (slot.available) ({ selected = slot.id }) else null,
                trailing = {
                    if (!slot.available || selected == slot.id)
                        Text(getString(if (slot.available) R.string.ai_rear_selected else R.string.ai_rear_unavailable),
                            color = AppPalette.secondary(), fontSize = 12.sp)
                })
        }
    }

    @Composable
    private fun GridPreview(appLookup: Map<String, AiRearApp>) {
        Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().aspectRatio(976f / 596f)
            .clip(RoundedCornerShape(18.dp)).background(Color(0xFF222634))) {
            background?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Row(Modifier.fillMaxSize().padding(8.dp)) {
                // Preview reserves the camera strip. Device geometry admission will use the measured cutout.
                Box(Modifier.weight(.29f))
                Column(Modifier.weight(.71f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(2) { line ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(3) { col ->
                                val tile = grid.tile(line * 3 + col)
                                val component = (tile as? AiRearGrid.Tile.App)?.component
                                val label = when (tile) {
                                    AiRearGrid.Tile.AllApps -> getString(R.string.ai_rear_all_apps)
                                    AiRearGrid.Tile.Empty -> getString(R.string.ai_rear_empty_slot)
                                    is AiRearGrid.Tile.App -> appLookup[tile.component]?.label ?: getString(R.string.ai_rear_unavailable)
                                }
                                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xB52C303D)).padding(3.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (tile == AiRearGrid.Tile.AllApps) Image(
                                            painterResource(R.drawable.ic_ai_rear_all_apps), null,
                                            Modifier.fillMaxWidth(.34f).aspectRatio(1f),
                                        ) else if (component != null) PreviewIcon(component)
                                        Text(label, fontSize = 11.sp, color = Color.White,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PreviewIcon(component: String) {
        var icon by remember(component) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(component) {
            icon = withContext(Dispatchers.IO) {
                runCatching { packageManager.getActivityIcon(ComponentName.unflattenFromString(component)!!)
                    .toBitmap(80, 80) }.getOrNull()
            }
        }
        icon?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(.34f).aspectRatio(1f)) }
    }

    @Composable
    private fun Apps(index: Int) {
        var search by rememberSaveable(index) { mutableStateOf("") }
        val visible = remember(apps, search) {
            val query = search.trim()
            apps.filter { it.canCast &&
                (query.isEmpty() || it.label.contains(query, true) || it.component.contains(query, true)) }
        }
        PageFrame(getString(R.string.ai_rear_slot, index + 1), { picking = null }, "ai_rear_app_back") { modifier, inset ->
            LazyColumn(modifier, contentPadding = PaddingValues(20.dp, inset + 12.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item("search") {
                    TextField(value = search, onValueChange = { search = it },
                        label = getString(R.string.ai_rear_search_apps),
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                item("clear") {
                    AppCard { AppRow(getString(R.string.ai_rear_clear_slot), null, action = {
                        save(grid.bind(index, null)); picking = null
                    }) }
                }
                if (visible.isEmpty()) item("empty") {
                    Text(getString(R.string.ai_rear_no_eligible_apps), Modifier.padding(14.dp),
                        color = AppPalette.secondary())
                }
                items(visible, key = { it.component }) { app ->
                    AppCard { AppRow(app.label, ComponentName.unflattenFromString(app.component)?.packageName, action = {
                        save(grid.bind(index, app.component)); picking = null
                    }) }
                }
            }
        }
    }

    private companion object {
        val thumbnailWorker = Dispatchers.IO.limitedParallelism(2)
    }
}
