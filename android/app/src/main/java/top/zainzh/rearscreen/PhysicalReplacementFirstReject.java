package top.zainzh.rearscreen;

/** Low-cardinality, Debug-only summary vocabulary for the verified-device P→P experiment path. */
final class PhysicalReplacementFirstReject {
    private PhysicalReplacementFirstReject() { }

    static boolean enabled(boolean debug, String device, String model) {
        return debug && PhysicalReplacementBottomFocus.supportedDevice(device, model);
    }

    static boolean stableInventory(RearDisplayCastOccupancy.Snapshot snapshot, int display) {
        return snapshot != null && snapshot.failure == null && snapshot.displayId == display
                && snapshot.tasks != null && snapshot.displayState == android.view.Display.STATE_ON
                && snapshot.powerBefore == android.view.Display.STATE_ON
                && snapshot.powerAfter == android.view.Display.STATE_ON
                && ("activity_task".equals(snapshot.source)
                    || "activity_task_focused_stack".equals(snapshot.source)
                    || "activity_focused_stack".equals(snapshot.source));
    }

    static String rearAInventoryReason(RearDisplayCastOccupancy.Snapshot snapshot,
            int display, int taskId, String packageName, String base) {
        if (!stableInventory(snapshot, display)) return "snapshot_unknown";
        RearDisplayCastOccupancy.Task found = null;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (RearDisplayCastOccupancy.Task row : snapshot.tasks) {
            if (row == null || row.taskId <= 0 || !ids.add(row.taskId)) return "snapshot_unknown";
            if (row.taskId == taskId) {
                if (found != null) return "a_identity";
                found = row;
            }
        }
        if (found == null || found.userId != 0 || !packageName.equals(found.basePackage)
                || !packageName.equals(found.topPackage) || !base.equals(found.baseComponent)
                || !base.equals(found.topComponent)) return "a_identity";
        if (found.visible == null || found.requested == null || found.sleeping == null)
            return "snapshot_unknown";
        return Boolean.TRUE.equals(found.visible) && Boolean.TRUE.equals(found.requested)
                && Boolean.FALSE.equals(found.sleeping) ? null : "a_not_visible";
    }

    static String mainBInventoryReason(RearDisplayCastOccupancy.Snapshot snapshot,
            int taskId, String packageName, String base) {
        if (!stableInventory(snapshot, 0)) return "snapshot_unknown";
        RearDisplayCastOccupancy.Task found = null;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (RearDisplayCastOccupancy.Task row : snapshot.tasks) {
            if (row == null || row.taskId <= 0 || !ids.add(row.taskId)) return "snapshot_unknown";
            if (row.taskId == taskId) {
                if (found != null) return "b_identity";
                found = row;
            }
        }
        if (found == null || found.userId != 0 || !packageName.equals(found.basePackage)
                || !packageName.equals(found.topPackage) || !base.equals(found.baseComponent)
                || !base.equals(found.topComponent)) return "b_identity";
        if (found.visible == null || found.requested == null || found.sleeping == null)
            return "snapshot_unknown";
        return Boolean.TRUE.equals(found.visible) && Boolean.TRUE.equals(found.requested)
                && Boolean.FALSE.equals(found.sleeping) ? null : "b_not_visible_main";
    }


    /** Mirrors the source predicate's short-circuit order; null means a later predicate was not run. */
    static String firstInventoryFailure(RearDisplayCastOccupancy.Snapshot rear, int rearDisplay,
            int aTask, String aPackage, String aBase, boolean desktopExact,
            RearDisplayCastOccupancy.Snapshot main, int bTask, String bPackage, String bBase,
            Boolean mainCandidateExact, Boolean aAlsoMain) {
        if (!desktopExact) {
            String aFailure = rearAInventoryReason(rear, rearDisplay, aTask, aPackage, aBase);
            return aFailure == null ? "desktop_identity" : aFailure;
        }
        if (Boolean.FALSE.equals(mainCandidateExact)) {
            String bFailure = mainBInventoryReason(main, bTask, bPackage, bBase);
            return bFailure == null ? "snapshot_unknown" : bFailure;
        }
        if (Boolean.TRUE.equals(aAlsoMain)) return "a_also_main";
        return null;
    }

    static String result(String raw) {
        if ("not_called".equals(raw) || "unsupported".equals(raw) || "rejected".equals(raw)
                || "incomplete".equals(raw) || "unknown".equals(raw) || "requested".equals(raw)) return raw;
        return "unknown";
    }

    static String reason(String raw) {
        if (raw == null) return "unknown";
        switch (raw) {
            case "host_authority": case "host_claim": case "host_target_current":
            case "host_reservation": case "host_owner": case "host_placement":
            case "host_journal": case "host_pre_rpc_gate": case "reply_unknown":
            case "response_unclassified": case "invalid_replacement_authority":
            case "invalid_actions": case "caller_rejected": case "capability_unavailable":
            case "dispatch_gate_rejected": case "rear_identity": case "snapshot_unknown":
            case "a_identity": case "a_not_visible": case "desktop_identity":
            case "b_identity": case "b_not_visible_main": case "a_also_main":
            case "root_inventory": case "a_presentation_ready": case "a_presentation_window_not_drawn":
            case "a_presentation_input_main_stale": case "a_presentation_owner_missing":
            case "a_presentation_other": case "b_presentation_ready":
            case "b_presentation_window_not_drawn": case "b_presentation_input_main_stale":
            case "b_presentation_owner_missing": case "b_presentation_other":
            case "process_identity": case "bottom_rejected_before_send":
            case "bottom_reply_unknown": case "bottom_readback_unknown":
            case "focus_rejected_before_send": case "focus_reply_unknown":
            case "bottom_focus_requested": case "post_bottom_proof_unknown":
            case "source_evidence_unverified": case "bottom_pre_gate":
                return raw;
            default: return "unknown";
        }
    }

    static String summary(String rawResult, String rawReason, boolean bMoved) {
        return "physical_replace phase=bottom_dispatch event=bottom_dispatch result=" + result(rawResult)
                + " reason=" + reason(rawReason) + " b_moved=" + (bMoved ? "1" : "0");
    }

    static String hostGateReason(boolean authorized, boolean claimed, boolean targetCurrent,
            boolean reservationCurrent) {
        if (!authorized) return "host_authority";
        if (!claimed) return "host_claim";
        if (!targetCurrent) return "host_target_current";
        if (!reservationCurrent) return "host_reservation";
        return "unknown";
    }

    static String presentationReason(String evidence, String side) {
        String prefix = "a".equals(side) ? "a_presentation_" : "b_presentation_";
        if (evidence == null || evidence.isBlank()) return prefix + "other";
        switch (evidence) {
            case "ready": return prefix + "ready";
            case "window_not_drawn_and_shown": return prefix + "window_not_drawn";
            case "input_main_display_stale": return prefix + "input_main_stale";
            default: return prefix + "other";
        }
    }
}
