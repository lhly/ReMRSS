package top.zainzh.rearscreen.platform

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Display
import org.json.JSONObject
import top.zainzh.rearscreen.BuildConfig
import top.zainzh.rearscreen.ControlledCastHostComponent
import top.zainzh.rearscreen.VirtualRearHostActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Real adapter over the selected ITaskService and the existing same-host dual-Surface virtual path. */
internal class AndroidControlledCastBackend(
    context: Context,
    private val taskService: AndroidCastTaskService,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : ControlledCastBackend {
    private val app = context.applicationContext
    private data class Operation(
        val id: String,
        val owner: CastOwnerKey,
        val coordinatorGeneration: Long,
        val target: HostCastTarget,
        val previous: CastOccupantIdentity?,
        val physical: Boolean = false,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        @Volatile var backendSessionId: String? = null,
        @Volatile var backendGeneration: Long = 0L,
        @Volatile var backendActionInFlight: Boolean = false,
        @Volatile var physicalReplacementToken: String? = null,
        @Volatile var authorizationCurrent: (() -> Boolean)? = null
    )
    private val operations = ConcurrentHashMap<String, Operation>()
    private val cancelledBeforeRegistration = ConcurrentHashMap<String, Long>()
    private val ownedSessions = ConcurrentHashMap<String, Operation>()
    private val sessionOwners = ConcurrentHashMap<String, Pair<CastOwnerKey, Long>>()
    /** Coordinator operation IDs are not backend session IDs; retain exact mapping across Ready. */
    private val operationSessions = ConcurrentHashMap<String, String>()
    /** OWNER_B physical journal is retired only after the Coordinator consumes ReplaceReady. */
    private val pendingPhysicalReplacements = ConcurrentHashMap<String, Boolean>()
    private val pendingReplacementOldSessions = ConcurrentHashMap<String, Operation>()
    private val admissionTraceSequence = AtomicLong(0L)
    private val poller = ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(8), { r -> Thread(r, "controlled-cast-backend").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    override fun inspectOccupant(rearDisplayId: Int, rearIdentity: String): CastOccupantIdentity? =
        inspectOccupantDetailed(rearDisplayId, rearIdentity) { }

    private fun inspectOccupantDetailed(rearDisplayId: Int, rearIdentity: String,
        onPhysicalRead: (LegacyDisplayOccupancy.PhysicalProjectionAdmission) -> Unit): CastOccupantIdentity? {
        val remote = taskService.current() ?: throw IllegalStateException("task_service_unavailable")
        val rear = readRearIdentity(remote) ?: throw IllegalStateException("rear_identity_unavailable")
        if (rear.first != rearDisplayId || rear.second != rearIdentity) throw IllegalStateException("rear_identity_changed")
        val status = JSONObject(remote.getRearVirtualCastStatus())
        if (status.optString("state") == "running" || status.optString("state") == "starting") {
            val host = status.optInt("host", -1)
            val base = remote.getTaskBaseComponent(host, rearDisplayId)
            if (host <= 0 || !remote.isTaskOnDisplay(host, rearDisplayId)
                || ControlledCastBackendPolicy.canonicalComponent(base) !=
                    ControlledCastBackendPolicy.canonicalComponent(ControlledCastHostComponent.expected(
                        app.packageName, VirtualRearHostActivity::class.java.name) ?: ""))
                throw IllegalStateException("virtual_owner_unverified")
            val task = status.optInt("task", -1)
            val component = status.optString("baseComponent")
            val inputId = status.optJSONObject("input")?.optString("id", "").orEmpty()
            if (task <= 0 || component.isBlank() || inputId.isBlank()) throw IllegalStateException("virtual_target_unverified")
            val session = status.optString("id")
            val ownerRecord = sessionOwners[session] ?: return CastOccupantIdentity(CastMode.VIRTUAL,
                "unmanaged", session, status.optLong("generation", -1L), -1L, task, host, inputId,
                component, rearDisplayId, rearIdentity, CastOccupantSource.VIRTUAL_OWNER)
            val owned = ownedSessions[session] ?: return CastOccupantIdentity(CastMode.VIRTUAL,
                "unmanaged", session, status.optLong("generation", -1L), -1L, task, host, inputId,
                component, rearDisplayId, rearIdentity, CastOccupantSource.VIRTUAL_OWNER)
            if (owned.backendGeneration != status.optLong("generation", -1L)) throw IllegalStateException("virtual_generation_changed")
            return CastOccupantIdentity(CastMode.VIRTUAL, ownerRecord.first.toString(), session,
                status.optLong("generation", -1L), ownerRecord.second, task, host, inputId,
                component, rearDisplayId, rearIdentity, CastOccupantSource.VIRTUAL_OWNER)
        }
        val snapshot = JSONObject(remote.getDisplayCastOccupancy(rearDisplayId))
        if (snapshot.optString("status") != "ok" || snapshot.optInt("display", -1) != rearDisplayId)
            throw IllegalStateException("rear_occupancy_unknown")
        val power = snapshot.powerEvidence(rearDisplayId)
        if (power == null || !power.isStableKnown())
            throw IllegalStateException("rear_power_unverified")
        val rows = snapshot.optJSONArray("tasks") ?: throw IllegalStateException("rear_occupancy_unknown")
        val physicalRead = LegacyDisplayOccupancy.readPhysicalProjectionAdmission(app, remote, rearDisplayId)
        onPhysicalRead(physicalRead.diagnostic)
        if (physicalRead.hasOwner) {
            val physicalOwner = physicalRead.packageTask
                ?: throw IllegalStateException("physical_owner_unknown")
            val separator = physicalOwner.lastIndexOf(':')
            val pkg = physicalOwner.substring(0, separator)
            val task = physicalOwner.substring(separator + 1).toIntOrNull() ?: throw IllegalStateException("physical_owner_unknown")
            val generation = LegacyDisplayOccupancy.physicalProjectionGeneration(app, task, rearDisplayId)
            onPhysicalRead(physicalRead.diagnostic.copy(
                generationMatches = physicalRead.diagnostic.storedGeneration > 0L
                    && physicalRead.diagnostic.storedGeneration == generation))
            val component = remote.getTaskBaseComponent(task, rearDisplayId)
                ?: throw IllegalStateException("physical_component_unknown")
            if (generation <= 0L) throw IllegalStateException("physical_generation_unknown")
            val session = "physical:$task:$generation"
            val ownerRecord = sessionOwners[session] ?: return ControlledCastWakeTransition.physicalProjection(
                "unmanaged", session, generation, -1L, task, component, rearDisplayId, rearIdentity)
            return ControlledCastWakeTransition.physicalProjection(ownerRecord.first.toString(), session,
                generation, ownerRecord.second, task, component, rearDisplayId, rearIdentity)
        }
        val admissionRows = ArrayList<CastAdmissionRowEvidence>(rows.length())
        val rowTaskIds = ArrayList<Pair<Int, String>>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: throw IllegalStateException("rear_row_unknown")
            val pkg = row.optString("base")
            val task = row.optInt("task", -1)
            if (pkg.isBlank() || task <= 0) throw IllegalStateException("rear_row_identity_unknown")
            val visible = row.opt("visible") as? Boolean
            val requested = row.opt("requested") as? Boolean
            val sleeping = row.opt("sleeping") as? Boolean
            val component = remote.getTaskBaseComponent(task, rearDisplayId)
                ?.takeIf(String::isNotBlank) ?: throw IllegalStateException("rear_component_unknown")
            admissionRows.add(CastAdmissionRowEvidence(pkg, row.optString("top", ""), component,
                row.optString("topComponent", ""), visible, requested, sleeping, task,
                row.optInt("userId", -1)))
            rowTaskIds.add(task to pkg)
        }
        val rowDecision = ControlledCastAdmissionRows.classify(power, admissionRows)
        val blocked = rowDecision.firstBlockedIndex ?: return null
        val row = admissionRows[blocked]
        val (task, pkg) = rowTaskIds[blocked]
        return ControlledCastWakeTransition.blockedTaskRow(pkg, task, row.baseComponent.orEmpty(), rearDisplayId, rearIdentity)
    }

    /** Occupancy and power are interpreted from one fresh typed snapshot immediately before a side effect. */
    private fun inspectStartEvidence(remote: top.zainzh.rearscreen.ITaskService, rearDisplayId: Int,
                                     rearIdentity: String, trace: AdmissionAttemptTrace? = null,
                                     phase: String = "pre_wake",
                                     allowOemWakeTransition: Boolean = false,
                                     expectedPostWakeOemTaskId: Int? = null,
                                     expectedPostWakeOemUserId: Int? = null,
                                     expectedPostWakeOemComponent: String? = null): ControlledCastStartEvidence {
        var physicalBefore: LegacyDisplayOccupancy.PhysicalProjectionAdmission? = null
        val occupantBefore = try {
            inspectOccupantDetailed(rearDisplayId, rearIdentity) { physicalBefore = it }
        } catch (failure: Exception) {
            if (physicalBefore != null) trace?.physicalOwnerRead(phase, physicalBefore, null, false, false, false)
            throw failure
        }
        val snapshot = JSONObject(remote.getDisplayCastOccupancy(rearDisplayId))
        if (snapshot.optString("status") != "ok" || snapshot.optInt("display", -1) != rearDisplayId)
            throw IllegalStateException("rear_occupancy_unknown")
        val power = snapshot.powerEvidence(rearDisplayId)
            ?: throw IllegalStateException("rear_power_unverified")
        if (!power.isStableKnown()) throw IllegalStateException("rear_power_unverified")
        val rows = snapshot.optJSONArray("tasks") ?: throw IllegalStateException("rear_occupancy_unknown")
        val admissionRows = ArrayList<CastAdmissionRowEvidence>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: throw IllegalStateException("rear_row_unknown")
            val task = row.optInt("task", -1)
            admissionRows.add(CastAdmissionRowEvidence(row.optString("base", ""), row.optString("top", ""),
                row.optString("baseComponent").takeIf(String::isNotBlank),
                row.optString("topComponent").takeIf(String::isNotBlank),
                row.opt("visible") as? Boolean, row.opt("requested") as? Boolean,
                row.opt("sleeping") as? Boolean, task, row.optInt("userId", -1)))
        }
        val rowDecision = ControlledCastAdmissionRows.classify(power, admissionRows)
        val oemVerifier = ControlledCastWakeTransition.OemLauncherVerifier { component, userId ->
            top.zainzh.rearscreen.RearDisplay.isValidatedRearLauncherForAdmission(component, userId)
        }
        val transitionTaskId = if (allowOemWakeTransition)
            ControlledCastWakeTransition.postWakeTaskId(expectedPostWakeOemTaskId,
                expectedPostWakeOemUserId, expectedPostWakeOemComponent, power,
                occupantBefore, admissionRows, oemVerifier)
            else null
        if (occupantBefore == null && !rowDecision.empty && transitionTaskId == null)
            throw IllegalStateException("rear_occupant_changed")
        val preWakeCandidate = if (occupantBefore == null)
            ControlledCastWakeTransition.preWakeTaskId(power, admissionRows, oemVerifier) else null
        val preWakeOemTaskId = preWakeCandidate?.takeIf { taskId ->
            val typedComponent = admissionRows.singleOrNull()?.baseComponent.orEmpty()
            val remoteComponent = remote.getTaskBaseComponent(taskId, rearDisplayId).orEmpty()
            ControlledCastBackendPolicy.canonicalComponent(typedComponent) ==
                ControlledCastBackendPolicy.canonicalComponent(remoteComponent)
        }
        val preWakeOemRow = admissionRows.singleOrNull()?.takeIf { it.taskId == preWakeOemTaskId }
        val preWakeOemUserId = preWakeOemRow?.userId?.takeIf { preWakeOemTaskId != null }
        val preWakeOemComponent = preWakeOemRow?.baseComponent?.takeIf { preWakeOemTaskId != null }
        var physicalAfter: LegacyDisplayOccupancy.PhysicalProjectionAdmission? = null
        val occupantAfter = try {
            inspectOccupantDetailed(rearDisplayId, rearIdentity) { physicalAfter = it }
        } catch (failure: Exception) {
            trace?.physicalOwnerRead(phase, physicalBefore, physicalAfter, false, false, false)
            throw failure
        }
        trace?.snapshot(phase, summarizeAdmissionSnapshot(snapshot, power, rows, occupantBefore))
        fun matchesOemTask(read: LegacyDisplayOccupancy.PhysicalProjectionAdmission?): Boolean =
            read != null && read.taskId > 0 && admissionRows.count { row -> row.taskId == read.taskId
                && row.userId >= 0 && ControlledCastStandbyPolicy.isExactOemDesktop(row.basePackage,
                    row.topPackage, row.baseComponent, row.topComponent) } == 1
        val beforeOemTask = matchesOemTask(physicalBefore)
        val afterOemTask = matchesOemTask(physicalAfter)
        val sameOemTask = beforeOemTask && afterOemTask && physicalBefore?.taskId == physicalAfter?.taskId
        trace?.physicalOwnerRead(phase, physicalBefore, physicalAfter, beforeOemTask, afterOemTask, sameOemTask)
        if (ControlledCastWakeTransition.canKeepPollingExactOem(transitionTaskId, occupantBefore, occupantAfter))
            return ControlledCastStartEvidence(null, power, oemWakeTransition = true,
                oemWakeTransitionTaskId = transitionTaskId, preWakeOemTaskId = preWakeOemTaskId,
                preWakeOemUserId = preWakeOemUserId, preWakeOemComponent = preWakeOemComponent)
        if (allowOemWakeTransition && ControlledCastWakeTransition.exactFallbackSettledToEmpty(
                occupantBefore, occupantAfter, expectedPostWakeOemTaskId, expectedPostWakeOemUserId,
                expectedPostWakeOemComponent, power, admissionRows, oemVerifier))
            return ControlledCastStartEvidence(null, power, preWakeOemTaskId = preWakeOemTaskId,
                preWakeOemUserId = preWakeOemUserId, preWakeOemComponent = preWakeOemComponent)
        if (occupantAfter != occupantBefore) {
            trace?.ownerChanged(phase, occupantAfter)
            throw IllegalStateException("rear_occupant_changed")
        }
        return ControlledCastStartEvidence(occupantAfter, power, preWakeOemTaskId = preWakeOemTaskId,
            preWakeOemUserId = preWakeOemUserId, preWakeOemComponent = preWakeOemComponent)
    }

    private fun summarizeAdmissionSnapshot(snapshot: JSONObject, power: CastDisplayPowerEvidence,
                                           rows: org.json.JSONArray,
                                           occupant: CastOccupantIdentity?): AdmissionSnapshotSummary {
        val evidence = ArrayList<CastAdmissionRowEvidence>(rows.length())
        val oemFlags = ArrayList<String>()
        var malformed = 0
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
            if (row == null) { malformed++; continue }
            val base = row.optString("base", "")
            val top = row.optString("top", "")
            val typedBase = row.optString("baseComponent", "").takeIf(String::isNotBlank)
            val topComponent = row.optString("topComponent", "").takeIf(String::isNotBlank)
            evidence.add(CastAdmissionRowEvidence(base, top, typedBase, topComponent,
                row.opt("visible") as? Boolean, row.opt("requested") as? Boolean,
                row.opt("sleeping") as? Boolean, row.optInt("task", -1), row.optInt("userId", -1)))
            val isOemCandidate = base == OEM_PACKAGE || top == OEM_PACKAGE
            if (isOemCandidate) {
                val typedExact = ControlledCastStandbyPolicy.isExactOemDesktop(base, top, typedBase, topComponent)
                oemFlags.add("basePkg=${base == OEM_PACKAGE},topPkg=${top == OEM_PACKAGE},typed=${typedExact},visible=${tri(row.opt("visible"))},requested=${tri(row.opt("requested"))},sleeping=${tri(row.opt("sleeping"))}")
            }
        }
        val rowsResult = ControlledCastAdmissionRows.classify(power, evidence)
        return AdmissionSnapshotSummary(snapshot.optString("status", "unknown"),
            snapshot.optString("source", "unknown"), snapshot.optInt("power", -1),
            snapshot.optInt("powerBefore", -1), snapshot.optInt("powerAfter", -1), rows.length(),
            rowsResult.exactOemRows, rowsResult.duplicateOemRows, malformed + rowsResult.blockedRows,
            malformed + rowsResult.unknownRows, occupantClass(occupant), oemFlags)
    }

    private data class AdmissionSnapshotSummary(
        val status: String, val source: String, val power: Int, val powerBefore: Int, val powerAfter: Int,
        val rowCount: Int, val exactOemRows: Int, val duplicateOemRows: Boolean, val blockedRows: Int,
        val unknownRows: Int, val occupantClass: String, val oemFlags: List<String>
    ) {
        fun signature(phase: String): String = listOf(phase, status, source, power, powerBefore, powerAfter,
            rowCount, exactOemRows, duplicateOemRows, blockedRows, unknownRows,
            occupantClass, oemFlags.joinToString(";")).joinToString("|")

        fun logFields(): String = "status=$status source=${safeAdmissionSource(source)} power=${admissionPowerName(power)}/${admissionPowerName(powerBefore)}/${admissionPowerName(powerAfter)} " +
            "rows=$rowCount exact_oem=$exactOemRows duplicate_oem=$duplicateOemRows blocked=$blockedRows unknown=$unknownRows " +
            "owner_before=$occupantClass oem_flags=${oemFlags.ifEmpty { listOf("none") }.joinToString(";")}"
    }

    /** Debug-only, bounded and content-free trace for exactly one approved start attempt. */
    private class AdmissionAttemptTrace(private val id: Long, private val startedAt: Long) {
        private data class TimedEvent(val atMs: Long, val line: String)
        private val events = ArrayList<TimedEvent>(12)
        private var wakeAttempted = false
        private var lastSnapshotSignature: String? = null
        private var repeatedSnapshots = 0
        private var finished = false

        fun phase(): String = if (wakeAttempted) "post_wake" else "pre_wake"

        fun snapshot(phase: String, value: AdmissionSnapshotSummary) {
            val signature = value.signature(phase)
            if (signature == lastSnapshotSignature) { repeatedSnapshots++; return }
            flushRepeat()
            lastSnapshotSignature = signature
            event("snapshot phase=$phase ${value.logFields()}")
        }

        fun ownerChanged(phase: String, value: CastOccupantIdentity?) {
            event("owner_changed phase=$phase owner=${admissionOccupantClass(value)}")
        }

        fun physicalOwnerRead(phase: String,
            before: LegacyDisplayOccupancy.PhysicalProjectionAdmission?,
            after: LegacyDisplayOccupancy.PhysicalProjectionAdmission?, beforeOemTask: Boolean,
            afterOemTask: Boolean, sameOemTask: Boolean) {
            event("physical_owner_read phase=$phase origin_before=${before?.disposition ?: "not_applicable"} " +
                "origin_after=${after?.disposition ?: "not_applicable"} placement_before=${before?.placement ?: "unknown"} " +
                "placement_after=${after?.placement ?: "unknown"} generation_match_before=${before?.generationMatches ?: false} " +
                "generation_match_after=${after?.generationMatches ?: false} before_oem_task=$beforeOemTask " +
                "after_oem_task=$afterOemTask same_oem_task=$sameOemTask transaction_pending_before=${before?.transactionPending ?: false} " +
                "transaction_pending_after=${after?.transactionPending ?: false}")
        }

        fun readFailed(phase: String, reason: String) {
            flushRepeat()
            event("snapshot_read_failed phase=$phase reason=$reason")
        }

        fun wakeCall() {
            flushRepeat()
            wakeAttempted = true
            event("wake_attempt")
        }

        fun wakeReturned(result: Boolean) {
            event("wake_returned result=$result")
        }

        fun wakeFailed(reason: String) {
            event("wake_call_failed reason=$reason")
        }

        fun finish(gate: String) {
            if (finished) return
            finished = true
            flushRepeat()
            events.add(TimedEvent(SystemClock.elapsedRealtime() - startedAt, "terminal gate=$gate"))
            events.forEach { Log.i("ControlledCastAdmission", "attempt=$id t=${it.atMs}ms ${it.line}") }
        }

        private fun flushRepeat() {
            if (repeatedSnapshots > 0) event("stable_snapshot_repeats=$repeatedSnapshots")
            repeatedSnapshots = 0
        }

        fun event(value: String) {
            val now = SystemClock.elapsedRealtime() - startedAt
            if (events.size < 22) events.add(TimedEvent(now, value))
            else if (events.size < 23 && events.lastOrNull()?.line != "trace_truncated")
                events.add(TimedEvent(now, "trace_truncated"))
        }
    }

    private fun occupantClass(value: CastOccupantIdentity?): String = when (value?.mode) {
        null -> "empty"
        CastMode.VIRTUAL -> "virtual"
        CastMode.DIRECT_PHYSICAL -> "physical_task"
    }

    private fun tri(value: Any?): String = when (value) {
        true -> "true"; false -> "false"; else -> "unknown"
    }

    private fun powerName(value: Int): String = when (value) {
        Display.STATE_ON -> "ON"; Display.STATE_OFF -> "OFF"; Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"; else -> "UNKNOWN"
    }

    private fun safeSource(value: String): String = when (value) {
        "activity_task", "activity_task_focused_stack", "activity_focused_stack" -> value
        "am_stack_list" -> value
        else -> "other"
    }

    private fun safeFailureReason(failure: Exception): String = when (failure.message) {
        "rear_occupancy_unknown", "rear_power_unverified", "rear_row_unknown", "rear_row_identity_unknown",
        "rear_visibility_unknown", "rear_component_unknown", "rear_occupant_changed",
        "rear_identity_changed", "task_service_unavailable" -> failure.message!!
        else -> "read_failed"
    }

    override fun canReplace(target: HostCastTarget, occupant: CastOccupantIdentity): Boolean {
        if (occupant.generation <= 0L || occupant.ownerGeneration <= 0L
            || target.rearDisplayId != occupant.rearDisplayId || target.rearIdentity != occupant.rearIdentity) return false
        if (LegacyDisplayOccupancy.physicalReplacementJournal(app) != null) return false
        if (occupant.mode == CastMode.DIRECT_PHYSICAL) {
            if (occupant.source != CastOccupantSource.PHYSICAL_PROJECTION_OWNER || occupant.taskId <= 0
                || occupant.owner == "unmanaged" || occupant.baseComponent.isBlank()) return false
            val saved = LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(app, occupant.rearDisplayId)
                ?: return false
            val expectedSession = "physical:${saved.taskId}:${saved.generation}"
            val current = runCatching { inspectOccupant(occupant.rearDisplayId, occupant.rearIdentity) }.getOrNull()
                ?: return false
            return saved.taskId == occupant.taskId && saved.packageName.isNotBlank()
                && saved.baseComponent == occupant.baseComponent && saved.identity == occupant.rearIdentity
                && saved.generation == occupant.generation && occupant.sessionId == expectedSession
                && current == occupant && current.ownerGeneration > 0L
                && top.zainzh.rearscreen.RearScreenKeeperService.physicalCastTask() ==
                    "${saved.packageName}:${saved.taskId}"
                && top.zainzh.rearscreen.RearScreenKeeperService.physicalCastOwnerGeneration() == saved.generation
        }
        if (occupant.mode != CastMode.VIRTUAL || occupant.hostTaskId <= 0 || occupant.inputSessionId.isBlank()) return false
        val current = runCatching { inspectOccupant(occupant.rearDisplayId, occupant.rearIdentity) }.getOrNull()
            ?: return false
        return current == occupant && target.rearDisplayId == occupant.rearDisplayId
    }

    override fun isExactControlledVirtualOwner(occupant: CastOccupantIdentity): Boolean {
        if (occupant.mode != CastMode.VIRTUAL || occupant.source != CastOccupantSource.VIRTUAL_OWNER ||
            occupant.owner == "unmanaged" || occupant.owner.isBlank() || occupant.generation <= 0L ||
            occupant.ownerGeneration <= 0L || occupant.taskId <= 0 || occupant.hostTaskId <= 0 ||
            occupant.inputSessionId.isBlank() || occupant.baseComponent.isBlank()) return false
        val current = runCatching { inspectOccupant(occupant.rearDisplayId, occupant.rearIdentity) }.getOrNull()
            ?: return false
        return current == occupant && current.owner != "unmanaged" && current.ownerGeneration > 0L
    }

    override fun start(operationId: String, owner: CastOwnerKey, generation: Long, target: HostCastTarget,
                       expectedOccupant: CastOccupantIdentity?, authorizationCurrent: () -> Boolean,
                       claimAction: () -> Boolean, callback: (BackendEvent) -> Unit) {
        val packageName = ComponentNameCompat.packageName(target.baseComponent)
            ?: throw IllegalStateException("target_component_invalid")
        val remote = taskService.current() ?: throw IllegalStateException("task_service_unavailable")
        val op = Operation(operationId, owner, generation, target, expectedOccupant,
            physical = target.mode == CastMode.DIRECT_PHYSICAL)
        op.authorizationCurrent = authorizationCurrent
        synchronized(operations) {
            if ((cancelledBeforeRegistration.remove(operationId) ?: 0L) > clock()) op.cancelled.set(true)
            if (operations.values.any { !it.cancelled.get() }) throw IllegalStateException("cast_backend_busy")
            operations[operationId] = op
        }
        val trace = if (BuildConfig.DEBUG)
            AdmissionAttemptTrace(admissionTraceSequence.incrementAndGet(), clock()) else null
        trace?.event("approved_start_enter mode=${target.mode.name.lowercase()}")
        // The coordinator calls start only after a user approved the exact preview ticket. Keep
        // all power-changing work here (never in prepare/cancel), and recheck every proof before
        // the first privileged move/create operation.
        val gate = try { ControlledCastStandbyPolicy.evaluateAfterApproval(
            approved = true,
            expectedOccupant = expectedOccupant,
            access = object : ControlledCastStandbyPolicy.Access {
                private var actionLease: top.zainzh.rearscreen.ControlledCastActionGate.Lease? = null
                override fun isAuthorized(): Boolean = !op.cancelled.get() && authorizationCurrent()
                override fun claimSideEffect(): Boolean = !op.cancelled.get() && claimAction()
                override fun beginActionWindow(): Boolean {
                    actionLease = top.zainzh.rearscreen.ControlledCastActionGate.reserve()
                    return actionLease?.isCurrent() == true
                }
                override fun endActionWindow() {
                    actionLease?.close()
                    actionLease = null
                }
                override fun rearIsCurrent(): Boolean = readRearIdentity(remote) ==
                    (target.rearDisplayId to target.rearIdentity)
                override fun targetIsCurrent(): Boolean = remote.isTaskOnDisplay(target.taskId, target.mainDisplayId)
                    && ControlledCastBackendPolicy.canonicalComponent(
                        remote.getTaskBaseComponent(target.taskId, target.mainDisplayId) ?: "") ==
                        ControlledCastBackendPolicy.canonicalComponent(target.baseComponent)
                    && (target.expectedTopComponent == null || taskService.isExplicitTargetCurrent(target))
                override fun inspectStartEvidence(): ControlledCastStartEvidence {
                    val phase = trace?.phase() ?: "untraced"
                    return try {
                        inspectStartEvidence(remote, target.rearDisplayId, target.rearIdentity, trace, phase)
                    } catch (failure: Exception) {
                        trace?.readFailed(phase, safeFailureReason(failure))
                        throw failure
                    }
                }
                override fun inspectPostWakeEvidence(expectedOemTaskId: Int?, expectedOemUserId: Int?,
                    expectedOemComponent: String?): ControlledCastStartEvidence {
                    val phase = trace?.phase() ?: "untraced"
                    return try {
                        inspectStartEvidence(remote, target.rearDisplayId, target.rearIdentity,
                            trace, phase, allowOemWakeTransition = true,
                            expectedPostWakeOemTaskId = expectedOemTaskId,
                            expectedPostWakeOemUserId = expectedOemUserId,
                            expectedPostWakeOemComponent = expectedOemComponent)
                    } catch (failure: Exception) {
                        trace?.readFailed(phase, safeFailureReason(failure))
                        throw failure
                    }
                }
                override fun wakeRearOnce(): ControlledCastWakeResult {
                    val lease = actionLease ?: return ControlledCastWakeResult.FAILED
                    val gate = object : top.zainzh.rearscreen.ISceneTaskActionGate.Stub() {
                        override fun isCurrent(): Boolean = lease.isCurrent
                            && !op.cancelled.get() && authorizationCurrent()
                        override fun isCurrentBeforeTargetMove(): Boolean = isCurrent()
                    }
                    trace?.wakeCall()
                    return try {
                        when (remote.wakeRearDisplayForControlledCast(target.rearDisplayId, target.rearIdentity, gate)) {
                            top.zainzh.rearscreen.ITaskService.CONTROLLED_CAST_WAKE_CONFIRMED -> {
                                trace?.wakeReturned(true); ControlledCastWakeResult.CONFIRMED
                            }
                            top.zainzh.rearscreen.ITaskService.CONTROLLED_CAST_WAKE_ALREADY_ON -> {
                                trace?.event("wake_returned already_on"); ControlledCastWakeResult.ALREADY_ON
                            }
                            else -> { trace?.wakeReturned(false); ControlledCastWakeResult.FAILED }
                        }
                    } catch (failure: Exception) {
                        trace?.wakeFailed(safeFailureReason(failure))
                        throw failure
                    }
                }
                override fun elapsedRealtime(): Long = clock()
                override fun sleep(ms: Long) = Thread.sleep(ms)
            }) } catch (failure: Exception) {
            trace?.finish("exception_${safeFailureReason(failure)}")
            throw failure
        }
        trace?.finish(gate.name)
        if (gate != ApprovedCastStartGate.READY) {
            operations.remove(operationId, op)
            android.util.Log.i("ControlledCast", "approved start gate rejected: ${gate.name}")
            val code = when (gate) {
                ApprovedCastStartGate.REAR_CHANGED -> CastFailureCode.TARGET_CHANGED
                ApprovedCastStartGate.TARGET_CHANGED -> CastFailureCode.TARGET_CHANGED
                ApprovedCastStartGate.OCCUPANCY_CHANGED -> CastFailureCode.OCCUPANCY_CHANGED
                ApprovedCastStartGate.OCCUPANCY_UNKNOWN, ApprovedCastStartGate.STANDBY_UNKNOWN -> CastFailureCode.OCCUPANCY_UNKNOWN
                ApprovedCastStartGate.WAKE_FAILED -> CastFailureCode.BACKEND_NOT_READY
                ApprovedCastStartGate.ACTION_BUSY -> CastFailureCode.BACKEND_BUSY
                ApprovedCastStartGate.CANCELLED -> CastFailureCode.ACTIVATION_INVALID
                ApprovedCastStartGate.READY -> error("unreachable")
            }
            callback(BackendEvent.Failed(CastFailure(code, recoverable = gate != ApprovedCastStartGate.WAKE_FAILED)))
            return
        }
        if (op.cancelled.get() || !authorizationCurrent()) {
            operations.remove(operationId, op)
            if (!op.cancelled.get()) callback(BackendEvent.Failed(CastFailure(CastFailureCode.OWNER_STALE, false)))
            return
        }
        if (target.mode == CastMode.DIRECT_PHYSICAL) {
            if (expectedOccupant != null && expectedOccupant.mode == CastMode.DIRECT_PHYSICAL) {
                // The occupied-display transaction is implemented by the same strict physical
                // authority; an unrecognized or stale owner never reaches this branch.
                if (!canReplace(target, expectedOccupant)) {
                    operations.remove(operationId, op)
                    callback(BackendEvent.Failed(CastFailure(CastFailureCode.OCCUPANCY_CHANGED, false)))
                    return
                }
                if (target.mode != CastMode.DIRECT_PHYSICAL || target.region != CastRegion.SAFE
                    || target.contentRotation != 0 || target.requestedDensityDpi != null) {
                    operations.remove(operationId, op)
                    callback(BackendEvent.Failed(CastFailure(CastFailureCode.BACKEND_NOT_READY, false)))
                    return
                }
                val oldOwner = LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(app, target.rearDisplayId)
                if (oldOwner == null || oldOwner.taskId != expectedOccupant.taskId
                    || oldOwner.packageName.isBlank() || oldOwner.baseComponent != expectedOccupant.baseComponent
                    || oldOwner.generation != expectedOccupant.generation
                    || oldOwner.identity != target.rearIdentity) {
                    operations.remove(operationId, op)
                    callback(BackendEvent.Failed(CastFailure(CastFailureCode.OCCUPANCY_CHANGED, false)))
                    return
                }
                op.backendActionInFlight = true
                val result = try { top.zainzh.rearscreen.ControlledPhysicalCastOperations.replace(app, remote,
                    oldOwner.taskId, oldOwner.packageName, oldOwner.baseComponent, oldOwner.generation,
                    target.taskId, packageName, target.baseComponent, target.rearDisplayId, target.rearIdentity,
                    op.coordinatorGeneration, owner.toString(), operationId,
                    { !op.cancelled.get() && authorizationCurrent() },
                    { !op.cancelled.get() && claimAction() },
                    { !op.cancelled.get() && taskService.isExplicitTargetCurrent(target) }) }
                catch (_: Exception) {
                    callback(BackendEvent.Failed(CastFailure(CastFailureCode.OWNER_UNVERIFIED, true)))
                    operations.remove(operationId, op)
                    return
                } finally { op.backendActionInFlight = false }
                if (!result.committed) {
                    val currentOwner = LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(app, target.rearDisplayId)
                    if (currentOwner != null && currentOwner.generation == expectedOccupant.generation
                        && currentOwner.taskId == expectedOccupant.taskId) {
                        callback(BackendEvent.Failed(CastFailure(if (result.indeterminate)
                            CastFailureCode.OWNER_UNVERIFIED else CastFailureCode.BACKEND_FAILED, result.indeterminate)))
                    } else if (result.indeterminate) {
                        callback(BackendEvent.Failed(CastFailure(CastFailureCode.OWNER_UNVERIFIED, true)))
                    } else callback(BackendEvent.Failed(CastFailure(CastFailureCode.BACKEND_FAILED, true)))
                    operations.remove(operationId, op)
                    return
                }
                val previousBackendId = expectedOccupant.sessionId
                val previous = ownedSessions[previousBackendId]
                val newBackendId = result.sessionId!!
                if (previous != null) {
                    pendingReplacementOldSessions[newBackendId] = previous
                }
                retainPhysicalOwner(op, newBackendId, result.ownerGeneration)
                pendingPhysicalReplacements[newBackendId] = true
                callback(BackendEvent.ReplacementReady(previousBackendId, expectedOccupant.ownerGeneration,
                    newBackendId, target, true, true, CastPresentationKind.SYSTEM_MANAGED, null))
                operations.remove(operationId, op)
                return
            }
            if (expectedOccupant != null || target.region != CastRegion.SAFE || target.contentRotation != 0
                || target.requestedDensityDpi != null) {
                operations.remove(operationId, op)
                throw IllegalStateException("physical_mode_requires_idle_safe_unrotated_target")
            }
            op.backendActionInFlight = true
            val result = try { top.zainzh.rearscreen.ControlledPhysicalCastOperations.start(app, remote,
                target.taskId, packageName, target.baseComponent, target.rearDisplayId, target.rearIdentity,
                { !op.cancelled.get() && authorizationCurrent() },
                { !op.cancelled.get() && claimAction() })
            } catch (failure: Exception) {
                operations.remove(operationId, op)
                throw failure
            } finally { op.backendActionInFlight = false }
            if (result.sessionId != null && result.ownerGeneration > 0L)
                retainPhysicalOwner(op, result.sessionId, result.ownerGeneration)
            if (op.cancelled.get() || !authorizationCurrent()) {
                val returned = if (result.sessionId != null && result.ownerGeneration > 0L)
                    top.zainzh.rearscreen.ControlledPhysicalCastOperations.stop(app, remote,
                    result.sessionId, target.taskId, packageName, target.baseComponent, target.rearDisplayId,
                    target.rearIdentity, result.ownerGeneration) else null
                if (returned?.stopped == true) releasePhysicalOwner(op, result.sessionId!!)
                else if (result.ownerGeneration > 0L)
                    top.zainzh.rearscreen.RearScreenKeeperService.notifyControlledPhysicalRecovery(
                        app, target.taskId, result.ownerGeneration)
                if (returned?.stopped == true || result.ownerGeneration <= 0L) operations.remove(operationId, op)
                return
            }
            if (!result.committed) {
                var cleanupStopped = false
                if (result.sessionId != null && result.ownerGeneration > 0L) {
                    val cleanup = top.zainzh.rearscreen.ControlledPhysicalCastOperations.stop(app, remote,
                        result.sessionId, target.taskId, packageName, target.baseComponent, target.rearDisplayId,
                        target.rearIdentity, result.ownerGeneration)
                    cleanupStopped = cleanup.stopped
                    if (cleanupStopped) releasePhysicalOwner(op, result.sessionId)
                }
                val unresolvedOwner = !cleanupStopped && (result.indeterminate || result.ownerGeneration > 0L)
                if (unresolvedOwner && result.ownerGeneration > 0L)
                    top.zainzh.rearscreen.RearScreenKeeperService.notifyControlledPhysicalRecovery(
                        app, target.taskId, result.ownerGeneration)
                callback(BackendEvent.Failed(CastFailure(
                    if (unresolvedOwner) CastFailureCode.OWNER_UNVERIFIED else CastFailureCode.BACKEND_FAILED,
                    unresolvedOwner)))
                if (!unresolvedOwner) operations.remove(operationId, op)
                return
            }
            if (!awaitPhysicalVisible(remote, target, packageName)) {
                val returned = top.zainzh.rearscreen.ControlledPhysicalCastOperations.stop(app, remote,
                    result.sessionId, target.taskId, packageName, target.baseComponent, target.rearDisplayId,
                    target.rearIdentity, result.ownerGeneration)
                callback(BackendEvent.Failed(CastFailure(
                    if (returned.stopped) CastFailureCode.BACKEND_NOT_READY else CastFailureCode.OWNER_UNVERIFIED,
                    true)))
                if (returned.stopped) releasePhysicalOwner(op, result.sessionId)
                else top.zainzh.rearscreen.RearScreenKeeperService.notifyControlledPhysicalRecovery(
                    app, target.taskId, result.ownerGeneration)
                return
            }
            op.apply {
                backendSessionId = result.sessionId
                backendGeneration = result.ownerGeneration
            }
            ownedSessions[result.sessionId] = op
            operationSessions[operationId] = result.sessionId
            sessionOwners[result.sessionId] = owner to generation
            callback(BackendEvent.Ready(result.sessionId, target, true, true,
                CastPresentationKind.SYSTEM_MANAGED, null))
            operations.remove(operationId, op)
            return
        }
        if (target.mode != CastMode.VIRTUAL) {
            operations.remove(operationId, op)
            throw IllegalStateException("cast_mode_unsupported")
        }
        val density = target.requestedDensityDpi ?: 200
        if (op.cancelled.get() || !authorizationCurrent()) {
            operations.remove(operationId, op)
            if (!op.cancelled.get()) callback(BackendEvent.Failed(CastFailure(CastFailureCode.OWNER_STALE, false)))
            return
        }
        op.backendActionInFlight = true
        val ownerGate = object : top.zainzh.rearscreen.ISceneTaskActionGate.Stub() {
            override fun isCurrent(): Boolean = !op.cancelled.get() && authorizationCurrent()
            override fun isCurrentBeforeTargetMove(): Boolean =
                isCurrent() && taskService.isExplicitTargetCurrentBeforeVirtualMove(target) {
                    VirtualRearHostActivity.controlledPresenterTaskForMove(operationId, generation,
                        owner.toString(), target.taskId, target.rearDisplayId, target.rearIdentity, remote)
                } && isCurrent()
        }
        if (expectedOccupant?.mode == CastMode.DIRECT_PHYSICAL) {
            if (!canReplace(target, expectedOccupant)) {
                operations.remove(operationId, op)
                callback(BackendEvent.Failed(CastFailure(CastFailureCode.OCCUPANCY_CHANGED, false)))
                return
            }
            val oldOwner = LegacyDisplayOccupancy.controlledPhysicalProjectionForRecovery(app, target.rearDisplayId)
            if (oldOwner == null || oldOwner.taskId != expectedOccupant.taskId
                || oldOwner.generation != expectedOccupant.generation || oldOwner.baseComponent != expectedOccupant.baseComponent) {
                operations.remove(operationId, op)
                callback(BackendEvent.Failed(CastFailure(CastFailureCode.OCCUPANCY_CHANGED, false)))
                return
            }
            val virtualBaseline = JSONObject(remote.getRearVirtualCastStatus())
            if (virtualBaseline.optString("state") != "stopped") {
                operations.remove(operationId, op)
                callback(BackendEvent.Failed(CastFailure(CastFailureCode.OWNER_UNVERIFIED, true)))
                return
            }
            val exactVirtualBaseline = JSONObject().put("state", "stopped")
            listOf("id", "generation", "coordinatorOperationId", "ownerGeneration", "ownerIdentity",
                "task", "package", "baseComponent", "rearDisplayId", "rearIdentity").forEach { key ->
                if (virtualBaseline.has(key)) exactVirtualBaseline.put(key, virtualBaseline.get(key))
            }
            val journal = LegacyDisplayOccupancy.beginPhysicalReplacement(app, operationId,
                oldOwner.taskId, oldOwner.packageName, oldOwner.baseComponent, oldOwner.displayId,
                oldOwner.identity, oldOwner.generation,
                LegacyDisplayOccupancy.physicalProjectionLauncherDisabled(app, oldOwner.taskId, oldOwner.displayId),
                target.taskId, packageName, target.baseComponent, true,
                op.coordinatorGeneration, owner.toString(), exactVirtualBaseline.toString())
            if (journal == null) {
                operations.remove(operationId, op)
                callback(BackendEvent.Failed(CastFailure(CastFailureCode.OWNER_UNVERIFIED, true)))
                return
            }
            op.physicalReplacementToken = journal.token
        }
        val started = try { VirtualRearHostActivity.startControlledCast(app, target.taskId,
            target.requestedComponent, packageName, target.baseComponent, target.rearDisplayId,
            target.rearIdentity, density, target.requestedDensityDpi != null,
            target.region == CastRegion.FULL, target.contentRotation,
            expectedOccupant, generation, owner.toString(), operationId, ownerGate, remote)
        } catch (failure: Exception) {
            op.physicalReplacementToken?.let { token ->
                val cleaned = top.zainzh.rearscreen.ControlledPhysicalCastOperations.discardUnmovedPhysicalToVirtual(
                    app, remote, token, op.id)
                callback(BackendEvent.Failed(CastFailure(
                    if (cleaned) CastFailureCode.BACKEND_FAILED else CastFailureCode.OWNER_UNVERIFIED, !cleaned)))
            }
            operations.remove(operationId, op)
            throw failure
        } finally { op.backendActionInFlight = false }
        if (!started) {
            op.physicalReplacementToken?.let { token ->
                val cleaned = top.zainzh.rearscreen.ControlledPhysicalCastOperations.discardUnmovedPhysicalToVirtual(
                    app, remote, token, op.id)
                callback(BackendEvent.Failed(CastFailure(
                    if (cleaned) CastFailureCode.BACKEND_FAILED else CastFailureCode.OWNER_UNVERIFIED, !cleaned)))
            }
            operations.remove(operationId, op)
            if (op.physicalReplacementToken == null) throw IllegalStateException("host_start_rejected")
            return
        }
        if (op.cancelled.get() || !authorizationCurrent()) { abort(operationId); return }
        try { poller.execute { awaitReady(op, callback) } }
        catch (busy: java.util.concurrent.RejectedExecutionException) {
            abort(operationId)
            throw IllegalStateException("cast_backend_busy", busy)
        }
    }

    private fun retainPhysicalOwner(op: Operation, sessionId: String, generation: Long) {
        op.backendSessionId = sessionId
        op.backendGeneration = generation
        ownedSessions[sessionId] = op
        operationSessions[op.id] = sessionId
        sessionOwners[sessionId] = op.owner to op.coordinatorGeneration
    }

    override fun confirmReplacement(backendSessionId: String): Boolean {
        if (!pendingPhysicalReplacements.containsKey(backendSessionId)) return true
        val journal = LegacyDisplayOccupancy.physicalReplacementJournal(app)
        if (journal?.newSessionId == backendSessionId && journal.newVirtual) {
            // Keep the accepted virtual replacement durable until B's exact terminal cleanup.
            return journal.phase == "ACCEPTED_VIRTUAL"
        }
        val confirmed = top.zainzh.rearscreen.ControlledPhysicalCastOperations.confirmReplacement(app, backendSessionId)
        if (confirmed) {
            pendingPhysicalReplacements.remove(backendSessionId)
            val old = pendingReplacementOldSessions.remove(backendSessionId)
            if (old != null) {
                val oldId = old.backendSessionId
                if (oldId != null) {
                    ownedSessions.remove(oldId, old)
                    sessionOwners.remove(oldId, old.owner to old.coordinatorGeneration)
                    operationSessions.remove(old.id, oldId)
                }
                operations.remove(old.id, old)
            }
        }
        return confirmed
    }

    override fun markReplacementAccepted(backendSessionId: String, coordinatorGeneration: Long,
        ownerIdentity: String): Boolean {
        if (!pendingPhysicalReplacements.containsKey(backendSessionId)) return true
        val journal = LegacyDisplayOccupancy.physicalReplacementJournal(app) ?: return false
        if (journal.newSessionId != backendSessionId) return false
        return LegacyDisplayOccupancy.markPhysicalReplacementAccepted(app, backendSessionId,
            coordinatorGeneration, ownerIdentity)
    }

    override fun rollbackReplacement(backendSessionId: String): Boolean {
        if (!pendingPhysicalReplacements.containsKey(backendSessionId)) {
            abort(backendSessionId)
            return false
        }
        val remote = taskService.current() ?: return false
        val restored = top.zainzh.rearscreen.ControlledPhysicalCastOperations.rollbackReplacement(
            app, remote, backendSessionId)
        if (restored) {
            pendingPhysicalReplacements.remove(backendSessionId)
            pendingReplacementOldSessions.remove(backendSessionId)
            val op = ownedSessions.remove(backendSessionId)
            sessionOwners.remove(backendSessionId)
            if (op != null) {
                operationSessions.remove(op.id, backendSessionId)
                operations.remove(op.id, op)
            }
        }
        return restored
    }

    private fun releasePhysicalOwner(op: Operation, sessionId: String) {
        ownedSessions.remove(sessionId, op)
        sessionOwners.remove(sessionId, op.owner to op.coordinatorGeneration)
        operationSessions.remove(op.id, sessionId)
        operations.remove(op.id, op)
    }

    private fun awaitReady(op: Operation, callback: (BackendEvent) -> Unit) {
        val deadline = clock() + READY_TIMEOUT_MS
        try {
            while (!op.cancelled.get() && clock() < deadline) {
                val remote = taskService.current() ?: throw IllegalStateException("task_service_disconnected")
                val raw = remote.getRearVirtualCastStatus()
                val status = JSONObject(raw)
                val state = status.optString("state")
                if (state == "running" && exactTargetStatus(op, status)) {
                    val input = status.optJSONObject("input")
                    val outputReady = status.optBoolean("outputAvailable")
                    val inputReady = input?.optString("state") == "running" && input.optBoolean("outputAvailable")
                    if (outputReady && inputReady
                        && VirtualRearHostActivity.controlledOutputHasFrame(status.optString("id"), 1_500L)) {
                        val id = status.optString("id")
                        val generation = status.optLong("generation", -1L)
                        val viewport = appliedViewport(op.target, status, id)
                        if (id.isNotBlank() && generation > 0L && viewport != null) {
                            val replacementToken = op.physicalReplacementToken
                            if (replacementToken != null) {
                                op.backendActionInFlight = true
                                val remoteReady = try { top.zainzh.rearscreen.ControlledPhysicalCastOperations
                                    .completePhysicalToVirtualReplacement(app, remote, replacementToken, id,
                                        op.target.taskId, ComponentNameCompat.packageName(op.target.baseComponent).orEmpty(),
                                        op.target.baseComponent, op.coordinatorGeneration, op.owner.toString(), generation,
                                        { !op.cancelled.get()
                                            && runCatching { op.authorizationCurrent?.invoke() == true }.getOrDefault(false) }) }
                                    finally { op.backendActionInFlight = false }
                                if (!remoteReady) {
                                    val journal = LegacyDisplayOccupancy.physicalReplacementJournal(app)
                                    val restored = journal != null && top.zainzh.rearscreen.ControlledPhysicalCastOperations
                                        .rollbackPhysicalToVirtual(app, remote, journal, id)
                                    if (restored) op.physicalReplacementToken = null
                                    else top.zainzh.rearscreen.RearScreenKeeperService.notifyControlledPhysicalRecovery(
                                        app, op.previous?.taskId ?: -1, op.previous?.generation ?: -1L)
                                    callback(BackendEvent.Failed(CastFailure(
                                        if (restored) CastFailureCode.BACKEND_NOT_READY else CastFailureCode.OWNER_UNVERIFIED,
                                        !restored)))
                                    return
                                }
                                val previousId = op.previous?.sessionId ?: run {
                                    callback(BackendEvent.Failed(CastFailure(CastFailureCode.OWNER_UNVERIFIED, true)))
                                    return
                                }
                                val previous = ownedSessions[previousId]
                                op.backendSessionId = id; op.backendGeneration = generation
                                sessionOwners[id] = op.owner to op.coordinatorGeneration
                                ownedSessions[id] = op
                                operationSessions[op.id] = id
                                operations.remove(op.id)
                                pendingPhysicalReplacements[id] = true
                                if (previous != null) pendingReplacementOldSessions[id] = previous
                                callback(BackendEvent.ReplacementReady(previousId, op.previous.ownerGeneration,
                                    id, op.target, true, true, CastPresentationKind.MANAGED_VIEWPORT, viewport))
                                return
                            }
                            op.backendSessionId = id; op.backendGeneration = generation
                            sessionOwners[id] = op.owner to op.coordinatorGeneration
                            ownedSessions[id] = op
                            operationSessions[op.id] = id
                            operations.remove(op.id)
                            callback(BackendEvent.Ready(id, op.target, true, true,
                                CastPresentationKind.MANAGED_VIEWPORT, viewport))
                            return
                        }
                    }
                } else if (state == "stopped" && ControlledCastBackendPolicy.isTerminalForThisOperation(
                        op.id, op.coordinatorGeneration, op.owner.toString(), status)) {
                    throw IllegalStateException(status.optString("reason", "cast_stopped"))
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            if (!op.cancelled.get()) {
                val previousStillActive = op.previous?.let { expected ->
                    val current = runCatching { inspectOccupant(expected.rearDisplayId, expected.rearIdentity) }.getOrNull()
                    current == expected
                } ?: false
                val replacementToken = op.physicalReplacementToken
                if (replacementToken != null) {
                    val cleanupRemote = taskService.current()
                    val restored = cleanupRemote != null && top.zainzh.rearscreen.ControlledPhysicalCastOperations
                        .discardUnmovedPhysicalToVirtual(app, cleanupRemote, replacementToken, op.id)
                    if (restored) op.physicalReplacementToken = null
                    callback(BackendEvent.Failed(CastFailure(
                        if (restored) CastFailureCode.BACKEND_NOT_READY else CastFailureCode.OWNER_UNVERIFIED,
                        !restored)))
                    return
                }
                VirtualRearHostActivity.cancelControlledCast(app, op.id)
                cleanupExactStartedSession(op)
                val code = if (previousStillActive) CastFailureCode.BACKEND_NOT_READY else CastFailureCode.OWNER_UNVERIFIED
                callback(BackendEvent.Failed(CastFailure(code, previousStillActive)))
            } else {
                recoverCancelledPhysicalReplacement(op)
                if (op.physicalReplacementToken == null) {
                    VirtualRearHostActivity.cancelControlledCast(app, op.id)
                    cleanupExactStartedSession(op)
                }
            }
        } catch (_: Exception) {
            if (!op.cancelled.get()) {
                val previousStillActive = op.previous?.let { expected ->
                    runCatching { inspectOccupant(expected.rearDisplayId, expected.rearIdentity) == expected }.getOrDefault(false)
                } ?: false
                val replacementToken = op.physicalReplacementToken
                if (replacementToken != null) {
                    val cleanupRemote = taskService.current()
                    val restored = cleanupRemote != null && top.zainzh.rearscreen.ControlledPhysicalCastOperations
                        .discardUnmovedPhysicalToVirtual(app, cleanupRemote, replacementToken, op.id)
                    if (restored) op.physicalReplacementToken = null
                    callback(BackendEvent.Failed(CastFailure(
                        if (restored) CastFailureCode.BACKEND_FAILED else CastFailureCode.OWNER_UNVERIFIED,
                        !restored)))
                    return
                }
                VirtualRearHostActivity.cancelControlledCast(app, op.id)
                cleanupExactStartedSession(op)
                callback(BackendEvent.Failed(CastFailure(
                    if (previousStillActive) CastFailureCode.BACKEND_FAILED else CastFailureCode.OWNER_UNVERIFIED,
                    previousStillActive)))
            } else {
                recoverCancelledPhysicalReplacement(op)
                if (op.physicalReplacementToken == null) {
                    VirtualRearHostActivity.cancelControlledCast(app, op.id)
                    cleanupExactStartedSession(op)
                }
            }
        } finally {
            if (op.backendSessionId == null) operations.remove(op.id)
        }
    }

    private fun recoverCancelledPhysicalReplacement(op: Operation): Boolean {
        val token = op.physicalReplacementToken ?: return true
        val remote = taskService.current() ?: return false
        val journal = LegacyDisplayOccupancy.physicalReplacementJournal(app) ?: return false
        if (journal.token != token) return false
        val restored = top.zainzh.rearscreen.ControlledPhysicalCastOperations
            .rollbackPhysicalToVirtual(app, remote, journal, journal.newSessionId)
        if (restored) op.physicalReplacementToken = null
        else top.zainzh.rearscreen.RearScreenKeeperService.notifyControlledPhysicalRecovery(
            app, journal.oldTask, journal.oldGeneration)
        return restored
    }

    override fun abort(operationId: String) {
        val op = operations[operationId] ?: run {
            val sessionId = operationSessions[operationId]
            if (sessionId != null) ownedSessions[sessionId] else ownedSessions[operationId]
        } ?: run {
            val now = clock()
            cancelledBeforeRegistration.entries.removeIf { it.value <= now }
            if (cancelledBeforeRegistration.size < 128) cancelledBeforeRegistration[operationId] = now + CANCELLED_OPERATION_TTL_MS
            return
        }
        op.cancelled.set(true)
        if (op.physical) {
            val session = op.backendSessionId ?: return
            val remote = taskService.current() ?: return
            val pkg = ComponentNameCompat.packageName(op.target.baseComponent) ?: return
            val result = top.zainzh.rearscreen.ControlledPhysicalCastOperations.stop(app, remote, session,
                op.target.taskId, pkg, op.target.baseComponent, op.target.rearDisplayId,
                op.target.rearIdentity, op.backendGeneration)
            if (result.stopped) {
                ownedSessions.remove(session, op)
                sessionOwners.remove(session, op.owner to op.coordinatorGeneration)
                operationSessions.remove(op.id, session)
            }
            operations.remove(operationId, op)
            return
        }
        if (!op.physicalReplacementToken.isNullOrBlank()) {
            // The durable rollback CAS arbitrates against Coordinator ACCEPT before any host
            // cancellation or backend stop. A losing cancellation has no remote side effects.
            recoverCancelledPhysicalReplacement(op)
            return
        }
        VirtualRearHostActivity.cancelControlledCast(app, op.id)
        val session = op.backendSessionId
        if (session == null && op.backendActionInFlight) return
        val remote = taskService.current() ?: return
        val status = runCatching { JSONObject(remote.getRearVirtualCastStatus()) }.getOrNull()
        if (status == null) { recoverCancelledPhysicalReplacement(op); return }
        val actualId = status.optString("id")
        // If B crossed commit just before cancellation, revoke exactly B by its verified backend
        // identity. Never stop A or an unrelated later session from an operation-ID callback.
        if (actualId.isNotBlank() && (session == null || actualId == session)
            // Cancellation must also reclaim a partially started B whose output/input is not yet
            // ready. exactTargetStatus is deliberately stricter for publication, not cleanup.
            && exactOperationStatus(op, status)
            && status.optString("coordinatorOperationId") == op.id
            && status.optLong("ownerGeneration", -1L) == op.coordinatorGeneration
            && status.optString("ownerIdentity") == op.owner.toString()) {
            runCatching { remote.stopRearVirtualCast(actualId) }
            if (session != null) {
                ownedSessions.remove(session, op)
                operationSessions.remove(op.id, session)
                sessionOwners.remove(session, op.owner to op.coordinatorGeneration)
            }
        }
        if (session == null && (actualId == op.previous?.sessionId || !exactOperationStatus(op, status)))
            operations.remove(operationId, op)
        recoverCancelledPhysicalReplacement(op)
    }

    override fun cancelPendingReplacement(incumbentBackendSessionId: String): Boolean {
        val pending = synchronized(operations) {
            operations.values.firstOrNull { op ->
                op.previous?.sessionId == incumbentBackendSessionId && !op.cancelled.get()
            }
        } ?: return false
        pending.cancelled.set(true)
        if (!pending.physicalReplacementToken.isNullOrBlank()) recoverCancelledPhysicalReplacement(pending)
        else VirtualRearHostActivity.cancelControlledCast(app, pending.id)
        return true
    }

    private fun cleanupExactStartedSession(op: Operation) {
        val remote = taskService.current() ?: return
        val status = runCatching { JSONObject(remote.getRearVirtualCastStatus()) }.getOrNull() ?: return
        if (exactOperationStatus(op, status)) {
            val id = status.optString("id")
            if (id.isNotBlank() && id != op.previous?.sessionId)
                runCatching { remote.stopRearVirtualCast(id) }
        }
    }

    override fun query(backendSessionId: String, generation: Long): BackendRuntimeSnapshot? {
        val op = ownedSessions[backendSessionId] ?: return null
        if (op.coordinatorGeneration != generation) return null
        val remote = taskService.current() ?: return null
        if (op.physical) {
            val pkg = ComponentNameCompat.packageName(op.target.baseComponent) ?: return null
            if (LegacyDisplayOccupancy.physicalProjectionGeneration(app, op.target.taskId,
                    op.target.rearDisplayId) != op.backendGeneration) return null
            val exact = top.zainzh.rearscreen.ControlledPhysicalCastOperations.isExactVisibleTask(remote,
                op.target.taskId, pkg, op.target.baseComponent, op.target.rearDisplayId, op.target.rearIdentity)
            return BackendRuntimeSnapshot(if (exact) CastSessionState.READY else CastSessionState.INDETERMINATE,
                op.target, CastPresentationKind.SYSTEM_MANAGED, null,
                if (exact) null else CastFailure(CastFailureCode.OWNER_UNVERIFIED, false))
        }
        val status = JSONObject(remote.getRearVirtualCastStatus())
        if (status.optString("id") != backendSessionId || !exactTargetStatus(op, status)) return null
        val state = if (status.optString("state") == "running") CastSessionState.READY
            else if (status.optString("state") == "stopped") CastSessionState.CLOSED else CastSessionState.INDETERMINATE
        if (status.optLong("generation", -1L) != op.backendGeneration) return null
        return BackendRuntimeSnapshot(state, op.target, CastPresentationKind.MANAGED_VIEWPORT,
            appliedViewport(op.target, status, backendSessionId), if (state == CastSessionState.INDETERMINATE)
                CastFailure(CastFailureCode.OWNER_UNVERIFIED, false) else null)
    }

    override fun close(backendSessionId: String, generation: Long): BackendCloseResult {
        val op = ownedSessions[backendSessionId] ?: return BackendCloseResult.Rejected
        if (op.coordinatorGeneration != generation) return BackendCloseResult.Rejected
        if (op.physical) {
            val remote = taskService.current() ?: return BackendCloseResult.Indeterminate
            val pkg = ComponentNameCompat.packageName(op.target.baseComponent) ?: return BackendCloseResult.Rejected
            val result = top.zainzh.rearscreen.ControlledPhysicalCastOperations.stop(app, remote,
                backendSessionId, op.target.taskId, pkg, op.target.baseComponent, op.target.rearDisplayId,
                op.target.rearIdentity, op.backendGeneration)
            if (result.stopped) {
                releasePhysicalOwner(op, backendSessionId)
                return BackendCloseResult.Closed
            }
            return if (result.result == "stale_owner") BackendCloseResult.Rejected else BackendCloseResult.Indeterminate
        }
        var acceptedVirtualRetry = false
        var acceptedVirtualRetryJournal: LegacyDisplayOccupancy.PhysicalReplacementJournal? = null
        if (op.previous != null) {
            val journal = LegacyDisplayOccupancy.physicalReplacementJournal(app)
            val acceptedRetry = LegacyDisplayOccupancy.acceptedVirtualCloseRetryMatches(journal,
                backendSessionId, op.backendGeneration, generation, op.owner.toString(),
                op.target.taskId, op.target.baseComponent, op.target.rearDisplayId, op.target.rearIdentity)
            acceptedVirtualRetry = acceptedRetry
            if (acceptedRetry) acceptedVirtualRetryJournal = journal
            if (!acceptedRetry) {
                val current = runCatching { inspectOccupant(op.target.rearDisplayId, op.target.rearIdentity) }.getOrNull()
                if (current?.sessionId != backendSessionId || current.ownerGeneration != generation)
                    return BackendCloseResult.Rejected
            }
        }
        val remote = taskService.current() ?: return BackendCloseResult.Indeterminate
        if (acceptedVirtualRetry) {
            val deadline = clock() + CLOSE_TIMEOUT_MS
            while (clock() < deadline) {
                val expectedJournal = acceptedVirtualRetryJournal
                    ?: return BackendCloseResult.Indeterminate
                val status = runCatching { JSONObject(remote.getRearVirtualCastStatus()) }.getOrNull()
                    ?: return BackendCloseResult.Indeterminate
                if (status.optString("id") != backendSessionId) return BackendCloseResult.Rejected
                if (top.zainzh.rearscreen.ControlledPhysicalCastOperations
                        .finalizeAcceptedVirtualReplacement(app, remote, expectedJournal, status)) {
                    ownedSessions.remove(backendSessionId, op)
                    sessionOwners.remove(backendSessionId, op.owner to op.coordinatorGeneration)
                    operationSessions.remove(op.id, backendSessionId)
                    return BackendCloseResult.Closed
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            return BackendCloseResult.Indeterminate
        }
        try { remote.stopRearVirtualCast(backendSessionId) } catch (_: Exception) { /* reconcile below */ }
        val deadline = clock() + CLOSE_TIMEOUT_MS
        while (clock() < deadline) {
            val status = runCatching { JSONObject(remote.getRearVirtualCastStatus()) }.getOrNull()
                ?: return BackendCloseResult.Indeterminate
            if (status.optString("id") == backendSessionId && status.optString("state") == "stopped") {
                val replacement = LegacyDisplayOccupancy.physicalReplacementJournal(app)
                if (replacement?.newSessionId == backendSessionId && replacement.newVirtual) {
                    if (!top.zainzh.rearscreen.ControlledPhysicalCastOperations
                        .finalizeAcceptedVirtualReplacement(app, remote, replacement, status))
                        return BackendCloseResult.Indeterminate
                }
                ownedSessions.remove(backendSessionId, op)
                sessionOwners.remove(backendSessionId, op.owner to op.coordinatorGeneration)
                operationSessions.remove(op.id, backendSessionId)
                return BackendCloseResult.Closed
            }
            if (status.optString("id") != backendSessionId && status.optString("state") == "running")
                return BackendCloseResult.Rejected
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return BackendCloseResult.Indeterminate
    }

    private fun exactOperationStatus(op: Operation, status: JSONObject): Boolean {
        val remote = taskService.current() ?: return false
        val target = op.target
        val host = status.optInt("host", -1)
        val task = status.optInt("task", -1)
        val display = status.optInt("display", -1)
        val hostComponent = if (host > 0) remote.getTaskBaseComponent(host, target.rearDisplayId) else null
        return status.optString("id").isNotBlank()
            && ControlledCastBackendPolicy.operationMatches(op.id, op.coordinatorGeneration,
                op.owner.toString(), status.optString("id"), status)
            && task == target.taskId && status.optString("baseComponent")?.let {
                ControlledCastBackendPolicy.canonicalComponent(it) == ControlledCastBackendPolicy.canonicalComponent(target.baseComponent)
            } == true
            && display > 0 && display != target.rearDisplayId && remote.isTaskOnDisplay(task, display)
            && host > 0 && remote.isTaskOnDisplay(host, target.rearDisplayId)
            && ControlledCastBackendPolicy.canonicalComponent(hostComponent ?: "") ==
                ControlledCastBackendPolicy.canonicalComponent("${app.packageName}/${VirtualRearHostActivity::class.java.name}")
            && status.optInt("rearDisplayId", -1) == target.rearDisplayId
            && status.optString("rearIdentity") == target.rearIdentity
            && readRearIdentity(remote) == (target.rearDisplayId to target.rearIdentity)
    }

    private fun exactTargetStatus(op: Operation, status: JSONObject): Boolean {
        if (!exactOperationStatus(op, status) || status.optString("state") != "running"
            || !status.optBoolean("outputAvailable")) return false
        val input = status.optJSONObject("input") ?: return false
        return input.optString("state") == "running" && input.optBoolean("outputAvailable")
            && input.optString("id").isNotBlank()
    }

    private fun awaitPhysicalVisible(remote: top.zainzh.rearscreen.ITaskService,
                                     target: HostCastTarget, packageName: String): Boolean {
        val deadline = clock() + PHYSICAL_VISIBLE_TIMEOUT_MS
        while (clock() < deadline) {
            if (readRearIdentity(remote) == (target.rearDisplayId to target.rearIdentity)
                && top.zainzh.rearscreen.ControlledPhysicalCastOperations.isExactVisibleTask(
                    remote, target.taskId, packageName, target.baseComponent, target.rearDisplayId,
                    target.rearIdentity)) return true
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
        return false
    }

    private fun appliedViewport(target: HostCastTarget, status: JSONObject, sessionId: String): AppliedCastViewport? = try {
        val box = status.getJSONObject("viewport")
        val width = box.getInt("width"); val height = box.getInt("height")
        val bw = box.getInt("bufferWidth"); val bh = box.getInt("bufferHeight")
        val left = box.getInt("left"); val top = box.getInt("top")
        if (width <= 0 || height <= 0 || bw <= 0 || bh <= 0) return null
        val window = VirtualRearHostActivity.controlledHostWindowGeometry(sessionId) ?: return null
        val appliedRotation = status.getInt("contentRotation")
        val appliedRegion = if (status.getBoolean("fullViewport")) CastRegion.FULL else CastRegion.SAFE
        if (window[2] <= 0 || window[3] <= 0) return null
        val sx = bw.toFloat() / width
        val sy = bh.toFloat() / height
        val originX = window[0]; val originY = window[1]
        val dx = (originX - left) * sx
        val dy = (originY - top) * sy
        val transform = when (appliedRotation) {
            0 -> floatArrayOf(sx, 0f, 0f, sy, dx, dy)
            1 -> floatArrayOf(0f, sy, -sx, 0f, dy, bw - 1f - dx)
            2 -> floatArrayOf(-sx, 0f, 0f, -sy, bw - 1f - dx, bh - 1f - dy)
            3 -> floatArrayOf(0f, -sy, sx, 0f, bh - 1f - dy, dx)
            else -> return null
        }
        val a = transform[0]; val b = transform[1]; val c = transform[2]
        val d = transform[3]; val tx = transform[4]; val ty = transform[5]
        AppliedCastViewport(status.optLong("viewportRevision", -1L), target.rearDisplayId,
            appliedRegion,
            ControlledCastBackendPolicy.appliedRenderDensity(status).takeIf { it > 0 } ?: return null, left, top, width, height, bw, bh, box.getInt("rotation"),
            appliedRotation, originX, originY, window[2], window[3], a, b, d, c, tx, ty)
    } catch (_: Exception) { null }

    private fun readRearIdentity(remote: top.zainzh.rearscreen.ITaskService): Pair<Int, String>? = try {
        val state = JSONObject(remote.getRearDisplayState())
        val id = state.optInt("selectedId", -1); val unique = state.optString("selectedUnique")
        if (id <= 0 || unique.isBlank()) null else id to "$id:$unique"
    } catch (_: Exception) { null }

    companion object {
        private const val OEM_PACKAGE = "com.xiaomi.subscreencenter"
        private const val READY_TIMEOUT_MS = 18_000L
        private const val CLOSE_TIMEOUT_MS = 8_000L
        private const val PHYSICAL_VISIBLE_TIMEOUT_MS = 3_000L
        private const val POLL_INTERVAL_MS = 100L
        private const val CANCELLED_OPERATION_TTL_MS = 60_000L
    }
}

private fun JSONObject.powerEvidence(expectedDisplayId: Int): CastDisplayPowerEvidence? {
    if (optString("status") != "ok" || optInt("display", -1) != expectedDisplayId) return null
    return CastDisplayPowerEvidence(optInt("power", -1), optInt("powerBefore", -1), optInt("powerAfter", -1))
}

private object ComponentNameCompat {
    fun packageName(flattened: String): String? =
        android.content.ComponentName.unflattenFromString(flattened)?.packageName
    fun canonicalComponent(flattened: String): String =
        android.content.ComponentName.unflattenFromString(flattened)?.flattenToString() ?: ""
}

private fun admissionOccupantClass(value: CastOccupantIdentity?): String = when (value?.mode) {
    null -> "empty"
    CastMode.VIRTUAL -> "virtual"
    CastMode.DIRECT_PHYSICAL -> "physical_task"
}

private fun admissionPowerName(value: Int): String = when (value) {
    Display.STATE_ON -> "ON"; Display.STATE_OFF -> "OFF"; Display.STATE_DOZE -> "DOZE"
    Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"; else -> "UNKNOWN"
}

private fun safeAdmissionSource(value: String): String = when (value) {
    "activity_task", "activity_task_focused_stack", "activity_focused_stack", "am_stack_list" -> value
    else -> "other"
}
