package top.zainzh.rearscreen;

import android.view.Display;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ControlledPhysicalCastAuthorityTest {
    @Test public void selectedIdAndUniqueAreParsedAsOneAuthoritativeSnapshot() {
        ControlledPhysicalCastOperations.SelectedRearIdentity selected =
                ControlledPhysicalCastOperations.parseSelectedRearIdentity(
                        "{\"selectedId\":4,\"selectedUnique\":\"trusted-panel\"}");
        assertNotNull(selected);
        assertEquals(4, selected.selectedId);
        assertEquals("trusted-panel", selected.selectedUnique);
        assertEquals("4:trusted-panel", selected.identity);
        assertTrue(selected.matches(4, "4:trusted-panel"));
        assertFalse(selected.matches(4, "4:replacement-panel"));
        assertFalse(selected.matches(5, "4:trusted-panel"));
    }

    @Test public void absentOrInvalidAuthorityIsUnknown() {
        assertNull(ControlledPhysicalCastOperations.parseSelectedRearIdentity(
                "{\"selectedId\":-1,\"selectedUnique\":\"\"}"));
        assertNull(ControlledPhysicalCastOperations.parseSelectedRearIdentity(
                "{\"selectedId\":4,\"selectedUnique\":\"\",\"error\":\"query_failed\"}"));
        assertNull(ControlledPhysicalCastOperations.parseSelectedRearIdentity(
                "{\"selectedId\":\"4\",\"selectedUnique\":\"trusted-panel\"}"));
        assertNull(ControlledPhysicalCastOperations.parseSelectedRearIdentity("not-json"));
    }

    @Test public void retainedOwnerAtHomeCloseIsDebugOnlyAndRequiresExactMainAndExistingOemLauncher() throws Exception {
        String packageName = "sample.a";
        String base = "sample.a/.Main";
        String identity = "4:rear";
        org.json.JSONObject main = new org.json.JSONObject().put("status", "present").put("task", 81)
                .put("display", 0).put("package", packageName).put("base", base).put("top", base)
                .put("selectedId", 4).put("selectedUnique", "rear")
                .put("rootSnapshotKnown", true).put("rootSole", true);
        assertTrue(ControlledPhysicalCastOperations.exactRetainedOwnerMainPlacement(
                main, 81, packageName, base, 4, identity));
        for (String field : new String[]{"status", "task", "display", "package", "base", "top",
                "selectedId", "selectedUnique", "rootSnapshotKnown", "rootSole"}) {
            Object wrong = "display".equals(field) ? 4 : "rootSnapshotKnown".equals(field)
                    || "rootSole".equals(field) ? false : "invalid";
            assertFalse(field, ControlledPhysicalCastOperations.exactRetainedOwnerMainPlacement(
                    new org.json.JSONObject(main.toString()).put(field, wrong),
                    81, packageName, base, 4, identity));
        }

        String launcher = "{\"status\":\"ok\",\"display\":4,\"power\":1,\"powerBefore\":1,"
                + "\"powerAfter\":1,\"source\":\"activity_task\",\"tasks\":[{\"task\":7,"
                + "\"base\":\"com.oem.mirror\",\"top\":\"com.oem.mirror\","
                + "\"baseComponent\":\"com.oem.mirror/.RearLauncher\","
                + "\"topComponent\":\"com.oem.mirror/.RearLauncher\",\"userId\":0}]}";
        RearDisplayCastOccupancy.ExactLauncherMatcher exactOem = task -> task.userId == 0
                && "com.oem.mirror".equals(task.basePackage) && "com.oem.mirror".equals(task.topPackage)
                && "com.oem.mirror/.RearLauncher".equals(task.baseComponent)
                && task.baseComponent.equals(task.topComponent);
        assertTrue("OFF/DOZE is allowed when stable and the OEM launcher is already present",
                ControlledPhysicalCastOperations.exactOemLauncherOnlySnapshot(launcher, 4, exactOem));
        assertFalse("empty rear is not proof the launcher is already restored",
                ControlledPhysicalCastOperations.exactOemLauncherOnlySnapshot(launcher.replace(
                        "[{\"task\":7,\"base\":\"com.oem.mirror\",\"top\":\"com.oem.mirror\","
                                + "\"baseComponent\":\"com.oem.mirror/.RearLauncher\","
                                + "\"topComponent\":\"com.oem.mirror/.RearLauncher\",\"userId\":0}]", "[]"),
                        4, exactOem));
        assertFalse("an additional task is not an OEM-only rear",
                ControlledPhysicalCastOperations.exactOemLauncherOnlySnapshot(launcher.replace(
                        "}]}", "},{\"task\":8,\"base\":\"other\",\"top\":\"other\"}] }"),
                        4, exactOem));
        assertFalse("an unstable power sample is not a safe home confirmation",
                ControlledPhysicalCastOperations.exactOemLauncherOnlySnapshot(
                        launcher.replace("\"powerAfter\":1", "\"powerAfter\":2"), 4, exactOem));

        assertTrue(ControlledPhysicalCastOperations.retainedPhysicalOwnerAtHomePreconditions(true, true,
                true, true, true, true, true, true, true));
        assertFalse("release and non-.debug builds cannot use this exceptional close",
                ControlledPhysicalCastOperations.retainedPhysicalOwnerAtHomePreconditions(false, false,
                        true, true, true, true, true, true, true));
        assertFalse("replacement journal blocks the close",
                ControlledPhysicalCastOperations.retainedPhysicalOwnerAtHomePreconditions(true, true,
                        false, true, true, true, true, true, true));
        assertFalse("live Keeper and pending start both fail closed",
                ControlledPhysicalCastOperations.retainedPhysicalOwnerAtHomePreconditions(true, true,
                        true, true, false, true, true, true, true));
        assertFalse(ControlledPhysicalCastOperations.retainedPhysicalOwnerAtHomePreconditions(true, true,
                true, true, true, false, true, true, true));
        assertFalse("rear placement is never routed through this no-move close",
                ControlledPhysicalCastOperations.retainedPhysicalOwnerAtHomePreconditions(true, true,
                        true, true, true, true, true, false, true));
        assertFalse("launcher must already be present; this path cannot start it",
                ControlledPhysicalCastOperations.retainedPhysicalOwnerAtHomePreconditions(true, true,
                        true, true, true, true, true, true, false));
    }

    @Test public void readyRequiresExactKeeperClaimNotSuccessfulIntentDispatch() {
        assertEquals("physical_keeper_not_claimed", ControlledPhysicalCastOperations.readinessFailure(
                false, false, false, ControlledPhysicalCastOperations.ExactTaskPlacement.REAR));
        assertNull(ControlledPhysicalCastOperations.readinessFailure(
                true, false, false, ControlledPhysicalCastOperations.ExactTaskPlacement.REAR));
        assertEquals("physical_ready_unverified", ControlledPhysicalCastOperations.readinessFailure(
                true, false, false, ControlledPhysicalCastOperations.ExactTaskPlacement.UNKNOWN));
    }

    @Test public void physicalReplacementReadyRequiresExactDrawnAndTouchableEvidence() throws Exception {
        org.json.JSONObject ready = new org.json.JSONObject().put("status", "ready").put("task", 82)
                .put("package", "writer").put("base", "writer/.Main").put("display", 4)
                .put("selectedId", 4).put("selectedUnique", "rear").put("drawn", true)
                .put("inputReady", true).put("windowId", "a1");
        assertTrue(ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                ready, 82, "writer", "writer/.Main", 4, "4:rear"));
        ready.put("inputReady", false);
        assertFalse(ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                ready, 82, "writer", "writer/.Main", 4, "4:rear"));
        ready.put("inputReady", true).put("selectedUnique", "other");
        assertFalse(ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                ready, 82, "writer", "writer/.Main", 4, "4:rear"));
        ready.put("selectedUnique", "rear").put("drawn", false);
        assertFalse(ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                ready, 82, "writer", "writer/.Main", 4, "4:rear"));
        ready.put("drawn", true).put("windowId", "");
        assertFalse(ControlledPhysicalCastOperations.exactPhysicalPresentationReady(
                ready, 82, "writer", "writer/.Main", 4, "4:rear"));
    }

    @Test public void physicalPlacementAndCompensationRequireExactKnownMainAfterward() {
        String prefix = "\"task\":81,\"selectedId\":4,\"selectedUnique\":\"trusted-panel\","
                + "\"package\":\"reader\",\"base\":\"reader/.Main\",\"rootSnapshotKnown\":true,";
        assertEquals(ControlledPhysicalCastOperations.ExactTaskPlacement.REAR,
                ControlledPhysicalCastOperations.parseExactPlacement("{\"status\":\"present\"," + prefix
                        + "\"display\":4,\"rootSole\":true}", 81, "reader", "reader/.Main", 4, "4:trusted-panel"));
        assertEquals(ControlledPhysicalCastOperations.ExactTaskPlacement.MAIN,
                ControlledPhysicalCastOperations.parseExactPlacement("{\"status\":\"present\"," + prefix
                        + "\"display\":0}", 81, "reader", "reader/.Main", 4, "4:trusted-panel"));
        assertEquals(ControlledPhysicalCastOperations.ExactTaskPlacement.UNKNOWN,
                ControlledPhysicalCastOperations.parseExactPlacement("{\"status\":\"unknown\"}",
                        81, "reader", "reader/.Main", 4, "4:trusted-panel"));
        assertFalse(ControlledPhysicalCastOperations.compensationConfirmedHome(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR,
                ControlledPhysicalCastOperations.ExactTaskPlacement.UNKNOWN));
        assertTrue(ControlledPhysicalCastOperations.compensationConfirmedHome(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR,
                ControlledPhysicalCastOperations.ExactTaskPlacement.MAIN));
        ControlledPhysicalCastOperations.StartResult retained =
                ControlledPhysicalCastOperations.recoverable(81, 23L, true, "post_commit_unknown");
        assertEquals("physical:81:23", retained.sessionId);
        assertEquals(23L, retained.ownerGeneration);
        assertFalse(retained.committed);
        assertTrue(retained.indeterminate);
        assertTrue(retained.keeperStarted);
    }

    @Test public void occupiedPhysicalReplacementRequiresBothExactRootsAndTypedVisibleBWithoutGlobalFocus() throws Exception {
        String a = "{\"task\":81,\"base\":\"reader.pkg\",\"baseComponent\":\"reader.pkg/.Main\",\"topComponent\":\"reader.pkg/.Main\",\"visible\":false,\"requested\":false,\"sleeping\":false}";
        String b = "{\"task\":82,\"base\":\"writer.pkg\",\"baseComponent\":\"writer.pkg/.Main\",\"topComponent\":\"writer.pkg/.Main\",\"visible\":true,\"requested\":true,\"sleeping\":false}";
        String prefix = "{\"status\":\"ok\",\"display\":4,\"power\":2,\"powerBefore\":2,\"powerAfter\":2,\"source\":\"activity_task\",\"tasks\":[";
        org.json.JSONObject ready = new org.json.JSONObject(prefix + a + "," + b + "]}");
        assertTrue(ControlledPhysicalCastOperations.replacementCandidateReadySnapshot(
                ready, 81, "reader.pkg", "reader.pkg/.Main", 82, "writer.pkg", "writer.pkg/.Main", 4));
        assertEquals("ready", ControlledPhysicalCastOperations.replacementCandidateReadinessReason(
                ready, 81, "reader.pkg", "reader.pkg/.Main", 82, "writer.pkg", "writer.pkg/.Main", 4));
        org.json.JSONObject bStillStarting = new org.json.JSONObject(prefix + a + "," + b
                .replace("\"visible\":true", "\"visible\":false") + "]}");
        assertEquals("new_task_not_visible_requested",
                ControlledPhysicalCastOperations.replacementCandidateReadinessReason(
                        bStillStarting, 81, "reader.pkg", "reader.pkg/.Main", 82,
                        "writer.pkg", "writer.pkg/.Main", 4));
        org.json.JSONObject oldStillRequested = new org.json.JSONObject(prefix + a
                .replace("\"requested\":false", "\"requested\":true") + "," + b + "]}");
        assertEquals("old_task_still_requested",
                ControlledPhysicalCastOperations.replacementCandidateReadinessReason(
                        oldStillRequested, 81, "reader.pkg", "reader.pkg/.Main", 82,
                        "writer.pkg", "writer.pkg/.Main", 4));
        org.json.JSONObject duplicate = new org.json.JSONObject(prefix + a + "," + a + "," + b + "]}");
        assertFalse(ControlledPhysicalCastOperations.replacementCandidateReadySnapshot(
                duplicate, 81, "reader.pkg", "reader.pkg/.Main", 82, "writer.pkg", "writer.pkg/.Main", 4));
        org.json.JSONObject extraUnknownRoot = new org.json.JSONObject(prefix + a + "," + b
                + ",{\"task\":99,\"base\":\"unrelated\"}]}");
        assertFalse(ControlledPhysicalCastOperations.replacementCandidateReadySnapshot(
                extraUnknownRoot, 81, "reader.pkg", "reader.pkg/.Main", 82, "writer.pkg", "writer.pkg/.Main", 4));
        org.json.JSONObject unknownA = new org.json.JSONObject(prefix + a.replace("\"baseComponent\":\"reader.pkg/.Main\"", "\"baseComponent\":null") + "," + b + "]}");
        assertFalse(ControlledPhysicalCastOperations.replacementCandidateReadySnapshot(
                unknownA, 81, "reader.pkg", "reader.pkg/.Main", 82, "writer.pkg", "writer.pkg/.Main", 4));
        org.json.JSONObject missingA = new org.json.JSONObject(prefix + b + "]}");
        assertFalse(ControlledPhysicalCastOperations.replacementCandidateReadySnapshot(
                missingA, 81, "reader.pkg", "reader.pkg/.Main", 82, "writer.pkg", "writer.pkg/.Main", 4));
        String actualReader = RearDisplayCastOccupancyReader.fromTaskInfos(Arrays.asList(
                info(81, 4, "reader.pkg", false, false, false),
                info(82, 4, "writer.pkg", true, true, false)), 4, Display.STATE_ON).toJson();
        org.json.JSONObject readerSnapshot = new org.json.JSONObject(actualReader);
        assertFalse("rear TaskManager snapshots have no global focusedTask", readerSnapshot.has("focusedTask"));
        assertTrue(actualReader, ControlledPhysicalCastOperations.replacementCandidateReadySnapshot(
                readerSnapshot, 81, "reader.pkg", "reader.pkg/reader.pkg.Main", 82, "writer.pkg", "writer.pkg/writer.pkg.Main", 4));
        org.json.JSONObject aStillVisible = new org.json.JSONObject(RearDisplayCastOccupancyReader.fromTaskInfos(Arrays.asList(
                info(81, 4, "reader.pkg", true, false, false),
                info(82, 4, "writer.pkg", true, true, false)), 4, Display.STATE_ON).toJson());
        assertFalse("B presentation does not prove A has stopped displaying",
                ControlledPhysicalCastOperations.replacementCandidateReadySnapshot(
                        aStillVisible, 81, "reader.pkg", "reader.pkg/reader.pkg.Main", 82,
                        "writer.pkg", "writer.pkg/writer.pkg.Main", 4));
        org.json.JSONObject aStillRequested = new org.json.JSONObject(RearDisplayCastOccupancyReader.fromTaskInfos(Arrays.asList(
                info(81, 4, "reader.pkg", false, true, false),
                info(82, 4, "writer.pkg", true, true, false)), 4, Display.STATE_ON).toJson());
        assertFalse("B readiness requires A request to be withdrawn too",
                ControlledPhysicalCastOperations.replacementCandidateReadySnapshot(
                        aStillRequested, 81, "reader.pkg", "reader.pkg/reader.pkg.Main", 82,
                        "writer.pkg", "writer.pkg/writer.pkg.Main", 4));
        org.json.JSONObject readerWithThirdTask = new org.json.JSONObject(
                RearDisplayCastOccupancyReader.fromTaskInfos(Arrays.asList(
                        info(81, 4, "reader.pkg", false, false, false), info(82, 4, "writer.pkg", true, true, false),
                        info(99, 4, "other.pkg", true, true, false)), 4, Display.STATE_ON).toJson());
        assertFalse(ControlledPhysicalCastOperations.replacementCandidateReadySnapshot(
                readerWithThirdTask, 81, "reader.pkg", "reader.pkg/reader.pkg.Main", 82,
                "writer.pkg", "writer.pkg/writer.pkg.Main", 4));
    }

    @Test public void exactRearTaskPresentationUsesSingletonVisibleRowNotGlobalFocus() throws Exception {
        org.json.JSONObject fromReader = new org.json.JSONObject(RearDisplayCastOccupancyReader.fromTaskInfos(
                Arrays.asList(info(82, 4, "writer.pkg", true, true, false)), 4, Display.STATE_ON).toJson());
        assertFalse(fromReader.has("focusedTask"));
        assertTrue(fromReader.toString(), ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshot(
                fromReader, 82, "writer.pkg", "writer.pkg/writer.pkg.Main", 4));
        assertFalse(ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshot(
                fromReader, 82, "writer.pkg", "writer.pkg/writer.pkg.Main", 5));
        assertFalse(ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshot(
                new org.json.JSONObject(RearDisplayCastOccupancyReader.fromTaskInfos(Arrays.asList(
                        info(82, 4, "writer.pkg", true, true, false), info(99, 4, "other.pkg", true, true, false)),
                        4, Display.STATE_ON).toJson()), 82, "writer.pkg", "writer.pkg/writer.pkg.Main", 4));
        assertFalse(ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshot(
                new org.json.JSONObject(RearDisplayCastOccupancyReader.fromTaskInfos(
                        Arrays.asList(new OldTaskInfo(82, 4, "writer.pkg")), 4, Display.STATE_ON).toJson()),
                82, "writer.pkg", "writer.pkg/writer.pkg.Main", 4));
    }

    @Test public void exactARecoveryMayKeepOnlyOneVerifiedHiddenOemLauncherCompanion() throws Exception {
        String a = "{\"task\":81,\"base\":\"reader.pkg\",\"top\":\"reader.pkg\","
                + "\"baseComponent\":\"reader.pkg/.Main\",\"topComponent\":\"reader.pkg/.Main\","
                + "\"visible\":true,\"requested\":true,\"sleeping\":false,\"userId\":0}";
        String launcher = "{\"task\":7,\"base\":\"com.xiaomi.mirror\",\"top\":\"com.xiaomi.mirror\","
                + "\"baseComponent\":\"com.xiaomi.mirror/.SubScreenLauncher\","
                + "\"topComponent\":\"com.xiaomi.mirror/.SubScreenLauncher\","
                + "\"visible\":false,\"requested\":false,\"sleeping\":false,\"userId\":0}";
        String prefix = "{\"status\":\"ok\",\"display\":4,\"power\":2,\"powerBefore\":2,"
                + "\"powerAfter\":2,\"source\":\"activity_task\",\"tasks\":[";
        java.util.function.BiPredicate<String, Integer> exactOem = (component, user) -> user == 0
                && "com.xiaomi.mirror/.SubScreenLauncher".equals(component);
        org.json.JSONObject valid = new org.json.JSONObject(prefix + a + "," + launcher + "]}");
        assertTrue(ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(
                valid, 81, "reader.pkg", "reader.pkg/.Main", 4, exactOem));
        assertFalse("ordinary B readiness never permits a launcher companion",
                ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshot(valid, 81,
                        "reader.pkg", "reader.pkg/.Main", 4));
        assertFalse("unknown or unvalidated component is not the OEM launcher",
                ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(
                        valid, 81, "reader.pkg", "reader.pkg/.Main", 4, (component, user) -> false));
        assertFalse("visible launcher is a competing display owner",
                ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(
                        new org.json.JSONObject(prefix + a + "," + launcher.replace("\"visible\":false", "\"visible\":true") + "]}"),
                        81, "reader.pkg", "reader.pkg/.Main", 4, exactOem));
        assertFalse("pending launcher show is not hidden",
                ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(
                        new org.json.JSONObject(prefix + a + "," + launcher.replace("\"requested\":false", "\"requested\":true") + "]}"),
                        81, "reader.pkg", "reader.pkg/.Main", 4, exactOem));
        assertFalse("third task remains protected",
                ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(
                        new org.json.JSONObject(prefix + a + "," + launcher + ","
                                + "{\"task\":99,\"base\":\"other\"}]}"),
                        81, "reader.pkg", "reader.pkg/.Main", 4, exactOem));
        assertFalse("identity must be exact to the system user",
                ControlledPhysicalCastOperations.exactVisiblePhysicalTaskSnapshotWithHiddenLauncher(
                        new org.json.JSONObject(prefix + a + "," + launcher.replace("\"userId\":0", "\"userId\":10") + "]}"),
                        81, "reader.pkg", "reader.pkg/.Main", 4, exactOem));
    }

    @Test public void explicitCloseAcceptsOnlyStableTypedIdleRearAndStoppedVirtualInputResources() throws Exception {
        assertTrue(ControlledPhysicalCastOperations.exactStableNoOwnerRearInventory(new org.json.JSONObject(
                "{\"status\":\"ok\",\"display\":4,\"power\":1,\"powerBefore\":1,\"powerAfter\":1,"
                        + "\"source\":\"activity_task\",\"tasks\":[]}"), 4));
        assertTrue(ControlledPhysicalCastOperations.exactStableNoOwnerRearInventory(new org.json.JSONObject(
                "{\"status\":\"ok\",\"display\":4,\"power\":2,\"powerBefore\":2,\"powerAfter\":2,"
                        + "\"source\":\"activity_task\",\"tasks\":[]}"), 4));
        assertFalse(ControlledPhysicalCastOperations.exactStableNoOwnerRearInventory(new org.json.JSONObject(
                "{\"status\":\"ok\",\"display\":4,\"power\":1,\"powerBefore\":1,\"powerAfter\":2,"
                        + "\"source\":\"activity_task\",\"tasks\":[]}"), 4));
        assertFalse(ControlledPhysicalCastOperations.exactStableNoOwnerRearInventory(new org.json.JSONObject(
                "{\"status\":\"ok\",\"display\":4,\"power\":1,\"powerBefore\":1,\"powerAfter\":1,"
                        + "\"source\":\"am_stack_list\",\"tasks\":[]}"), 4));
        assertFalse(ControlledPhysicalCastOperations.exactStableNoOwnerRearInventory(new org.json.JSONObject(
                "{\"status\":\"ok\",\"display\":4,\"power\":1,\"powerBefore\":1,\"powerAfter\":1,"
                        + "\"source\":\"activity_task\",\"tasks\":[{\"task\":9,\"base\":\"foreign.app\","
                        + "\"top\":\"foreign.app\",\"baseComponent\":\"foreign.app/.Main\","
                        + "\"topComponent\":\"foreign.app/.Main\",\"userId\":0}]}"), 4));
        String exactLauncher = "{\"status\":\"ok\",\"display\":4,\"power\":1,\"powerBefore\":1,"
                + "\"powerAfter\":1,\"source\":\"activity_task\",\"tasks\":[{\"task\":7,"
                + "\"base\":\"com.xiaomi.mirror\",\"top\":\"com.xiaomi.mirror\","
                + "\"baseComponent\":\"com.xiaomi.mirror/.SubScreenLauncher\","
                + "\"topComponent\":\"com.xiaomi.mirror/.SubScreenLauncher\",\"userId\":0}] }";
        org.json.JSONObject exactLauncherSnapshot = new org.json.JSONObject(exactLauncher);
        java.util.function.BiPredicate<String, Integer> exactOem = (component, user) -> user == 0
                && "com.xiaomi.mirror/.SubScreenLauncher".equals(component);
        assertTrue("disabled launcher can close only when the exact OEM row is already present",
                ControlledPhysicalCastOperations.exactStableNoOwnerRearInventory(
                        exactLauncherSnapshot, 4, true, exactOem));
        assertFalse("empty rear cannot stand in for a force-stopped launcher",
                ControlledPhysicalCastOperations.exactStableNoOwnerRearInventory(
                        new org.json.JSONObject("{\"status\":\"ok\",\"display\":4,\"power\":1,"
                                + "\"powerBefore\":1,\"powerAfter\":1,\"source\":\"activity_task\",\"tasks\":[]}"),
                        4, true, exactOem));
        assertFalse("an unverified launcher identity does not satisfy cleanup",
                ControlledPhysicalCastOperations.exactStableNoOwnerRearInventory(
                        exactLauncherSnapshot, 4, true, (component, user) -> false));

        org.json.JSONObject stopped = new org.json.JSONObject().put("state", "stopped")
                .put("managerResourcesStopped", true)
                .put("managerInput", new org.json.JSONObject().put("state", "stopped")
                        .put("resourcesStopped", true).put("outputAvailable", false));
        assertTrue(ControlledPhysicalCastOperations.exactVirtualResourcesStopped(stopped));
        assertFalse(ControlledPhysicalCastOperations.exactVirtualResourcesStopped(
                new org.json.JSONObject("{\"state\":\"stopped\"}")));
        assertFalse(ControlledPhysicalCastOperations.exactVirtualResourcesStopped(new org.json.JSONObject()
                .put("state", "stopped").put("managerResourcesStopped", false)
                .put("managerInput", new org.json.JSONObject().put("state", "running")
                        .put("resourcesStopped", false).put("outputAvailable", true))));
        assertFalse(ControlledPhysicalCastOperations.exactVirtualResourcesStopped(new org.json.JSONObject()
                .put("state", "stopped").put("managerResourcesStopped", false)
                .put("managerInput", new org.json.JSONObject().put("state", "stopped")
                        .put("resourcesStopped", true).put("outputAvailable", false))));
    }

    @Test public void compensationRestoresAOnlyWithLiveJournalOwnerMainBAndUnoccupiedRear() {
        assertTrue(ControlledPhysicalCastOperations.replacementRestoreGate(
                "op", "op", "OWNER_B", true, true, true, true, true, true));
        assertFalse(ControlledPhysicalCastOperations.replacementRestoreGate(
                "op", "op", "OWNER_B", true, true, true, true, false, true));
        assertFalse(ControlledPhysicalCastOperations.replacementRestoreGate(
                "op", "op", "OWNER_B", true, true, false, true, true, true));
        assertFalse(ControlledPhysicalCastOperations.replacementRestoreGate(
                "op", "op", "ACCEPTED_B", true, true, true, true, true, true));
        assertFalse(ControlledPhysicalCastOperations.replacementRestoreGate(
                "op", "stale", "OWNER_B", true, true, true, true, true, true));
        assertFalse(ControlledPhysicalCastOperations.replacementRestoreGate(
                "op", "op", "OWNER_B", true, true, true, false, true, true));
        assertFalse(ControlledPhysicalCastOperations.replacementRestoreGate(
                "op", "op", "OWNER_B", true, true, true, true, true, false));
        assertTrue("A_RETURNING is restorable only after the exact P→P move was authorized",
                ControlledPhysicalCastOperations.replacementRestoreGate(
                        "op", "op", "A_RETURNING", true, true, true, true, true, true));
        assertFalse("phase alone cannot turn an unrelated A→MAIN move into this transaction's move",
                ControlledPhysicalCastOperations.replacementRestoreGate(
                        "op", "op", "A_RETURNING", true, false, true, true, true, true));
        assertFalse("P→V A_RETURNING belongs to its own rollback flow",
                ControlledPhysicalCastOperations.replacementRestoreGate(
                        "op", "op", "A_RETURNING", false, true, true, true, true, true));
        assertFalse("B_READY is not authority to restore an A that moved independently",
                ControlledPhysicalCastOperations.replacementRestoreGate(
                        "op", "op", "B_READY", true, true, true, true, true, true));
        assertFalse("PREPARING is not authority to restore an A that moved independently",
                ControlledPhysicalCastOperations.replacementRestoreGate(
                        "op", "op", "PREPARING", true, true, true, true, true, true));
        assertTrue("the same issued move remains recoverable after A_RETURNED or OWNER_B",
                ControlledPhysicalCastOperations.replacementRestoreGate(
                        "op", "op", "A_RETURNED", true, true, true, true, true, true)
                && ControlledPhysicalCastOperations.replacementRestoreGate(
                        "op", "op", "OWNER_B", true, true, true, true, true, true));
        assertTrue(ControlledPhysicalCastOperations.replacementFinalReadbackReady(
                ControlledPhysicalCastOperations.ExactTaskPlacement.MAIN,
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR, true, "OWNER_B"));
        assertFalse("final unknown must enter the common compensation path",
                ControlledPhysicalCastOperations.replacementFinalReadbackReady(
                        ControlledPhysicalCastOperations.ExactTaskPlacement.UNKNOWN,
                        ControlledPhysicalCastOperations.ExactTaskPlacement.REAR, false, "OWNER_B"));
    }

    @Test public void exactOldOwnerCloseRoutesMainRearAndUnknownWithoutResurrection() {
        assertEquals("NO_MOVE_CLOSE", ControlledPhysicalCastOperations.unacceptedPhysicalCloseRoute(
                ControlledPhysicalCastOperations.ExactTaskPlacement.MAIN));
        assertEquals("RECOVER_THEN_CLOSE", ControlledPhysicalCastOperations.unacceptedPhysicalCloseRoute(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR));
        assertEquals("INDETERMINATE", ControlledPhysicalCastOperations.unacceptedPhysicalCloseRoute(
                ControlledPhysicalCastOperations.ExactTaskPlacement.UNKNOWN));
    }

    @Test public void explicitCloseRecoveryRequiresSamePendingJournalAndCannotRepeatAfterFinish() {
        top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal expected =
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "PREPARING", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "", false, 9L, "owner-b", 0L,
                        "", "NOT_REQUESTED", "", 0L, "NONE");
        assertTrue(ControlledPhysicalCastOperations.sameUnacceptedPhysicalCloseJournal(expected, expected));
        assertFalse("a later transaction token cannot reuse the reserved recovery",
                ControlledPhysicalCastOperations.sameUnacceptedPhysicalCloseJournal(expected,
                        new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                                "next-op", "PREPARING", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                                42, "b.pkg", "b.pkg/.Main", 8L, "", false, 9L, "owner-b", 0L,
                                "", "NOT_REQUESTED", "", 0L, "NONE")));
        assertFalse("a second close after atomic terminal cleanup has no reusable journal",
                ControlledPhysicalCastOperations.sameUnacceptedPhysicalCloseJournal(expected, null));
        assertFalse("session assignment or acceptance exits this recovery-only path",
                ControlledPhysicalCastOperations.sameUnacceptedPhysicalCloseJournal(expected,
                        new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                                "op", "B_READY", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                                42, "b.pkg", "b.pkg/.Main", 8L, "physical:42:8", false, 9L, "owner-b", 8L,
                                "", "NOT_REQUESTED", "", 0L, "NONE")));
    }

    @Test public void replacementFrontierMustRemainSameTokenOwnerAndExpectedPhase() {
        top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal preparing =
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "PREPARING", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "", false, 9L, "owner-b", 0L,
                        "", "NOT_REQUESTED", "", 0L, "NONE");
        top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal ready =
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "B_READY", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "", false, 9L, "owner-b", 0L,
                        "", "NOT_REQUESTED", "", 0L, "NONE");
        assertTrue(ControlledPhysicalCastOperations.replacementJournalAtFrontier(
                preparing, preparing, "PREPARING"));
        assertTrue(ControlledPhysicalCastOperations.replacementJournalAtFrontier(
                preparing, ready, "B_READY"));
        assertFalse(ControlledPhysicalCastOperations.replacementJournalAtFrontier(
                preparing, ready, "PREPARING"));
        assertFalse(ControlledPhysicalCastOperations.replacementJournalAtFrontier(
                preparing, null, "PREPARING"));
        top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal switched =
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "next-op", "PREPARING", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "", false, 9L, "owner-b", 0L,
                        "", "NOT_REQUESTED", "", 0L, "NONE");
        assertFalse(ControlledPhysicalCastOperations.replacementJournalAtFrontier(
                preparing, switched, "PREPARING"));
    }

    @Test public void compensationAcceptsOnlyKnownPhysicalOwnerBSessionAdvance() {
        top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal expected =
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "A_RETURNING", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "", false, 0L, "", 0L,
                        "", "NOT_REQUESTED", "", 0L, "NONE");
        top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal ownerB =
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "OWNER_B", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "physical:42:8", false, 0L, "", 8L,
                        "", "NOT_REQUESTED", "", 0L, "NONE");
        assertTrue(ControlledPhysicalCastOperations.sameKnownPhysicalReplacementOwnerSession(expected, ownerB));
        assertTrue(ControlledPhysicalCastOperations.sameKnownPhysicalReplacementOwnerSession(
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "A_RETURNED", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "", false, 0L, "", 0L,
                        "", "NOT_REQUESTED", "", 0L, "NONE"), ownerB));
        assertFalse(ControlledPhysicalCastOperations.sameKnownPhysicalReplacementOwnerSession(expected,
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "OWNER_B", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "foreign-session", false, 0L, "", 8L,
                        "", "NOT_REQUESTED", "", 0L, "NONE")));
        assertFalse(ControlledPhysicalCastOperations.sameKnownPhysicalReplacementOwnerSession(expected,
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "op", "OWNER_B", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "physical:42:8", false, 0L, "", 9L,
                        "", "NOT_REQUESTED", "", 0L, "NONE")));
        assertFalse(ControlledPhysicalCastOperations.sameKnownPhysicalReplacementOwnerSession(expected,
                new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                        "other-op", "OWNER_B", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear", 7L, true,
                        42, "b.pkg", "b.pkg/.Main", 8L, "physical:42:8", false, 0L, "", 8L,
                        "", "NOT_REQUESTED", "", 0L, "NONE")));
    }

    @Test public void onlyUnknownOrMovedDispatchWithLiveMarkerCanAuthorizeARecoveryFromMain() {
        assertFalse(ControlledPhysicalCastOperations.physicalReturnReceiptAllowsRestore("rejected", false));
        assertFalse(ControlledPhysicalCastOperations.physicalReturnReceiptAllowsRestore("rejected", true));
        assertTrue(ControlledPhysicalCastOperations.physicalReturnReceiptAllowsRestore("unknown", true));
        assertTrue(ControlledPhysicalCastOperations.physicalReturnReceiptAllowsRestore("moved", true));
        assertFalse(ControlledPhysicalCastOperations.physicalReturnReceiptAllowsRestore("unknown", false));
        assertFalse(ControlledPhysicalCastOperations.physicalReturnReceiptAllowsRestore("malformed", false));
    }

    @Test public void finalSampleRejectsThirdRootArrivingDuringPresentationBeforeMove() {
        final java.util.List<String> order = new java.util.ArrayList<>();
        final int[] moveCalls = {0};
        boolean ready = ControlledPhysicalCastOperations.runReplacementFinalSampleGate(
                new ControlledPhysicalCastOperations.ReplacementFinalChecks() {
                    @Override public boolean presentationReady() { order.add("B_PRESENTATION_WMS_INPUT"); return true; }
                    @Override public boolean finalInventoryAndSelectedReady() {
                        order.add("A_B_C_INVENTORY_SELECTED"); // inventory observes late root C
                        return false;
                    }
                    @Override public boolean finalLocalGateReady() { order.add("LOCAL_GATE"); return true; }
                });
        if (ready) moveCalls[0]++;
        assertFalse(ready);
        assertEquals(0, moveCalls[0]);
        assertEquals(java.util.Arrays.asList("B_PRESENTATION_WMS_INPUT", "A_B_C_INVENTORY_SELECTED"), order);
    }

    @Test public void finalSampleChecksAuthorizationAfterSelectedReadBeforeMove() {
        final java.util.List<String> order = new java.util.ArrayList<>();
        final boolean[] authorized = {true};
        final int[] moveCalls = {0};
        boolean ready = ControlledPhysicalCastOperations.runReplacementFinalSampleGate(
                new ControlledPhysicalCastOperations.ReplacementFinalChecks() {
                    @Override public boolean presentationReady() { order.add("B_PRESENTATION_WMS_INPUT"); return true; }
                    @Override public boolean finalInventoryAndSelectedReady() {
                        order.add("A_B_INVENTORY");
                        order.add("FINAL_SELECTED_BINDER_READ");
                        authorized[0] = false; // revocation occurs while the last remote read is in flight
                        return true;
                    }
                    @Override public boolean finalLocalGateReady() {
                        order.add("NONBLOCKING_LOCAL_AUTH_JOURNAL_OWNER_GATE");
                        return authorized[0];
                    }
                });
        if (ready) moveCalls[0]++;
        assertFalse(ready);
        assertEquals(0, moveCalls[0]);
        assertEquals(java.util.Arrays.asList("B_PRESENTATION_WMS_INPUT", "A_B_INVENTORY",
                "FINAL_SELECTED_BINDER_READ", "NONBLOCKING_LOCAL_AUTH_JOURNAL_OWNER_GATE"), order);
    }

    @Test public void preReturnReadyCannotSubstituteForPostKeeperFinalPresentation() {
        boolean preReturnReady = ControlledPhysicalCastOperations.runReplacementFinalSampleGate(
                new ControlledPhysicalCastOperations.ReplacementFinalChecks() {
                    @Override public boolean presentationReady() { return true; }
                    @Override public boolean finalInventoryAndSelectedReady() { return true; }
                    @Override public boolean finalLocalGateReady() { return true; }
                });
        assertTrue(preReturnReady);
        final int[] deliveryCalls = {0};
        boolean finalReady = ControlledPhysicalCastOperations.runReplacementFinalSampleGate(
                new ControlledPhysicalCastOperations.ReplacementFinalChecks() {
                    @Override public boolean presentationReady() { return false; }
                    @Override public boolean finalInventoryAndSelectedReady() {
                        deliveryCalls[0]++;
                        return true;
                    }
                    @Override public boolean finalLocalGateReady() {
                        deliveryCalls[0]++;
                        return true;
                    }
                });
        assertFalse("missing post-A/post-Keeper WMS/input evidence must not deliver ReplaceReady", finalReady);
        assertEquals(0, deliveryCalls[0]);
    }

    @Test public void p2vEarlyRollbackAcceptsOnlyExactRecordedAOrAFreeRear() {
        String aOnly = "{\"status\":\"ok\",\"display\":4,\"power\":2,\"tasks\":["
                + "{\"task\":41,\"base\":\"a.pkg\",\"top\":\"a.pkg\","
                + "\"baseComponent\":\"a.pkg/.Main\",\"topComponent\":\"a.pkg/.Main\"}]}";
        String thirdParty = aOnly.substring(0, aOnly.length() - 2)
                + ", {\"task\":99,\"base\":\"other.pkg\",\"top\":\"other.pkg\","
                + "\"baseComponent\":\"other.pkg/.Main\",\"topComponent\":\"other.pkg/.Main\"}]}";
        String empty = "{\"status\":\"ok\",\"display\":4,\"power\":2,\"tasks\":[]}";
        assertTrue(ControlledPhysicalCastOperations.p2vRecoveryAPlacementAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR, true, aOnly, 4, 41, "a.pkg", "a.pkg/.Main"));
        assertFalse(ControlledPhysicalCastOperations.p2vRecoveryAPlacementAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR, false, aOnly, 4, 41, "a.pkg", "a.pkg/.Main"));
        assertFalse(ControlledPhysicalCastOperations.p2vRecoveryAPlacementAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR, true, thirdParty, 4, 41, "a.pkg", "a.pkg/.Main"));
        assertTrue(ControlledPhysicalCastOperations.p2vRecoveryAPlacementAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.MAIN, false, empty, 4, 41, "a.pkg", "a.pkg/.Main"));
    }

    private static PhysicalTaskInfo info(int task, int display, String pkg,
            boolean visible, boolean requested, boolean sleeping) {
        return new PhysicalTaskInfo(task, display, pkg, visible, requested, sleeping);
    }

    public static final class TestComponent {
        private final String value;
        TestComponent(String value) { this.value = value; }
        public String flattenToString() { return value; }
    }
    public static class OldTaskInfo {
        public final int taskId, displayId, userId = 0;
        public final TestComponent baseActivity, topActivity;
        OldTaskInfo(int task, int display, String pkg) {
            taskId = task; displayId = display;
            baseActivity = topActivity = new TestComponent(pkg + "/.Main");
        }
    }
    public static final class PhysicalTaskInfo extends OldTaskInfo {
        private final boolean visible;
        public final boolean isVisibleRequested, isSleeping;
        PhysicalTaskInfo(int task, int display, String pkg, boolean visible, boolean requested, boolean sleeping) {
            super(task, display, pkg); this.visible = visible;
            isVisibleRequested = requested; isSleeping = sleeping;
        }
        public boolean isVisible() { return visible; }
    }

    @Test public void neverAllocatedBRequiresExactPersistedStoppedBaselineNotAnEmptyHistory() throws Exception {
        org.json.JSONObject baseline = new org.json.JSONObject().put("state", "stopped")
                .put("id", "old-session").put("generation", 4L)
                .put("coordinatorOperationId", "old-op").put("ownerGeneration", 3L)
                .put("ownerIdentity", "old-owner").put("task", 12).put("package", "old.pkg")
                .put("baseComponent", "old.pkg/.Main").put("rearDisplayId", 4).put("rearIdentity", "4:rear");
        assertTrue(ControlledPhysicalCastOperations.matchesUnallocatedVirtualBaseline(
                new org.json.JSONObject(baseline.toString()), baseline.toString()));
        assertFalse(ControlledPhysicalCastOperations.matchesUnallocatedVirtualBaseline(
                new org.json.JSONObject(baseline.toString()).put("id", "some-other-history"), baseline.toString()));
        assertFalse(ControlledPhysicalCastOperations.matchesUnallocatedVirtualBaseline(
                new org.json.JSONObject(baseline.toString()).put("state", "running"), baseline.toString()));
    }
}
