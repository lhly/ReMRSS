package top.zainzh.rearscreen.platform

import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONObject
import top.zainzh.rearscreen.ITaskService
import top.zainzh.rearscreen.TaskServiceManager
import java.io.Closeable
import java.util.UUID

/** One app-process connection to the existing privileged service; no second shell or device path. */
internal interface HostCastTaskSource {
    fun mainTaskSnapshot(): JSONObject?
    fun rearIdentity(): Pair<Int, String>?
    fun taskBaseComponent(taskId: Int, displayId: Int): String?
    fun taskIdByPackage(packageName: String): Int
    fun isTaskOnDisplay(taskId: Int, displayId: Int): Boolean
    fun autoContentRotation(taskId: Int, full: Boolean): Int = 0
    fun isExplicitTargetCurrent(target: HostCastTarget): Boolean = false
}

internal class AndroidCastTaskService(context: Context) : Closeable, HostCastTaskSource {
    private val app = context.applicationContext
    @Volatile private var remote: ITaskService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = ITaskService.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null }
        override fun onBindingDied(name: ComponentName) { remote = null }
        override fun onNullBinding(name: ComponentName) { remote = null }
    }

    init { TaskServiceManager.acquire(app, connection) }

    fun current(): ITaskService? = remote?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }

    override fun mainTaskSnapshot(): JSONObject? = runCatching {
        val value = JSONObject((current() ?: return null).getDisplayCastOccupancy(0))
        if (value.optString("status") != "ok" || value.optInt("display", -1) != 0
            || value.optInt("power", -1) != android.view.Display.STATE_ON
            || value.optInt("powerBefore", -1) != android.view.Display.STATE_ON
            || value.optInt("powerAfter", -1) != android.view.Display.STATE_ON
            || value.optString("source") !in setOf("activity_task", "activity_task_focused_stack", "activity_focused_stack")) null else value
    }.getOrNull()

    override fun rearIdentity(): Pair<Int, String>? = runCatching {
        val state = JSONObject((current() ?: return null).getRearDisplayState())
        val id = state.optInt("selectedId", -1); val unique = state.optString("selectedUnique")
        if (id <= 0 || unique.isBlank()) null else id to "$id:$unique"
    }.getOrNull()

    override fun taskBaseComponent(taskId: Int, displayId: Int): String? =
        runCatching { current()?.getTaskBaseComponent(taskId, displayId) }.getOrNull()
    override fun taskIdByPackage(packageName: String): Int =
        runCatching { current()?.getTaskIdByPackage(packageName) ?: -1 }.getOrDefault(-1)
    override fun isTaskOnDisplay(taskId: Int, displayId: Int): Boolean =
        runCatching { current()?.isTaskOnDisplay(taskId, displayId) == true }.getOrDefault(false)

    override fun autoContentRotation(taskId: Int, full: Boolean): Int = runCatching {
        val plan = current()?.getRearCastRenderPlan(taskId, null,
            200, false, full) ?: return 0
        JSONObject(plan).optInt("autoRotation", 0)
    }.getOrDefault(0)

    /** Preparation, admission and physical moves require the original exact main-focused picker. */
    override fun isExplicitTargetCurrent(target: HostCastTarget): Boolean =
        isExplicitTargetCurrent(target, null)

    /** The same exact approval can survive focus switching to this operation's own rear presenter. */
    fun isExplicitTargetCurrentBeforeVirtualMove(target: HostCastTarget, rearHostTask: () -> Int): Boolean =
        isExplicitTargetCurrent(target) { pickerId ->
            val host = rearHostTask()
            host > 0 && runCatching {
                current()?.isControlledCastPickerStillOnMain(pickerId, target.taskId, target.baseComponent,
                    target.expectedTopComponent ?: return@runCatching false, host,
                    target.rearDisplayId, target.rearIdentity) == true
            }.getOrDefault(false)
        }

    private fun isExplicitTargetCurrent(target: HostCastTarget,
        proveDisplayLocalPicker: ((Int) -> Boolean)?): Boolean {
        val expectedTop = target.expectedTopComponent ?: return true
        if (rearIdentity() != (target.rearDisplayId to target.rearIdentity) ||
            !isTaskOnDisplay(target.taskId, target.mainDisplayId)) return false
        val pickerId = target.confirmationTaskId ?: return false
        val pickerComponent = target.confirmationComponent ?: return false
        val picker = CastComponentIdentity.normalize(pickerComponent) ?: return false
        val snapshot = mainTaskSnapshot() ?: return false
        if (!confirmationPickerIsCurrent(snapshot, pickerId, picker, target.mainDisplayId,
                proveDisplayLocalPicker != null)) return false
        val actualBase = taskBaseComponent(target.taskId, target.mainDisplayId) ?: return false
        if (CastComponentIdentity.normalize(actualBase) != CastComponentIdentity.normalize(target.baseComponent)) return false
        val rows = snapshot.optJSONArray("tasks") ?: return false
        val targetRow = (0 until rows.length()).mapNotNull(rows::optJSONObject)
            .singleOrNull { it.optInt("task", -1) == target.taskId } ?: return false
        val pkg = CastComponentIdentity.parse(target.baseComponent)?.first ?: return false
        if (targetRow.optString("base") != pkg || targetRow.optString("top") != pkg ||
            CastComponentIdentity.normalize(targetRow.optString("baseComponent")) != CastComponentIdentity.normalize(target.baseComponent) ||
            CastComponentIdentity.normalize(targetRow.optString("topComponent")) != CastComponentIdentity.normalize(expectedTop)) return false
        val focused = snapshot.optInt("focusedTask", -1)
        if (focused == pickerId) return true
        return focused < 0 && proveDisplayLocalPicker?.invoke(pickerId) == true
    }

    private fun confirmationPickerIsCurrent(snapshot: JSONObject, taskId: Int, component: String,
        displayId: Int, mayUseDisplayLocalProof: Boolean): Boolean {
        val focused = snapshot.optInt("focusedTask", -1)
        if ((focused != taskId && !(focused < 0 && mayUseDisplayLocalProof))
            || !isTaskOnDisplay(taskId, displayId)) return false
        val rows = snapshot.optJSONArray("tasks") ?: return false
        val row = (0 until rows.length()).mapNotNull(rows::optJSONObject)
            .singleOrNull { it.optInt("task", -1) == taskId } ?: return false
        val base = taskBaseComponent(taskId, displayId) ?: return false
        return row.optString("base") == app.packageName && row.optString("top") == app.packageName &&
            CastComponentIdentity.normalize(base) == component &&
            CastComponentIdentity.normalize(row.optString("baseComponent")) == component &&
            CastComponentIdentity.normalize(row.optString("topComponent")) == component &&
            row.opt("visible") == true && row.opt("requested") == true && row.opt("sleeping") == false
    }

    override fun close() { remote = null; TaskServiceManager.release(connection) }
}

