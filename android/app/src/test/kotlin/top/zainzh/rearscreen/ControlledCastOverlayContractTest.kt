package top.zainzh.rearscreen

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ControlledCastOverlayContractTest {
    @Test fun virtualCastActionGateEmitsOneBoundedFailureDiscriminator() {
        val source = source("android/app/src/main/java/top/zainzh/rearscreen/RearVirtualCast.java")
        val gate = source.substringAfter("void ensureControlledStartCurrent(").substringBefore("private boolean recordReaderLifecycle")
        val diagnostic = source.substringAfter("private void logControlledStartGateReject(").substringBefore("private boolean recordReaderLifecycle")
        val entry = source.substringAfter("if (actionGate != null) {").substringBefore("JSONObject inputState")
        assertTrue(diagnostic.contains("stage=controlled_start_gate_rejected phase="))
        listOf("session_cancelled", "action_gate_stale", "action_gate_unavailable",
            "target_gate_stale", "target_gate_unavailable").forEach { assertTrue(gate.contains("\"$it\"")) }
        assertTrue(diagnostic.contains("controlledStartGateRejectLogged.compareAndSet(false, true)"))
        assertTrue(diagnostic.contains("BuildConfig.DEBUG"))
        assertFalse("the discriminator never logs raw Binder exception details", diagnostic.contains("unavailable.toString()"))
        assertTrue(entry.contains("stage=controlled_start_gate_rejected phase=entry reason=action_gate_stale"))
        assertTrue(entry.contains("stage=controlled_start_gate_rejected phase=entry reason=action_gate_unavailable"))
        assertTrue("entry gate failure preserves the original exception", entry.contains("throw unavailable"))
    }

    @Test fun physicalToVirtualReturnRechecksAuthorityAtActualDispatch() {
        val java = "android/app/src/main/java/top/zainzh/rearscreen/"
        val transaction = source(java + "ControlledPhysicalCastOperations.java")
            .substringAfter("boolean completePhysicalToVirtualReplacement(").substringBefore("private static boolean safeAuthorized")
        val service = source(java + "TaskService.java")
            .substringAfter("String returnPhysicalAForVirtualReplacement(").substringBefore("private final Object controlledPhysicalMoveLock")
        val bridge = source(java + "RearSystemBridge.java")
            .substringAfter("java.util.function.BooleanSupplier dispatchGate)").substringBefore("static boolean commandSucceeded")
        assertTrue(transaction.indexOf("advancePhysicalReplacementToBReady") in 0 until
            transaction.indexOf("advancePhysicalReplacementToAReturning"))
        assertTrue(transaction.contains("live.equals(aReturning)") &&
            transaction.contains("RearScreenKeeperService.physicalCastOwnerGeneration()") &&
            transaction.contains("safeAuthorized(authorized)") &&
            transaction.contains("remote.returnPhysicalAForVirtualReplacement"))
        assertFalse(transaction.contains("controlledMove("))
        assertTrue(service.contains("matchesRunningPhysicalReplacement") &&
            service.contains("synchronized (controlledPhysicalMoveLock)") &&
            service.contains("targetDisplayId, dispatchGate"))
        // The lambda is deferred; the helper evaluates the live gate before it invokes Binder.
        val move = bridge.substringBefore("@FunctionalInterface interface RootTaskMoveInvoker")
        val resolution = move.indexOf("getMethod(\"moveRootTaskToDisplay\"")
        val gatedDispatch = move.indexOf("dispatchRootMoveAfterGate(dispatchGate,")
        assertTrue(resolution >= 0 && resolution < gatedDispatch)
        assertTrue(move.substring(gatedDispatch).startsWith(
            "dispatchRootMoveAfterGate(dispatchGate,\n                () -> move.invoke(manager, location.rootTask, targetDisplay))"))
    }

    @Test fun taskServiceSeparatesFinalGateFromCastEngineFailure() {
        val source = source("android/app/src/main/java/top/zainzh/rearscreen/TaskService.java")
        val method = source.substringAfter("private String startControlledRearVirtualCastWithGateInternal(")
            .substringBefore("private static boolean isGateCurrent")
        assertTrue(method.indexOf("rejectStage = \"final_action_gate\"") < method.indexOf("if (!isGateCurrent(actionGate)) throw"))
        assertTrue(method.indexOf("rejectStage = \"cast_backend_start\"") > method.indexOf("if (!isGateCurrent(actionGate)) throw"))
        assertTrue(method.indexOf("rejectStage = \"cast_backend_start\"") < method.indexOf("return virtualCast.startControlled("))
        assertTrue(method.contains("controlled_start_reject stage="))
    }

    @Test fun pickerAndConfirmationAreTransparentNonDimmingMiuixActivities() {
        val manifest = xml("android/app/src/main/AndroidManifest.xml")
        val activities = manifest.getElementsByTagName("activity").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        listOf(
            ".ControlledCastConfirmationActivity",
            ".platform.mods.ModCastTargetPickerActivity"
        ).forEach { name ->
            val activity = activities.single { it.getAttribute("android:name") == name }
            assertEquals("false", activity.getAttribute("android:exported"))
            assertEquals("@style/MiuixCastOverlayTheme", activity.getAttribute("android:theme"))
        }

        val styles = xml("android/app/src/main/res/values/styles.xml")
        val overlay = styles.getElementsByTagName("style").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.single { it.getAttribute("name") == "MiuixCastOverlayTheme" }
        assertEquals("MiuixHostTheme", overlay.getAttribute("parent"))
        val items = overlay.getElementsByTagName("item").let { nodes ->
            (0 until nodes.length).associate { index ->
                val item = nodes.item(index) as Element
                item.getAttribute("name") to item.textContent.trim()
            }
        }
        assertEquals("true", items["android:windowIsTranslucent"])
        assertEquals("@android:color/transparent", items["android:windowBackground"])
        assertEquals("false", items["android:backgroundDimEnabled"])
    }

    @Test fun modSceneSettingsRouteUsesTheHostPickerOnMainDisplayAndReturnsResult() {
        val scene = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModRearSceneActivity.kt")
        assertTrue(scene.contains("startActivityForResult(intent, MOD_CAST_PICKER_REQUEST"))
        assertTrue(scene.contains("setLaunchDisplayId(Display.DEFAULT_DISPLAY)"))
        assertTrue(scene.contains("if (requestCode != MOD_CAST_PICKER_REQUEST) return"))
        assertTrue(scene.contains("EXTRA_RESULT_SESSION_ID"))
        val settings = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModSettingsActivity.kt")
        assertTrue(settings.contains("private fun openRear(id: String)"))
        assertTrue(settings.contains("ComponentName(this, ModRearSceneActivity::class.java)"))
    }

    @Test fun mainSettingsCastUsesItsOwnLiveSourceAndTheSameHostPicker() {
        val settings = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModConfigActivity.kt")
        val picker = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModCastTargetPickerActivity.kt")
        val selector = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModCastForegroundTargetActivity.kt")
        val broker = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModEventBroker.kt")
        assertTrue(settings.contains("castState.sourceFor(modId)"))
        assertTrue(settings.contains("submitUserEvent(active.id, it, castSource = castSource)"))
        assertTrue(settings.contains("isModConfigMainDisplay(display?.displayId)"))
        assertTrue(settings.contains("ModCastForegroundTargetActivity.createIntent(this, effect, source, requestId)"))
        assertTrue(settings.contains("castState.requestId != requestId"))
        assertTrue(selector.contains("ModCastActivationSources.isRequestCurrent"))
        assertTrue(selector.contains("选择主屏应用"))
        assertTrue(selector.contains("EXTRA_FOREGROUND_CHOICE_TOKEN"))
        assertTrue(selector.contains("FLAG_ACTIVITY_NEW_TASK"))
        val launchPath = selector.substringAfter("private fun openConfirmationForChoice").substringBefore("private fun readEffect")
        assertTrue("host starts the separate transparent task before leaving the visible chooser",
            launchPath.indexOf("startActivity(pickerIntent") < launchPath.indexOf("finish()"))
        assertTrue("the explicit selection is resolved through its one-shot owner-bound token",
            picker.contains("CastTargetSelector.ForegroundChoiceToken"))
        assertFalse("pre-confirmation never changes task focus", picker.contains("focusMainTask("))
        assertFalse("pre-confirmation never runs a focus visibility wait", picker.contains("settleConfirmationTaskVisibility"))
        assertTrue(picker.contains("runtime.coordinator.prepare(activeOwner, request)"))
        assertTrue("cancelled async prepare still aborts a ticket it produced", picker.contains("preparedRef.get()?.let"))
        assertTrue(picker.contains("EXTRA_RESULT_SESSION_ID"))
        assertTrue(broker.contains("gesture.castSource == null"))
        assertTrue(broker.contains("gesture.castSource)"))
        val sourceEpochCheck = picker.substringAfter("private fun requestCurrent(").substringBefore("private fun loadChoices")
        assertTrue("all flows require the exact live surface registration",
            sourceEpochCheck.contains("isRegistered(activeSource, activeMod)"))
        assertTrue("main settings additionally require the exact current request epoch",
            sourceEpochCheck.contains("!mainSettingsFlow || ModCastActivationSources.isRequestCurrent(activeSource, requestId)"))
        assertTrue("late in-flight Ready closes only its exact handle", picker.contains("close(activeOwner, event.handle)"))
        assertTrue("finishReady fallback closes only its exact handle", picker.contains("close(it, event.handle)"))
        val confirmation = picker.substringAfter("private fun confirmPrepared()").substringBefore("private fun replacementSummary")
        assertTrue("request epoch is checked before confirmation", confirmation.contains("requestCurrent()"))
        assertTrue("the captured exact epoch gates asynchronous commit side effects", confirmation.contains("requestIsCurrent = {") &&
            confirmation.contains("isRequestCurrent(activeSource, activeRequestId)"))
        assertTrue("late Ready after replacement closes only its own handle", confirmation.contains("close(activeOwner, event.handle)"))
        assertTrue("Ready delivery also checks the exact request", picker.substringAfter("private fun finishReady").contains("!requestCurrent(activeSource, activeModId)"))
        val coordinator = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/ControlledCastCoordinator.kt")
        val prepareOnly = coordinator.substringAfter("fun prepare(owner:").substringBefore("fun commit(")
        assertFalse("prepare resolves and inspects only; backend start remains behind the second confirmation",
            prepareOnly.contains("backend.start("))
        assertTrue("request epoch is checked before activation consumption", coordinator.indexOf("if (!isRequestCurrent(record))") <
            coordinator.indexOf("activations.consume(record.requestOwner"))
        assertTrue("request epoch reaches backend authorization",
            coordinator.substringAfter("private fun isStartCurrent").substringBefore("private fun claimStartSideEffect").contains("isRequestCurrent(record)"))
        assertTrue("request epoch reaches the actual side-effect claim",
            coordinator.substringAfter("private fun claimStartSideEffect").substringBefore("private fun onBackendEvent").contains("isRequestCurrent(record)"))
        val taskService = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/AndroidCastTaskService.kt")
        val backend = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/AndroidControlledCastBackend.kt")
        assertTrue("explicit hidden target requires the exact visible picker task", taskService.contains("confirmationPickerIsCurrent"))
        assertTrue("backend admission rechecks exact selected task activity before its guarded action", backend.contains("taskService.isExplicitTargetCurrent(target)"))
        val sceneGate = source("android/app/src/main/aidl/top/zainzh/rearscreen/ISceneTaskActionGate.aidl")
        val virtualCast = source("android/app/src/main/java/top/zainzh/rearscreen/RearVirtualCast.java")
        val virtualMoveGate = virtualCast.substringAfter("void ensureControlledStartCurrent(").substringBefore("private boolean recordReaderLifecycle")
        val virtualStart = virtualCast.substringAfter("ensureControlledStartCurrent(\"before_target_move\")").substringBefore("ensureControlledStartCurrent(\"after_target_move\")")
        assertTrue("the internal task gate exposes a separate pre-move attestation", sceneGate.contains("isCurrentBeforeTargetMove"))
        assertTrue("only the pre-move phase invokes the explicit target attestation", virtualMoveGate.contains("isCurrentBeforeTargetMove"))
        assertTrue("virtual task move follows the pre-move check, with no target-focus gate after it",
            virtualStart.indexOf("moveIfExact") > 0 && !virtualStart.contains("isCurrentBeforeTargetMove"))
        val taskServiceAidl = source("android/app/src/main/aidl/top/zainzh/rearscreen/ITaskService.aidl")
        val virtualHost = source("android/app/src/main/java/top/zainzh/rearscreen/VirtualRearHostActivity.java")
        val privilegedService = source("android/app/src/main/java/top/zainzh/rearscreen/TaskService.java")
        assertTrue("virtual replacement has a separate appended transaction for the explicit Mod gate",
            taskServiceAidl.contains("prepareRearVirtualCastReplacementWithTargetGate") &&
                taskServiceAidl.contains("ISceneTaskActionGate targetActionGate) = 79"))
        assertTrue("the real virtual-to-virtual candidate path forwards its source gate",
            virtualHost.contains("remote.prepareRearVirtualCastReplacementWithTargetGate") &&
                virtualHost.contains("candidateSourceActionGate);"))
        assertTrue("legacy replacement remains on the old null-gate transaction",
            privilegedService.contains("owner, gestures, viewport, compatibility, rotation, full, null)"))
        assertTrue("privileged replacement installs the optional gate before begin moves the task",
            virtualCast.contains("b.controlledStartActionGate = targetActionGate;") &&
                virtualCast.indexOf("b.controlledStartActionGate = targetActionGate;") < virtualCast.indexOf("b.begin(false);"))
        val physicalOps = source("android/app/src/main/java/top/zainzh/rearscreen/ControlledPhysicalCastOperations.java")
        val physicalReplacement = physicalOps.substringAfter("public static StartResult replace(").substringBefore("public static")
        assertTrue("physical replacement rechecks B only at its last pre-move gate",
            physicalReplacement.indexOf("targetCurrentBeforeBMove.getAsBoolean()") in 0 until physicalReplacement.indexOf("controlledMove(remote, newTask"))
        assertTrue("late backend Ready is compensated", coordinator.contains("backend.abort(event.backendSessionId)"))
        val manifest = source("android/app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("${'$'}{applicationId}.cast.confirm"))
        val focus = privilegedService.substringAfter("int focusExactMainTask").substringBefore("/**\n     * 根据taskId获取包名")
        assertTrue(focus.contains("am", ignoreCase = true))
        assertFalse(focus.contains("inputProxy.taskLeaving"))
        assertFalse(focus.contains("moveTaskToDisplay"))
        assertFalse(focus.contains("saveLastTask"))
    }

    @Test fun sdkCastRevocationIsTiedToPackageIdentityNotBinderDisconnect() {
        val service = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/ipc/RemoteRearPlatformService.kt")
        val monitor = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/ipc/SdkCastPackageIdentityMonitor.kt")
        assertTrue(monitor.contains("Intent.ACTION_PACKAGE_REMOVED"))
        assertTrue(monitor.contains("Intent.ACTION_PACKAGE_REPLACED"))
        assertTrue(monitor.contains("SdkCastIdentityRevocation.shouldRevoke"))
        assertTrue(monitor.contains("runtime.coordinator.revokeOwner(owner)"))
        assertTrue(service.contains("pendingCastActivations.remove(pending.nonce, pending)"))
        val release = service.substringAfter("override fun releaseClient(").substringBefore("override fun requestSceneActivation(")
        assertTrue(release.contains("sdkConnectionClosed"))
        assertFalse("an ordinary Binder release must not revoke a committed Cast", release.contains("revokeOwner"))
    }

    @Test fun castFailureDiagnosticIsBoundToTheConfirmedRequestAndNeverLogsEventPayload() {
        val probe = source("android/app/src/androidTest/java/top/zainzh/rearscreen/Rc5TargetTaskSnapshotProbe.java")
        val castTest = source("android/app/src/androidTest/kotlin/top/zainzh/rearscreen/platform/mods/Rc5SignedModCastEntryProbeTest.kt")
        val coordinator = source("android/app/src/main/kotlin/top/zainzh/rearscreen/platform/ControlledCastCoordinator.kt")
        val host = source("android/app/src/main/java/top/zainzh/rearscreen/VirtualRearHostActivity.java")
        assertTrue(host.contains("stage=controlled_host_action_gate_rejected reason="))
        assertTrue(host.contains("reason=display_action_reservation_stale elapsed_ms="))
        assertTrue(host.contains("reject(\"output_surface_invalid\")"))
        assertTrue(host.contains("reject(\"source_gate_stale\")"))
        assertTrue(host.contains("gateRejectLogged.compareAndSet(false, true)"))
        assertTrue(coordinator.contains("stage=start_action_gate_rejected reason=\$reason"))
        assertTrue(coordinator.contains("record.expiresAt <= clock() -> \"ticket_expired\""))
        assertTrue(coordinator.contains("tickets[record.id] !== record -> \"ticket_not_current\""))
        assertTrue(coordinator.contains("if (!authority.isCurrentForActivation(record.requestOwner)) \"owner_stale\""))
        assertTrue(coordinator.contains("else if (!isRequestCurrent(record)) \"request_stale\""))
        assertTrue(coordinator.contains("BuildConfig.DEBUG && record.startGateRejectLogged?.compareAndSet(false, true) == true"))
        assertTrue(probe.contains("stamp.timestampMs >= requestStartedAtMs"))
        assertTrue(probe.contains("stamp.timestampMs <= sampleTimeMs"))
        assertTrue(probe.contains("!\"controlled_virtual_start_failed\".equals(parts[1])"))
        assertTrue(probe.contains("matchingEvents == 1 ? controlledStartFailureStage(failure) : \"none\""))
        assertFalse("diagnostic never emits a compatibility event verbatim",
            probe.contains("Log.i(tag, event"))
        assertTrue(castTest.contains("beforeClick = { confirmationActionStartedAtMs = System.currentTimeMillis() }"))
        assertTrue(castTest.contains("beforeClick?.invoke()\n        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)"))
        assertTrue(castTest.contains("logControlledVirtualStartDiagnostic(TAG, confirmationActionStartedAtMs)"))
        assertTrue(castTest.contains("private fun extractCastFailureToken(texts: List<String>)"))
    }

    private fun xml(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(path))
    private fun source(path: String) = file(path).readText()

    private fun file(path: String): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(cursor, path)
            if (candidate.isFile) return candidate
            val appRelative = File(cursor, path.removePrefix("android/app/"))
            if (appRelative.isFile) return appRelative
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Missing source fixture: $path")
    }
}
