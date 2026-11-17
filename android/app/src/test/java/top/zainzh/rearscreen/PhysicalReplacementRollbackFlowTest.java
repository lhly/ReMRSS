package top.zainzh.rearscreen;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class PhysicalReplacementRollbackFlowTest {
    private static PhysicalReplacementRollbackFlow.Stage stage(List<String> calls, String name, boolean result) {
        return () -> { calls.add(name); return result; };
    }

    @Test public void actualRollbackPipelineOrdersClaimStopReadbackMoveKeeperAndFinish() throws Exception {
        List<String> calls = new ArrayList<>();
        assertTrue(PhysicalReplacementRollbackFlow.execute(stage(calls, "rollback_claim", true),
                stage(calls, "exact_pre_stop_gate", true),
                () -> { calls.add("stop_exact_b"); calls.add("read_exact_terminal"); return true; },
                () -> { calls.add("move_a_if_needed"); calls.add("restore_keeper"); return true; },
                stage(calls, "finish_owner_a", true)));
        assertEquals(java.util.Arrays.asList("rollback_claim", "exact_pre_stop_gate", "stop_exact_b",
                "read_exact_terminal", "move_a_if_needed", "restore_keeper", "finish_owner_a"), calls);
    }

    @Test public void acceptWinnerAndThirdPartyGateCauseNoCandidateOrAEffects() throws Exception {
        List<String> acceptCalls = new ArrayList<>();
        assertFalse(PhysicalReplacementRollbackFlow.execute(stage(acceptCalls, "accept_already_won", false),
                stage(acceptCalls, "gate", true), stage(acceptCalls, "stop", true),
                stage(acceptCalls, "restore_a", true), stage(acceptCalls, "finish", true)));
        assertEquals(java.util.Arrays.asList("accept_already_won"), acceptCalls);

        List<String> thirdParty = new ArrayList<>();
        assertFalse(PhysicalReplacementRollbackFlow.execute(stage(thirdParty, "rollback_claim", true),
                stage(thirdParty, "third_party_or_wrong_host", false), stage(thirdParty, "stop", true),
                stage(thirdParty, "restore_a", true), stage(thirdParty, "finish", true)));
        assertEquals(java.util.Arrays.asList("rollback_claim", "third_party_or_wrong_host"), thirdParty);
    }

    @Test public void unknownStopReceiptNeverMovesAOrFinishesJournal() throws Exception {
        List<String> calls = new ArrayList<>();
        assertFalse(PhysicalReplacementRollbackFlow.execute(stage(calls, "rollback_claim", true),
                stage(calls, "exact_pre_stop_gate", true),
                () -> { calls.add("stop_exact_b"); calls.add("terminal_unknown"); return false; },
                stage(calls, "restore_a", true), stage(calls, "finish", true)));
        assertEquals(java.util.Arrays.asList("rollback_claim", "exact_pre_stop_gate",
                "stop_exact_b", "terminal_unknown"), calls);
    }

    private static top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal journal() {
        return journal("B_READY");
    }
    private static top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal journal(String phase) {
        return new top.zainzh.rearscreen.platform.LegacyDisplayOccupancy.PhysicalReplacementJournal(
                "token", phase, 11, "old.pkg", "old.pkg/.Main", 4, "4:rear", 2L, false,
                22, "new.pkg", "new.pkg/.Main", 1L, "session", true, 9L, "owner", 3L,
                "baseline", "READY", "reader", 7L, "ROLLBACK");
    }

    private static String inventory(boolean includeA, boolean unknownTask, String hostComponent) throws Exception {
        JSONArray rows = new JSONArray().put(new JSONObject().put("task", 50).put("base", "app")
                .put("top", "app").put("baseComponent", hostComponent).put("userId", 0));
        if (includeA) rows.put(new JSONObject().put("task", 11).put("base", "old.pkg")
                .put("top", "old.pkg").put("baseComponent", "old.pkg/.Main").put("userId", 0));
        rows.put(new JSONObject().put("task", 80).put("base", "com.xiaomi.subscreencenter")
                .put("top", "com.xiaomi.subscreencenter").put("baseComponent", "com.xiaomi.subscreencenter/.Launcher")
                .put("userId", 0));
        if (unknownTask) rows.put(new JSONObject().put("task", 91).put("base", "third.party")
                .put("top", "third.party").put("baseComponent", "third.party/.Main").put("userId", 0));
        return new JSONObject().put("status", "ok").put("display", 4).put("tasks", rows).toString();
    }

    @Test public void productionPreStopInventoryAllowsOnlyExactAAndBoundHostPlusVerifiedLauncher() throws Exception {
        String host = "app/top.zainzh.rearscreen.VirtualRearHostActivity";
        JSONObject live = new JSONObject().put("state", "running").put("host", 50);
        java.util.function.BiPredicate<String, Integer> verified = (component, user) ->
                user == 0 && "com.xiaomi.subscreencenter/.Launcher".equals(component);
        assertTrue(ControlledPhysicalCastOperations.p2vPreStopInventoryAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR, "SAME_A",
                inventory(true, false, host), journal(), live, host, verified));
        assertTrue(ControlledPhysicalCastOperations.p2vPreStopInventoryAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.MAIN, "SAME_A",
                inventory(false, false, host), journal("A_RETURNED"), live, host, verified));
        assertFalse(ControlledPhysicalCastOperations.p2vPreStopInventoryAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR, "SAME_A",
                inventory(true, false, "impostor/.VirtualRearHostActivity"), journal(), live, host, verified));
        assertFalse(ControlledPhysicalCastOperations.p2vPreStopInventoryAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR, "SAME_A",
                inventory(true, true, host), journal(), live, host, verified));
        assertFalse(ControlledPhysicalCastOperations.p2vPreStopInventoryAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.REAR, "FOREIGN",
                inventory(true, false, host), journal(), live, host, verified));
    }

    @Test public void ownerVirtualWithClearedOwnerAndAOnMainCanStopExactBoundCandidate() throws Exception {
        String host = "app/top.zainzh.rearscreen.VirtualRearHostActivity";
        JSONObject live = new JSONObject().put("state", "running").put("host", 50);
        java.util.function.BiPredicate<String, Integer> verified = (component, user) ->
                user == 0 && "com.xiaomi.subscreencenter/.Launcher".equals(component);
        String absent = top.zainzh.rearscreen.platform.PhysicalReplacementLifecycle.replacementOwnerFact(
                "OWNER_VIRTUAL", -1, null, null, -1, null, 0L, false,
                11, "old.pkg", "old.pkg/.Main", 4, "4:rear", 2L);
        assertEquals("ABSENT", absent);
        assertTrue(ControlledPhysicalCastOperations.p2vPreStopInventoryAllowed(
                ControlledPhysicalCastOperations.ExactTaskPlacement.MAIN, absent,
                inventory(false, false, host), journal("OWNER_VIRTUAL"), live, host, verified));
        for (String nonAbsent : new String[]{"FOREIGN", "UNKNOWN"}) {
            assertFalse(top.zainzh.rearscreen.platform.PhysicalReplacementLifecycle.p2vOwnerFactAllowed(
                    "OWNER_VIRTUAL", "MAIN", nonAbsent));
            assertFalse(ControlledPhysicalCastOperations.p2vPreStopInventoryAllowed(
                    ControlledPhysicalCastOperations.ExactTaskPlacement.MAIN, nonAbsent,
                    inventory(false, false, host), journal("OWNER_VIRTUAL"), live, host, verified));
        }
        assertFalse(top.zainzh.rearscreen.platform.PhysicalReplacementLifecycle.p2vOwnerFactAllowed(
                "OWNER_VIRTUAL", "REAR", "ABSENT"));
    }
}