/** Resolves selectors from one typed host snapshot and retains only short-lived, owner-bound choices. */
internal class AndroidHostCastTargetResolver(
    context: Context,
    private val service: HostCastTaskSource,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : HostCastTargetResolver {
    private data class ChoiceRecord(val owner: SessionOwner, val taskId: Int, val pkg: String,
                                    val component: String, val rearId: Int, val rearIdentity: String,
                                    val expiresAt: Long)
    private data class ForegroundChoiceRecord(val owner: SessionOwner, val taskId: Int, val pkg: String,
        val base: String, val top: String, val rearId: Int, val rearIdentity: String, val expiresAt: Long)
    private data class ForegroundChoiceDraft(
        val token: String, val taskId: Int, val packageName: String, val applicationLabel: String,
        val activityLabel: String, val shortComponentName: String,
    )
    private val app = context.applicationContext
    private val lock = Any()
    private val choices = HashMap<String, ChoiceRecord>()
    private val foregroundChoices = HashMap<String, ForegroundChoiceRecord>()

    override fun listForegroundChoices(owner: SessionOwner): List<HostCastForegroundChoice> = try {
        if (!isAuthenticatedModHost(owner)) return emptyList()
        val snapshot = service.mainTaskSnapshot() ?: return emptyList()
        val rear = service.rearIdentity() ?: return emptyList()
        val rows = snapshot.optJSONArray("tasks") ?: return emptyList()
        val taskCounts = HashMap<Int, Int>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: return emptyList()
            val id = row.optInt("task", -1)
            if (id > 0) taskCounts[id] = (taskCounts[id] ?: 0) + 1
        }
        val seen = HashSet<Int>()
        val drafts = ArrayList<ForegroundChoiceDraft>()
        synchronized(lock) { foregroundChoices.entries.removeAll { it.value.expiresAt <= clock() } }
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: return emptyList()
            val task = row.optInt("task", -1)
            val pkg = row.optString("base")
            val topPkg = row.optString("top")
            if (task <= 0 || pkg.isBlank() || pkg == app.packageName || topPkg != pkg ||
                taskCounts[task] != 1 || !seen.add(task) || !row.has("visible") || row.isNull("visible") ||
                !row.has("requested") || row.isNull("requested") || !row.has("sleeping") || row.isNull("sleeping")) continue
            val visible = row.opt("visible") as? Boolean ?: continue
            val requested = row.opt("requested") as? Boolean ?: continue
            val sleeping = row.opt("sleeping") as? Boolean ?: continue
            if (visible != requested || sleeping) continue
            val base = service.taskBaseComponent(task, 0)?.takeIf(String::isNotBlank) ?: continue
            val baseFromSnapshot = row.optString("baseComponent").takeIf(String::isNotBlank) ?: continue
            val top = row.optString("topComponent").takeIf(String::isNotBlank) ?: continue
            val normalizedBase = CastComponentIdentity.normalize(base) ?: continue
            if (CastComponentIdentity.normalize(baseFromSnapshot) != normalizedBase ||
                CastComponentIdentity.parse(base)?.first != pkg || CastComponentIdentity.parse(top)?.first != pkg) continue
            val parsed = CastComponentIdentity.parse(base) ?: continue
            val packageManager = runCatching { app.packageManager }.getOrNull()
            val appLabel = packageManager?.let { pm -> runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
            }.getOrNull() }.orEmpty().ifBlank { pkg }
            val activityLabel = packageManager?.let { pm -> runCatching {
                pm.getActivityInfo(ComponentName(parsed.first, parsed.second), 0).loadLabel(pm).toString().trim()
            }.getOrDefault("") }.orEmpty()
            val token = UUID.randomUUID().toString()
            synchronized(lock) { foregroundChoices[token] = ForegroundChoiceRecord(owner, task, pkg, base, top,
                rear.first, rear.second, clock() + CHOICE_TTL_MS) }
            drafts += ForegroundChoiceDraft(token, task, pkg, appLabel, activityLabel,
                parsed.second.substringAfterLast('.'))
        }
        val labels = foregroundChoiceLabels(drafts.map { candidate ->
            ForegroundChoiceLabelCandidate(candidate.packageName, candidate.applicationLabel,
                candidate.activityLabel, candidate.shortComponentName, candidate.taskId)
        })
        drafts.mapIndexed { index, draft -> HostCastForegroundChoice(draft.token, labels[index], draft.packageName) }
    } catch (_: Exception) { emptyList() }

    private fun isAuthenticatedModHost(owner: SessionOwner): Boolean =
        owner.transportPackageName == app.packageName && owner.principal is SessionPrincipal.SignedMod

    override fun listChoices(owner: SessionOwner): List<HostCastTargetChoice> {
        return try {
            val snapshot = service.mainTaskSnapshot() ?: return emptyList()
            val rear = service.rearIdentity() ?: return emptyList()
            val rows = snapshot.getJSONArray("tasks")
            val result = ArrayList<HostCastTargetChoice>()
            synchronized(lock) { choices.entries.removeAll { it.value.expiresAt <= clock() } }
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val task = row.optInt("task", -1)
                val pkg = row.optString("base")
                if (task <= 0 || pkg.isBlank() || pkg == app.packageName || pkg != row.optString("top")
                    || row.optBoolean("visible") != true || row.has("requested") && !row.isNull("requested") && !row.optBoolean("requested")
                    || row.optBoolean("sleeping")) continue
                val exact = service.taskBaseComponent(task, 0)?.takeIf(String::isNotBlank) ?: continue
                val parsed = CastComponentIdentity.parse(exact) ?: continue
                if (parsed.first != pkg) continue
                val token = UUID.randomUUID().toString()
                val label = runCatching { app.packageManager.getApplicationLabel(
                    app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                synchronized(lock) {
                    choices[token] = ChoiceRecord(owner, task, pkg, exact, rear.first, rear.second,
                        clock() + CHOICE_TTL_MS)
                }
                result.add(HostCastTargetChoice(token, label, pkg, task, exact))
            }
            result
        } catch (_: Exception) { emptyList() }
    }

    override fun resolve(owner: SessionOwner, request: CastTargetRequest): HostCastTarget? {
        if (request.mode == CastMode.DIRECT_PHYSICAL &&
            (request.region != null && request.region != CastRegion.SAFE
                || request.contentRotation != null && request.contentRotation != 0
                || request.requestedDensityDpi != null)) return null
        val rear = service.rearIdentity() ?: return null
        val snapshot = service.mainTaskSnapshot() ?: return null
        var selectedTopComponent: String? = null
        var confirmationTaskId: Int? = null
        var confirmationComponent: String? = null
        val selected = when (val selector = request.selector) {
            CastTargetSelector.CurrentForeground -> focusedTarget(snapshot) ?: return null
            is CastTargetSelector.Component -> {
                val requested = CastComponentIdentity.parse(selector.flattened) ?: return null
                if (requested.first == app.packageName) return null
                val task = service.taskIdByPackage(requested.first)
                exactTask(snapshot, task, requested.first, selector.flattened) ?: return null
            }
            is CastTargetSelector.ChoiceToken -> {
                val row = synchronized(lock) {
                    val choice = choices.remove(selector.opaque)
                    choice?.takeIf { it.owner == owner && it.expiresAt > clock() }
                } ?: return null
                if (row.rearId != rear.first || row.rearIdentity != rear.second) return null
                exactTask(snapshot, row.taskId, row.pkg, row.component) ?: return null
            }
            is CastTargetSelector.ForegroundChoiceToken -> {
                val pickerComponent = CastComponentIdentity.normalize(selector.confirmationComponent) ?: return null
                if (selector.confirmationTaskId <= 0) return null
                val exactPicker = CastComponentIdentity.normalize(
                    "${app.packageName}/top.zainzh.rearscreen.platform.mods.ModCastTargetPickerActivity")
                    ?: return null
                if (pickerComponent != exactPicker) return null
                val row = synchronized(lock) {
                    val choice = foregroundChoices[selector.opaque] ?: return@synchronized null
                    if (choice.owner != owner) return@synchronized null
                    foregroundChoices.remove(selector.opaque)
                    choice.takeIf { it.expiresAt > clock() }
                } ?: return null
                if (!confirmationPickerIsCurrent(snapshot, selector.confirmationTaskId, pickerComponent)) return null
                if (row.rearId != rear.first || row.rearIdentity != rear.second) return null
                val exact = exactTaskIdentity(snapshot, row.taskId, row.pkg, row.base, row.top) ?: return null
                selectedTopComponent = row.top
                confirmationTaskId = selector.confirmationTaskId
                confirmationComponent = pickerComponent
                exact
            }
        }
        val pkg = CastComponentIdentity.parse(selected.second)?.first ?: return null
        val presentation = if (request.mode == CastMode.VIRTUAL && (request.region == null || request.contentRotation == null))
            top.zainzh.rearscreen.RearPresentationPreferences.read(app, pkg) else null
        val region = if (request.mode == CastMode.DIRECT_PHYSICAL) CastRegion.SAFE
            else request.region ?: if (presentation?.full == true) CastRegion.FULL else CastRegion.SAFE
        val rotation = if (request.mode == CastMode.DIRECT_PHYSICAL) 0
            else request.contentRotation ?: presentation?.rotation
                ?: service.autoContentRotation(selected.first, region == CastRegion.FULL)
        return HostCastTarget(UUID.randomUUID().toString(), selected.first, selected.second,
            request.selector.let { if (it is CastTargetSelector.Component) it.flattened else null },
            0, rear.first, rear.second, request.mode,
            region, rotation, request.requestedDensityDpi, selectedTopComponent, confirmationTaskId, confirmationComponent)
    }

    override fun isCurrent(target: HostCastTarget): Boolean {
        return try {
            if (target.expectedTopComponent != null) return service.isExplicitTargetCurrent(target)
            val rear = service.rearIdentity() ?: return false
            if (rear.first != target.rearDisplayId || rear.second != target.rearIdentity) return false
            val snapshot = service.mainTaskSnapshot() ?: return false
            exactTask(snapshot, target.taskId, CastComponentIdentity.parse(target.baseComponent)?.first
                ?: return false, target.baseComponent) != null
        } catch (_: Exception) { false }
    }

    /** Identity-only check for the explicit Mod choice; unlike `exactTask`, visibility is separate. */
    private fun exactTaskIdentity(snapshot: JSONObject, task: Int, pkg: String, base: String,
        top: String): Pair<Int, String>? {
        if (task <= 0 || pkg == app.packageName || !service.isTaskOnDisplay(task, 0)) return null
        val actualBase = service.taskBaseComponent(task, 0) ?: return null
        val rows = snapshot.optJSONArray("tasks") ?: return null
        val row = (0 until rows.length()).mapNotNull(rows::optJSONObject)
            .singleOrNull { it.optInt("task", -1) == task } ?: return null
        if (row.optString("base") != pkg || row.optString("top") != pkg ||
            CastComponentIdentity.normalize(actualBase) != CastComponentIdentity.normalize(base) ||
            CastComponentIdentity.normalize(row.optString("baseComponent")) != CastComponentIdentity.normalize(base) ||
            CastComponentIdentity.normalize(row.optString("topComponent")) != CastComponentIdentity.normalize(top) ||
            CastComponentIdentity.parse(base)?.first != pkg || CastComponentIdentity.parse(top)?.first != pkg) return null
        return task to actualBase
    }

    /** Hidden is acceptable only while the exact picker task is itself the visible focused task. */
    private fun confirmationPickerIsCurrent(snapshot: JSONObject, taskId: Int, component: String): Boolean {
        if (snapshot.optInt("focusedTask", -1) != taskId || !service.isTaskOnDisplay(taskId, 0)) return false
        val rows = snapshot.optJSONArray("tasks") ?: return false
        val row = (0 until rows.length()).mapNotNull(rows::optJSONObject)
            .singleOrNull { it.optInt("task", -1) == taskId } ?: return false
        val actualBase = service.taskBaseComponent(taskId, 0) ?: return false
        return row.optString("base") == app.packageName && row.optString("top") == app.packageName &&
            CastComponentIdentity.normalize(actualBase) == component &&
            CastComponentIdentity.normalize(row.optString("baseComponent")) == component &&
            CastComponentIdentity.normalize(row.optString("topComponent")) == component &&
            row.opt("visible") == true && row.opt("requested") == true && row.opt("sleeping") == false
    }

    private fun focusedTarget(snapshot: JSONObject): Pair<Int, String>? {
        val focused = snapshot.optInt("focusedTask", -1)
        val rows = snapshot.optJSONArray("tasks") ?: return null
        var found: JSONObject? = null
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: return null
            if (row.optInt("task", -1) == focused) {
                if (found != null) return null
                found = row
            }
        }
        val row = found ?: return null
        val pkg = row.optString("base")
        if (pkg.isBlank() || pkg == app.packageName || row.optString("top") != pkg || !row.optBoolean("visible")
            || row.has("requested") && !row.isNull("requested") && !row.optBoolean("requested") || row.optBoolean("sleeping")) return null
        val task = row.optInt("task", -1)
        val component = service.taskBaseComponent(task, 0)?.takeIf(String::isNotBlank) ?: return null
        return task to component
    }

    private fun exactTask(snapshot: JSONObject, task: Int, pkg: String,
                          component: String): Pair<Int, String>? {
        if (task <= 0 || !service.isTaskOnDisplay(task, 0)) return null
        val actual = service.taskBaseComponent(task, 0) ?: return null
        if (CastComponentIdentity.normalize(actual) != CastComponentIdentity.normalize(component)
            || CastComponentIdentity.parse(actual)?.first != pkg) return null
        val rows = snapshot.optJSONArray("tasks") ?: return null
        val row = (0 until rows.length()).mapNotNull(rows::optJSONObject).singleOrNull { it.optInt("task", -1) == task }
            ?: return null
        if (row.optString("base") != pkg || row.optString("top") != pkg || !row.optBoolean("visible")
            || row.has("requested") && !row.isNull("requested") && !row.optBoolean("requested") || row.optBoolean("sleeping")) return null
        return task to actual
    }

    companion object { private const val CHOICE_TTL_MS = 30_000L }
}

/** Pure flattened-component parser shared with JVM fake-transport tests. */
internal object CastComponentIdentity {
    fun parse(value: String): Pair<String, String>? {
        val separator = value.indexOf('/')
        if (separator <= 0 || separator == value.length - 1 || value.indexOf('/', separator + 1) >= 0) return null
        val pkg = value.substring(0, separator)
        var klass = value.substring(separator + 1)
        if (pkg.isBlank() || klass.isBlank() || pkg.any { it.isWhitespace() } || klass.any { it.isWhitespace() }) return null
        if (klass.startsWith('.')) klass = pkg + klass
        if (!klass.contains('.')) return null
        return pkg to klass
    }

    fun normalize(value: String): String? = parse(value)?.let { "${it.first}/${it.second}" }
}
