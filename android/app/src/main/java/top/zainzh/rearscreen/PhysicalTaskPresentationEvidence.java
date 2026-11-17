package top.zainzh.rearscreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parser for one bounded WMS + InputDispatcher read; unknown OEM formats fail closed. */
final class PhysicalTaskPresentationEvidence {
    private static final Pattern WMS_HEADER = Pattern.compile(
            "^\\s*(?:Window #\\d+ )?Window\\{([0-9a-fA-F]+)\\s+u\\d+\\s+([^}]+)\\}:?.*$");
    private static final Pattern INPUT_NAME = Pattern.compile("name=(?:'([^']*)'|\\\"([^\\\"]*)\\\"|(.+?)),?\\s+id=");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+");
    private static final Pattern TYPE = Pattern.compile("\\bty=(\\d+|BASE_APPLICATION)\\b");
    private static final Pattern DISPLAY = Pattern.compile("\\b(?:mDisplayId|displayId)=(\\d+)\\b");
    private static final Pattern TASK = Pattern.compile("\\b(?:mTaskId|taskId)=(\\d+)\\b");
    private static final Pattern WMS_UID = Pattern.compile("\\bmOwnerUid=(\\d+)\\b");
    private static final Pattern INPUT_ID = Pattern.compile("\\bid=(\\d+)\\b");
    private static final Pattern OWNER_PID = Pattern.compile("\\bownerPid=(\\d+)\\b");
    private static final Pattern OWNER_UID = Pattern.compile("\\bownerUid=(\\d+)\\b");
    private static final Pattern INPUT_CONFIG = Pattern.compile(
            "\\binputConfig=(.*?)\\s+(?:alpha|frame|touchableRegion)=");
    private static final Pattern ADJACENT_CONFIG_FIELD = Pattern.compile(
            "^,\\s*([A-Za-z][A-Za-z0-9_]{0,30})=([^,]*)(.*)$");
    private static final Pattern MIUI_FLAGS_MARKER = Pattern.compile(
            "(?:^|,)\\s*miuiFlags\\b");
    private static final Pattern MIUI_FLAGS_DECIMAL = Pattern.compile("(?:0|[1-9][0-9]{0,9})");
    private static final Pattern MIUI_FLAGS_HEX = Pattern.compile("0x(?:0|[1-9a-f][0-9a-f]{0,7})");
    private static final Pattern MIUI_FLAGS_ENUM = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern INPUT_REGION = Pattern.compile(
            "\\btouchableRegion=(.*?)(?=\\s+ownerPid=)");

    /** Route-only parsers stay unloaded in Release and outside the two exact debug fixtures. */
    private static final class RouteDiagnostics {
        static final Pattern INLINE_DISPLAY_ID = Pattern.compile("\\bdisplayId\\s*=\\s*([^\\s,]+)");
        static final Pattern INLINE_MDISPLAY_ID = Pattern.compile("\\bmDisplayId\\s*=\\s*([^\\s,]+)");
        static final Pattern FOCUS_DISPLAY_VALUE = Pattern.compile(
                "\\bdisplayId\\s*=\\s*(\\d+)\\b|\\bDisplayId\\s*[:=]\\s*(\\d+)\\b|\\bDisplay\\s*[:=]\\s*(\\d+)\\b");
        static final Pattern ANY_WINDOW_NAME = Pattern.compile(
                "\\bname=(?:'([^']*)'|\\\"([^\\\"]*)\\\"|([^,]+))");
        static final Pattern DISPATCHER_DISPLAY_SEGMENT = Pattern.compile(
                "^\\s*(?:Display(?:Id)?\\s*[:=]\\s*(\\d+)\\s*|Display\\s+(\\d+)\\s*:)\\s*$");
        static final Pattern DISPATCHER_WINDOWS_HEADER = Pattern.compile("^\\s*Windows\\s*:");
        static final Pattern FOCUSED_WINDOWS_HEADER = Pattern.compile("^\\s*FocusedWindows?\\s*:");
        static final Pattern INPUT_SECTION_HEADER = Pattern.compile(
                "^\\s*[A-Za-z][A-Za-z0-9 _-]*:\\s*(?:<[^>]*>)?\\s*$");
        static final Pattern ROUTE_PROBE_V1 = Pattern.compile(
                "\\Alane=(a|b) service_contract=96 "
                        + "dispatcher_segment=(unique:(?:0|[1-9][0-9]{0,9})|missing|conflict) "
                        + "dispatcher_displayId=(unique:(?:0|[1-9][0-9]{0,9})|missing|conflict) "
                        + "dispatcher_mDisplayId=(unique:(?:0|[1-9][0-9]{0,9})|missing|conflict) "
                        + "focus=(main|rear|both|none|unknown) row_count=(one|none|multiple)\\z");
        static final Pattern PRESENTATION_SAMPLE_V1 = Pattern.compile(
                "\\Alane=(a|b) window=([0-9a-f]{1,16}) display=([1-9][0-9]{0,9}) "
                        + "epoch_start_ms=([0-9]{1,18}) epoch_end_ms=([0-9]{1,18}) "
                        + "elapsed_start_ms=([0-9]{1,18}) elapsed_end_ms=([0-9]{1,18}) "
                        + "class=(ready|input_main_display_stale|other_not_ready)\\z");
        static final Pattern PRESENTATION_LAYER_BINDING_V2 = Pattern.compile(
                "\\Alane=(a|b) window=([0-9a-f]{1,16}) display=([1-9][0-9]{0,9}) "
                        + "task=([1-9][0-9]{0,9}) dispatcher_id=([1-9][0-9]{0,9}) owner_uid=([1-9][0-9]{0,9}) "
                        + "epoch_start_ms=([0-9]{1,18}) epoch_end_ms=([0-9]{1,18}) "
                        + "elapsed_start_ms=([0-9]{1,18}) elapsed_end_ms=([0-9]{1,18}) "
                        + "uptime_start_ms=([0-9]{1,18}) uptime_end_ms=([0-9]{1,18}) "
                        + "class=(ready|input_main_display_stale|other_not_ready)\\z");
    }

    private PhysicalTaskPresentationEvidence() { }

    static final class Result {
        final boolean ready;
        final String reason, windowId, debugStructure, routeStructure, presentationSampleV1,
                presentationLayerBindingV2;
        final int dispatcherOwnerPid;
        Result(boolean ready, String reason, String windowId) { this(ready, reason, windowId, null); }
        Result(boolean ready, String reason, String windowId, String debugStructure) {
            this(ready, reason, windowId, debugStructure, null, null);
        }
        Result(boolean ready, String reason, String windowId, String debugStructure, String routeStructure) {
            this(ready, reason, windowId, debugStructure, routeStructure, null);
        }
        Result(boolean ready, String reason, String windowId, String debugStructure, String routeStructure,
                String presentationSampleV1) {
            this(ready, reason, windowId, debugStructure, routeStructure, presentationSampleV1, null);
        }
        Result(boolean ready, String reason, String windowId, String debugStructure, String routeStructure,
                String presentationSampleV1, String presentationLayerBindingV2) {
            this(ready, reason, windowId, debugStructure, routeStructure, presentationSampleV1,
                    presentationLayerBindingV2, -1);
        }
        Result(boolean ready, String reason, String windowId, String debugStructure, String routeStructure,
                String presentationSampleV1, String presentationLayerBindingV2, int dispatcherOwnerPid) {
            this.ready = ready; this.reason = reason; this.windowId = windowId;
            this.debugStructure = debugStructure; this.routeStructure = routeStructure;
            this.presentationSampleV1 = presentationSampleV1;
            this.presentationLayerBindingV2 = presentationLayerBindingV2;
            this.dispatcherOwnerPid = dispatcherOwnerPid;
        }
    }

    static final class InputSampleClock {
        final long epochStartMs, epochEndMs, elapsedStartMs, elapsedEndMs, uptimeStartMs, uptimeEndMs;
        InputSampleClock(long epochStartMs, long epochEndMs, long elapsedStartMs, long elapsedEndMs) {
            this(epochStartMs, epochEndMs, elapsedStartMs, elapsedEndMs, -1L, -1L);
        }
        InputSampleClock(long epochStartMs, long epochEndMs, long elapsedStartMs, long elapsedEndMs,
                long uptimeStartMs, long uptimeEndMs) {
            this.epochStartMs = epochStartMs; this.epochEndMs = epochEndMs;
            this.elapsedStartMs = elapsedStartMs; this.elapsedEndMs = elapsedEndMs;
            this.uptimeStartMs = uptimeStartMs; this.uptimeEndMs = uptimeEndMs;
        }
        boolean isValid() {
            return epochStartMs >= 0L && epochEndMs >= epochStartMs
                    && elapsedStartMs >= 0L && elapsedEndMs >= elapsedStartMs;
        }
        boolean isLayerBindingValid() {
            return isValid() && uptimeStartMs >= 0L && uptimeEndMs >= uptimeStartMs;
        }
    }

    static Result evaluate(String windowDump, String inputDump, int taskId, String packageName,
            String baseComponent, int displayId, int expectedUid) {
        return evaluate(windowDump, inputDump, taskId, packageName, baseComponent, displayId, expectedUid, true);
    }

    static Result evaluate(String windowDump, String inputDump, int taskId, String packageName,
            String baseComponent, int displayId, int expectedUid, boolean includeDebugStructure) {
        if (displayId <= 0) return unknown("invalid_input");
        return evaluate(windowDump, inputDump, taskId, packageName, baseComponent, displayId,
                expectedUid, includeDebugStructure, displayId);
    }

    /** Debug-only caller may inspect an exact MAIN task while classifying the selected rear lane. */
    static Result evaluate(String windowDump, String inputDump, int taskId, String packageName,
            String baseComponent, int displayId, int expectedUid, boolean includeDebugStructure,
            int routeRearDisplayId) {
        return evaluate(windowDump, inputDump, taskId, packageName, baseComponent, displayId, expectedUid,
                includeDebugStructure, routeRearDisplayId, null);
    }

    static Result evaluate(String windowDump, String inputDump, int taskId, String packageName,
            String baseComponent, int displayId, int expectedUid, boolean includeDebugStructure,
            int routeRearDisplayId, InputSampleClock sampleClock) {
        return evaluate(windowDump, inputDump, taskId, packageName, baseComponent, displayId,
                expectedUid, includeDebugStructure, routeRearDisplayId, sampleClock, false);
    }

    /** Private production transaction proof may bind the already-validated exact owner PID. */
    static Result evaluate(String windowDump, String inputDump, int taskId, String packageName,
            String baseComponent, int displayId, int expectedUid, boolean includeDebugStructure,
            int routeRearDisplayId, InputSampleClock sampleClock, boolean includeInternalOwnerPid) {
        if (windowDump == null || inputDump == null || taskId <= 0 || packageName == null
                || baseComponent == null || displayId < 0 || routeRearDisplayId <= 0 || expectedUid < 0)
            return unknown("invalid_input");
        List<String[]> windows = matchingWindows(windowDump, packageName, baseComponent);
        List<String[]> exactTaskWindows = new ArrayList<>();
        for (String[] window : windows) {
            if (hasExactlyOneInt(window[1], TASK, taskId)) exactTaskWindows.add(window);
        }
        if (exactTaskWindows.size() != 1)
            return unknown(exactTaskWindows.isEmpty() ? "window_task_missing" : "window_task_ambiguous");
        String id = exactTaskWindows.get(0)[0];
        String body = exactTaskWindows.get(0)[1];
        if (!hasInt(body, DISPLAY, displayId) || !hasInt(body, WMS_UID, expectedUid))
            return unknown("window_display_or_owner_mismatch");
        if (!body.contains("mHasSurface=true") || !body.contains("mDrawState=HAS_DRAWN")
                || !shownAndVisible(body) || !shownOnScreen(body)
                || (body.contains("mHidden=") && !body.contains("mHidden=false"))
                || (body.contains("mViewVisibility=") && !body.contains("mViewVisibility=0x0")))
            return unknown("window_not_drawn_and_shown");
        Matcher type = TYPE.matcher(body);
        if (!type.find() || !("BASE_APPLICATION".equals(type.group(1))
                || parseInt(type.group(1), -1) == 1)) return unknown("not_base_app_window");
        boolean exactWmsWindowKey = hasExactlyOneInt(body, TASK, taskId)
                && hasExactlyOneInt(body, DISPLAY, displayId)
                && hasExactlyOneInt(body, WMS_UID, expectedUid);

        Result result = evaluateInputEvidence(inputDump, id, taskId, packageName, baseComponent,
                displayId, expectedUid, includeDebugStructure);
        int dispatcherOwnerPid = includeInternalOwnerPid
                || (BuildConfig.DEBUG && includeDebugStructure && fixtureLane(packageName, baseComponent) != null)
                ? exactDispatcherOwnerPid(inputDump, id, packageName, baseComponent, expectedUid, result) : -1;
        if (dispatcherOwnerPid > 0) result = new Result(result.ready, result.reason, result.windowId,
                result.debugStructure, result.routeStructure, result.presentationSampleV1,
                result.presentationLayerBindingV2, dispatcherOwnerPid);
        // A focus row is attributable to the exact app window only after the Dispatcher owner,
        // numeric input ID and PID have all passed the normal exact-row checks.
        if ("input_uid_missing".equals(result.reason) || "input_uid_mismatch".equals(result.reason)
                || "input_id_invalid".equals(result.reason) || "input_pid_invalid".equals(result.reason))
            return result;
        return withRouteProbe(result, inputDump, id, packageName, baseComponent, routeRearDisplayId,
                taskId, displayId, expectedUid, exactWmsWindowKey, includeDebugStructure, sampleClock);
    }

    private static int exactDispatcherOwnerPid(String inputDump, String windowId, String pkg, String base,
            int expectedUid, Result result) {
        if (expectedUid <= 0 || result == null || "input_uid_missing".equals(result.reason)
                || "input_uid_mismatch".equals(result.reason) || "input_id_invalid".equals(result.reason)
                || "input_pid_invalid".equals(result.reason)) return -1;
        List<String> rows = matchingInputWindows(inputDump, windowId, pkg, base);
        if (rows.size() != 1) return -1;
        String row = rows.get(0);
        if (!hasExactlyOneInt(row, OWNER_UID, expectedUid) || !hasExactlyOnePositiveInt(row, INPUT_ID)
                || !hasExactlyOnePositiveInt(row, OWNER_PID)) return -1;
        Integer pid = firstInt(row, OWNER_PID);
        return pid != null && pid > 0 ? pid : -1;
    }

    private static Result evaluateInputEvidence(String inputDump, String id, int taskId,
            String packageName, String baseComponent, int displayId, int expectedUid,
            boolean includeDebugStructure) {
        List<String> inputWindows = matchingInputWindows(inputDump, id, packageName, baseComponent);
        if (inputWindows.size() != 1)
            return unknown(inputWindows.isEmpty() ? "input_window_missing" : "input_window_ambiguous");
        String input = inputWindows.get(0);
        Integer inputDisplay = firstInt(input, DISPLAY);
        if (inputDisplay == null) return unknown("input_display_missing");
        Integer inputUid = firstInt(input, OWNER_UID);
        if (inputUid == null) return unknown("input_uid_missing");
        if (inputUid != expectedUid) return unknown("input_uid_mismatch");
        if (!hasPositiveInt(input, INPUT_ID)) return unknown("input_id_invalid");
        if (!hasPositiveInt(input, OWNER_PID)) return unknown("input_pid_invalid");
        // InputDispatcher can briefly retain MAIN after the exact drawn WMS window moves rearward.
        // Expose this stale value only after exact window, owner and positive IDs are proven.
        if (inputDisplay != displayId)
            return unknown(inputDisplay == 0 ? "input_main_display_stale" : "input_display_mismatch");
        InputFlags flags = inputFlagsStatus(input);
        if (flags == InputFlags.MISSING)
            return unknown("input_flags_missing", includeDebugStructure
                    ? inputFlagsDebug(input, flags, "not_checked", packageName, baseComponent) : null);
        if (!flags.accepted)
            return unknown("input_flags_rejected", includeDebugStructure
                    ? inputFlagsDebug(input, flags, "not_checked", packageName, baseComponent) : null);
        RegionCheck region = inputRegionStatus(input);
        if (region.status != RegionStatus.VALID) {
            String structural = includeDebugStructure
                    ? inputFlagsDebug(input, flags, region.diagnostic, packageName, baseComponent) : null;
            if (region.status == RegionStatus.MISSING) return unknown("input_region_missing", structural);
            if (region.status == RegionStatus.EMPTY) return unknown("input_region_empty", structural);
            return unknown("input_region_invalid", structural);
        }
        return new Result(true, "ready", id);
    }

    /** Attach diagnostics after the acceptance result exists; probe failures never change that result. */
    private static Result withRouteProbe(Result result, String inputDump, String windowId, String pkg,
            String base, int rearDisplayId, int taskId, int presentationDisplayId, int expectedUid,
            boolean exactWmsWindowKey, boolean includeDebugStructure, InputSampleClock sampleClock) {
        if (!includeDebugStructure || fixtureLane(pkg, base) == null) return result;
        try {
            String route = dispatcherRouteStructure(inputDump, windowId, pkg, base, rearDisplayId);
            String sample = null;
            String layerBinding = null;
            if ("b".equals(fixtureLane(pkg, base)) && presentationDisplayId == rearDisplayId
                    && sampleClock != null && sampleClock.isValid()
                    && exactWmsWindowKey
                    && exactInputWindowIdentity(inputDump, windowId, pkg, base, expectedUid, result, sampleClock)) {
                String classification = result.ready ? "ready"
                        : "input_main_display_stale".equals(result.reason)
                            ? "input_main_display_stale" : "other_not_ready";
                sample = "lane=b window=" + windowId.toLowerCase(Locale.ROOT)
                        + " display=" + rearDisplayId
                        + " epoch_start_ms=" + sampleClock.epochStartMs
                        + " epoch_end_ms=" + sampleClock.epochEndMs
                        + " elapsed_start_ms=" + sampleClock.elapsedStartMs
                        + " elapsed_end_ms=" + sampleClock.elapsedEndMs
                        + " class=" + classification;
                layerBinding = presentationLayerBindingV2(inputDump, windowId, taskId, pkg, base,
                        rearDisplayId, expectedUid, sampleClock, classification);
            }
            return new Result(result.ready, result.reason, result.windowId, result.debugStructure,
                    route, sample, layerBinding, result.dispatcherOwnerPid);
        } catch (RuntimeException | LinkageError unavailable) {
            return result;
        }
    }

    private static String presentationLayerBindingV2(String inputDump, String windowId, int taskId, String pkg,
            String base, int displayId, int expectedUid, InputSampleClock clock, String classification) {
        if (clock == null || !clock.isLayerBindingValid() || taskId <= 0 || windowId == null
                || !windowId.matches("[0-9a-fA-F]{1,16}") || expectedUid <= 0
                || !("ready".equals(classification) || "input_main_display_stale".equals(classification)
                    || "other_not_ready".equals(classification))) return null;
        List<String> rows = matchingInputWindows(inputDump, windowId, pkg, base);
        if (rows.size() != 1) return null;
        String row = rows.get(0);
        if (!hasExactlyOnePositiveInt(row, INPUT_ID) || !hasExactlyOneInt(row, OWNER_UID, expectedUid)
                || !hasExactlyOnePositiveInt(row, OWNER_PID)) return null;
        Integer inputId = firstInt(row, INPUT_ID);
        Integer ownerUid = firstInt(row, OWNER_UID);
        if (inputId == null || ownerUid == null || ownerUid != expectedUid) return null;
        return "lane=b window=" + windowId.toLowerCase(Locale.ROOT) + " display=" + displayId
                + " task=" + taskId + " dispatcher_id=" + inputId + " owner_uid=" + ownerUid
                + " epoch_start_ms=" + clock.epochStartMs + " epoch_end_ms=" + clock.epochEndMs
                + " elapsed_start_ms=" + clock.elapsedStartMs + " elapsed_end_ms=" + clock.elapsedEndMs
                + " uptime_start_ms=" + clock.uptimeStartMs + " uptime_end_ms=" + clock.uptimeEndMs
                + " class=" + classification;
    }

    private static boolean exactInputWindowIdentity(String inputDump, String windowId, String pkg, String base,
            int expectedUid, Result result, InputSampleClock sampleClock) {
        if (sampleClock == null || expectedUid < 0 || windowId == null
                || !windowId.matches("[0-9a-fA-F]{1,16}")) return false;
        List<String> rows = matchingInputWindows(inputDump, windowId, pkg, base);
        if (rows.size() != 1) return false;
        String row = rows.get(0);
        return hasExactlyOneInt(row, OWNER_UID, expectedUid)
                && hasExactlyOnePositiveInt(row, INPUT_ID) && hasExactlyOnePositiveInt(row, OWNER_PID)
                && !"input_uid_missing".equals(result.reason) && !"input_uid_mismatch".equals(result.reason)
                && !"input_id_invalid".equals(result.reason) && !"input_pid_invalid".equals(result.reason);
    }

    private static List<String[]> matchingWindows(String dump, String pkg, String base) {
        List<String[]> result = new ArrayList<>();
        String id = null; StringBuilder body = new StringBuilder(); String title = null;
        for (String line : dump.split("\\r?\\n")) {
            Matcher header = WMS_HEADER.matcher(line);
            if (header.matches()) {
                addWindow(result, id, title, body.toString(), pkg, base);
                id = header.group(1).toLowerCase(Locale.ROOT);
                title = header.group(2).trim(); body.setLength(0);
            } else if (id != null) body.append(line).append('\n');
        }
        addWindow(result, id, title, body.toString(), pkg, base);
        return result;
    }

    private static void addWindow(List<String[]> out, String id, String title, String body,
            String pkg, String base) {
        if (id != null && sameComponent(title, base) && title.startsWith(pkg + "/")) out.add(new String[]{id, body});
    }

    private static List<String> matchingInputWindows(String dump, String expectedId, String pkg, String base) {
        List<String> result = new ArrayList<>();
        for (String line : dump.split("\\r?\\n")) {
            Matcher name = INPUT_NAME.matcher(line);
            if (!name.find()) continue;
            if (matchesInputWindow(line, expectedId, pkg, base)) result.add(line);
        }
        return result;
    }

    private static boolean matchesInputWindow(String line, String expectedId, String pkg, String base) {
        Matcher name = INPUT_NAME.matcher(line);
        if (!name.find()) return false;
        String value = name.group(1) != null ? name.group(1)
                : name.group(2) != null ? name.group(2) : name.group(3).trim();
        String[] parts = value.trim().split("\\s+", 2);
        return parts.length == 2 && expectedId.equals(parts[0].toLowerCase(Locale.ROOT))
                && sameComponent(parts[1], base) && parts[1].startsWith(pkg + "/");
    }

    /** Fixed, name-free routing evidence from the same input snapshot and exact window match. */
    private static String dispatcherRouteStructure(String dump, String windowId, String pkg,
            String base, int rearDisplayId) {
        List<Integer> segments = new ArrayList<>();
        List<String> displayIds = new ArrayList<>();
        List<String> mDisplayIds = new ArrayList<>();
        Integer segment = null;
        int segmentIndent = -1;
        int windowsIndent = -1;
        int rowCount = 0;
        for (String line : dump.split("\\r?\\n")) {
            Matcher header = RouteDiagnostics.DISPATCHER_DISPLAY_SEGMENT.matcher(line);
            if (header.matches()) {
                segment = parseNonNegative(firstNonNull(header.group(1), header.group(2)));
                segmentIndent = indentation(line);
                windowsIndent = -1;
            } else if (segmentIndent >= 0 && indentation(line) <= segmentIndent) {
                segment = null;
                segmentIndent = -1;
                windowsIndent = -1;
            }
            if (segment != null && RouteDiagnostics.DISPATCHER_WINDOWS_HEADER.matcher(line).find()
                    && indentation(line) > segmentIndent) {
                windowsIndent = indentation(line);
            } else if (windowsIndent >= 0 && indentation(line) <= windowsIndent
                    && !RouteDiagnostics.DISPATCHER_WINDOWS_HEADER.matcher(line).find()) {
                windowsIndent = -1;
            }
            boolean exactInputRow = matchesInputWindow(line, windowId, pkg, base);
            boolean exactDisplayWindow = segment != null && windowsIndent >= 0
                    && focusNameMatch(line, windowId, pkg, base) == FocusNameMatch.EXACT;
            if (!exactInputRow && !exactDisplayWindow) continue;
            rowCount++;
            if (exactDisplayWindow) segments.add(segment);
            collectFocusDisplayValues(line, displayIds);
            collectField(line, RouteDiagnostics.INLINE_MDISPLAY_ID, mDisplayIds);
        }
        String focus = focusDisposition(dump, windowId, pkg, base, rearDisplayId);
        return "dispatcher_segment=" + summarizeValues(segments)
                + " dispatcher_displayId=" + summarizeValues(displayIds)
                + " dispatcher_mDisplayId=" + summarizeValues(mDisplayIds)
                + " focus=" + focus + " row_count=" + (rowCount == 0 ? "none" : rowCount == 1 ? "one" : "multiple");
    }

    private static void collectField(String line, Pattern fieldPattern, List<String> values) {
        Matcher matcher = fieldPattern.matcher(line);
        while (matcher.find()) values.add(matcher.group(1));
    }

    private static void collectFocusDisplayValues(String line, List<String> values) {
        Matcher matcher = RouteDiagnostics.FOCUS_DISPLAY_VALUE.matcher(line);
        while (matcher.find()) {
            String value = firstNonNull(matcher.group(1), matcher.group(2));
            values.add(firstNonNull(value, matcher.group(3)));
        }
    }

    private static String firstNonNull(String first, String second) { return first != null ? first : second; }

    private static String summarizeValues(List<?> rawValues) {
        if (rawValues.isEmpty()) return "missing";
        Integer unique = null;
        for (Object raw : rawValues) {
            Integer parsed = raw instanceof Integer ? (Integer) raw : parseNonNegative(String.valueOf(raw));
            if (parsed == null || (unique != null && !unique.equals(parsed))) return "conflict";
            int value = parsed;
            unique = value;
        }
        return unique == null ? "missing" : "unique:" + unique;
    }

    private static Integer parseNonNegative(String value) {
        int parsed = parseInt(value, -1);
        return parsed < 0 ? null : parsed;
    }

    private static String focusDisposition(String dump, String windowId, String pkg, String base,
            int rearDisplayId) {
        boolean inFocusedWindows = false;
        boolean sectionSeen = false;
        boolean targetSeen = false;
        boolean unknownTargetDisplay = false;
        boolean uncertainTargetIdentity = false;
        List<Integer> targetDisplays = new ArrayList<>();
        Integer segment = null;
        int segmentIndent = -1;
        int focusIndent = -1;
        for (String line : dump.split("\\r?\\n")) {
            Matcher display = RouteDiagnostics.DISPATCHER_DISPLAY_SEGMENT.matcher(line);
            if (display.matches()) {
                segment = parseNonNegative(firstNonNull(display.group(1), display.group(2)));
                segmentIndent = indentation(line);
            } else if (segmentIndent >= 0 && indentation(line) <= segmentIndent) {
                segment = null;
                segmentIndent = -1;
            }
            if (RouteDiagnostics.FOCUSED_WINDOWS_HEADER.matcher(line).find()) {
                inFocusedWindows = true;
                sectionSeen = true;
                focusIndent = indentation(line);
            } else if (inFocusedWindows && indentation(line) <= focusIndent
                    && (display.matches() || RouteDiagnostics.INPUT_SECTION_HEADER.matcher(line).matches())) {
                inFocusedWindows = false;
                focusIndent = -1;
            }
            if (!inFocusedWindows) continue;
            FocusNameMatch nameMatch = focusNameMatch(line, windowId, pkg, base);
            if (nameMatch == FocusNameMatch.POSSIBLE_SAME_WINDOW) {
                uncertainTargetIdentity = true;
                continue;
            }
            if (nameMatch != FocusNameMatch.EXACT) continue;
            targetSeen = true;
            List<String> displayValues = new ArrayList<>(), mDisplayValues = new ArrayList<>();
            collectFocusDisplayValues(line, displayValues);
            collectField(line, RouteDiagnostics.INLINE_MDISPLAY_ID, mDisplayValues);
            String inlineDisplay = summarizeValues(displayValues);
            String inlineMDisplay = summarizeValues(mDisplayValues);
            if ("conflict".equals(inlineDisplay) || "conflict".equals(inlineMDisplay)
                    || (inlineDisplay.startsWith("unique:") && inlineMDisplay.startsWith("unique:")
                        && !inlineDisplay.equals(inlineMDisplay))) {
                unknownTargetDisplay = true;
            } else if (inlineDisplay.startsWith("unique:")) {
                targetDisplays.add(Integer.parseInt(inlineDisplay.substring(7)));
            } else if (inlineMDisplay.startsWith("unique:")) {
                targetDisplays.add(Integer.parseInt(inlineMDisplay.substring(7)));
            } else if (segment != null) {
                targetDisplays.add(segment);
            } else {
                unknownTargetDisplay = true;
            }
        }
        if (!sectionSeen) return "unknown";
        if (uncertainTargetIdentity) return "unknown";
        if (!targetSeen) return "none";
        if (unknownTargetDisplay || targetDisplays.isEmpty()) return "unknown";
        boolean main = targetDisplays.contains(0);
        boolean rear = targetDisplays.contains(rearDisplayId);
        return main && rear ? "both" : main ? "main" : rear ? "rear" : "none";
    }

    private enum FocusNameMatch { EXACT, POSSIBLE_SAME_WINDOW, OTHER }

    private static FocusNameMatch focusNameMatch(String line, String expectedId, String pkg, String base) {
        Matcher name = RouteDiagnostics.ANY_WINDOW_NAME.matcher(line);
        if (!name.find()) return FocusNameMatch.OTHER;
        String value = name.group(1) != null ? name.group(1)
                : name.group(2) != null ? name.group(2) : name.group(3).trim();
        String[] parts = value.trim().split("\\s+", 2);
        if (parts.length == 2) {
            boolean sameId = expectedId.equals(parts[0].toLowerCase(Locale.ROOT));
            boolean sameComponent = sameComponent(parts[1], base) && parts[1].startsWith(pkg + "/");
            if (sameId && sameComponent) return FocusNameMatch.EXACT;
            if (sameId) return FocusNameMatch.POSSIBLE_SAME_WINDOW;
            return FocusNameMatch.OTHER;
        }
        if (expectedId.equals(value.trim().toLowerCase(Locale.ROOT))
                || (sameComponent(value, base) && value.startsWith(pkg + "/")))
            return FocusNameMatch.POSSIBLE_SAME_WINDOW;
        return FocusNameMatch.OTHER;
    }

    static String validateRouteProbeV1(String payload, String expectedLane) {
        if (payload == null || !("a".equals(expectedLane) || "b".equals(expectedLane))) return null;
        Matcher matcher = RouteDiagnostics.ROUTE_PROBE_V1.matcher(payload);
        if (!matcher.matches() || !expectedLane.equals(matcher.group(1))) return null;
        return "lane=" + matcher.group(1) + " service_contract=96 dispatcher_segment=" + matcher.group(2)
                + " dispatcher_displayId=" + matcher.group(3) + " dispatcher_mDisplayId=" + matcher.group(4)
                + " focus=" + matcher.group(5) + " row_count=" + matcher.group(6);
    }

    static String validatePresentationSampleV1(String payload, String expectedLane) {
        if (payload == null || !("a".equals(expectedLane) || "b".equals(expectedLane))) return null;
        Matcher matcher = RouteDiagnostics.PRESENTATION_SAMPLE_V1.matcher(payload);
        if (!matcher.matches() || !expectedLane.equals(matcher.group(1))) return null;
        try {
            long epochStart = Long.parseLong(matcher.group(4));
            long epochEnd = Long.parseLong(matcher.group(5));
            long elapsedStart = Long.parseLong(matcher.group(6));
            long elapsedEnd = Long.parseLong(matcher.group(7));
            if (epochEnd < epochStart || elapsedEnd < elapsedStart) return null;
            return "lane=" + matcher.group(1) + " window=" + matcher.group(2)
                    + " display=" + matcher.group(3) + " epoch_start_ms=" + epochStart
                    + " epoch_end_ms=" + epochEnd + " elapsed_start_ms=" + elapsedStart
                    + " elapsed_end_ms=" + elapsedEnd + " class=" + matcher.group(8);
        } catch (NumberFormatException invalid) { return null; }
    }

    static String validatePresentationLayerBindingV2(String payload, String expectedLane, int expectedTask) {
        if (payload == null || !"b".equals(expectedLane) || expectedTask <= 0) return null;
        Matcher matcher = RouteDiagnostics.PRESENTATION_LAYER_BINDING_V2.matcher(payload);
        if (!matcher.matches() || !expectedLane.equals(matcher.group(1))) return null;
        try {
            if (Integer.parseInt(matcher.group(4)) != expectedTask) return null;
            long epochStart = Long.parseLong(matcher.group(7));
            long epochEnd = Long.parseLong(matcher.group(8));
            long elapsedStart = Long.parseLong(matcher.group(9));
            long elapsedEnd = Long.parseLong(matcher.group(10));
            long uptimeStart = Long.parseLong(matcher.group(11));
            long uptimeEnd = Long.parseLong(matcher.group(12));
            if (epochEnd < epochStart || elapsedEnd < elapsedStart || uptimeEnd < uptimeStart) return null;
            return "lane=" + matcher.group(1) + " window=" + matcher.group(2)
                    + " display=" + matcher.group(3) + " task=" + matcher.group(4)
                    + " dispatcher_id=" + matcher.group(5)
                    + " owner_uid=" + matcher.group(6) + " epoch_start_ms=" + epochStart
                    + " epoch_end_ms=" + epochEnd + " elapsed_start_ms=" + elapsedStart
                    + " elapsed_end_ms=" + elapsedEnd + " uptime_start_ms=" + uptimeStart
                    + " uptime_end_ms=" + uptimeEnd + " class=" + matcher.group(13);
        } catch (NumberFormatException invalid) { return null; }
    }

    private static int indentation(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
        return index;
    }

    private static boolean sameComponent(String a, String b) {
        return canonical(a).equals(canonical(b));
    }
    private static String canonical(String component) {
        if (component == null) return "";
        String value = component.trim(); int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) return "";
        String pkg = value.substring(0, slash), cls = value.substring(slash + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        else if (!cls.contains(".")) cls = pkg + "." + cls;
        return pkg + "/" + cls;
    }
    private static boolean shownOnScreen(String block) {
        return block.contains("mIsOnScreen=true") || block.contains("isOnScreen=true");
    }
    private static boolean shownAndVisible(String block) {
        if (block.contains("isVisible=true")) return true;
        return block.contains("mPolicyVisibility=true");
    }
    private enum InputFlags {
        MISSING(false, "missing"), ALLOWED(true, "allowed"), WRAPPED_ALLOWED(false, "wrapped_allowed"),
        NOT_TOUCHABLE(false, "not_touchable"), NOT_VISIBLE(false, "not_visible"),
        PAUSE_DISPATCHING(false, "pause_dispatching"), REJECTED(false, "other_rejected");
        final boolean accepted;
        final String diagnostic;
        InputFlags(boolean accepted, String diagnostic) {
            this.accepted = accepted; this.diagnostic = diagnostic;
        }
    }

    /** Fixed AOSP InputConfig names; unknown input text is represented only as OTHER. */
    private static final String[] AOSP_INPUT_CONFIG_NAMES = {
            "DEFAULT", "NO_INPUT_CHANNEL", "NOT_VISIBLE", "NOT_FOCUSABLE", "NOT_TOUCHABLE",
            "PREVENT_SPLITTING", "DUPLICATE_TOUCH_TO_WALLPAPER", "IS_WALLPAPER",
            "PAUSE_DISPATCHING", "TRUSTED_OVERLAY", "WATCH_OUTSIDE_TOUCH", "SLIPPERY",
            "DISABLE_USER_ACTIVITY", "DROP_INPUT", "DROP_INPUT_IF_OBSCURED", "SPY",
            "INTERCEPTS_STYLUS", "CLONE", "GLOBAL_STYLUS_BLOCKS_TOUCH", "SENSITIVE_FOR_PRIVACY"
    };

    private static String inputFlagsDebug(String line, InputFlags status, String region,
            String packageName, String baseComponent) {
        Matcher config = INPUT_CONFIG.matcher(line);
        if (!config.find()) return "flags_status=" + status.diagnostic
                + ";flags_shape=empty;flags_sep=none;flags_tokens=none"
                + fixtureAdjacentFields(packageName, baseComponent, null) + ";region=" + region;
        String value = config.group(1).trim().replaceFirst(",$", "").trim();
        boolean startsBrace = value.startsWith("{");
        boolean endsBrace = value.endsWith("}");
        String shape = startsBrace && endsBrace ? "braced"
                : startsBrace || endsBrace ? "partial" : "plain";
        String normalized = startsBrace && endsBrace && value.length() >= 2
                ? value.substring(1, value.length() - 1).trim() : value;
        String sep = flagSeparator(normalized);
        String tokens;
        if (normalized.matches("(?i)0x[0-9a-f]+")) {
            String digits = normalized.substring(2);
            boolean zero = true;
            for (int i = 0; i < digits.length(); i++) if (digits.charAt(i) != '0') zero = false;
            shape = "bitset";
            tokens = zero ? "bitset_zero" : "bitset_nonzero";
        } else if (normalized.isEmpty()) {
            tokens = "none";
        } else {
            String[] parts = normalized.split("\\s*[|,]\\s*|\\s+", 5);
            StringBuilder safe = new StringBuilder();
            int emitted = 0;
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (emitted == 4) {
                    safe.append("+more");
                    break;
                }
                if (emitted > 0) safe.append('+');
                safe.append(safeFlagToken(part));
                emitted++;
            }
            tokens = emitted == 0 ? "other" : safe.toString();
        }
        return "flags_status=" + status.diagnostic + ";flags_shape=" + shape
                + ";flags_sep=" + sep + ";flags_tokens=" + tokens
                + fixtureAdjacentFields(packageName, baseComponent, config.group(1))
                + ";region=" + region;
    }

    /** Adds bounded field diagnostics only for the two installed physical test fixtures. */
    private static String fixtureAdjacentFields(String packageName, String baseComponent, String rawConfig) {
        String lane = fixtureLane(packageName, baseComponent);
        if (lane == null) return "";
        if (rawConfig == null) return ";fixture_lane=" + lane
                + ";adjacent_fields=unparsed;miuiFlags_value=unparsed";
        int comma = rawConfig.indexOf(',');
        if (comma < 0) return ";fixture_lane=" + lane + ";adjacent_fields="
                + (inputFlagsStatusValue(rawConfig) == InputFlags.ALLOWED ? "none" : "prefix_rejected")
                + ";miuiFlags_value=absent";
        String suffix = rawConfig.substring(comma);
        String prefix = rawConfig.substring(0, comma).trim();
        if (inputFlagsStatusValue(prefix) != InputFlags.ALLOWED) {
            String status = MIUI_FLAGS_MARKER.matcher(suffix).find() ? "malformed" : "absent";
            return ";fixture_lane=" + lane + ";adjacent_fields=prefix_rejected;miuiFlags_value=" + status;
        }
        if (suffix.matches(",\\s*")) return ";fixture_lane=" + lane
                + ";adjacent_fields=none;miuiFlags_value=absent";
        StringBuilder fields = new StringBuilder();
        int count = 0;
        int miuiFlagsCount = 0;
        String miuiFlagsValue = null;
        while (count < 2) {
            Matcher field = ADJACENT_CONFIG_FIELD.matcher(suffix);
            if (!field.matches()) break;
            String key = field.group(1);
            String fieldValue = field.group(2);
            if (count > 0) fields.append('|');
            fields.append(safeAdjacentKeyName(key)).append(':').append(safeFieldValueCategory(fieldValue));
            if ("miuiFlags".equals(key)) {
                miuiFlagsCount++;
                if (miuiFlagsCount == 1) miuiFlagsValue = safeMiuiFlagsValue(fieldValue);
            }
            suffix = field.group(3);
            count++;
        }
        boolean more = ADJACENT_CONFIG_FIELD.matcher(suffix).matches();
        if (count == 0) return ";fixture_lane=" + lane + ";adjacent_fields=unparsed"
                + ";miuiFlags_value=" + (MIUI_FLAGS_MARKER.matcher(rawConfig.substring(comma)).find()
                    ? "malformed" : "absent");
        if (more) fields.append("|more");
        boolean malformedTail = !suffix.isEmpty() && !more && !suffix.matches(",\\s*");
        boolean unparsedMiuiFlags = more && MIUI_FLAGS_MARKER.matcher(suffix).find();
        String miuiStatus = miuiFlagsCount > 1 || unparsedMiuiFlags
                || (miuiFlagsCount == 1 && ("malformed".equals(miuiFlagsValue) || malformedTail))
                ? "malformed" : miuiFlagsCount == 1 ? miuiFlagsValue
                : MIUI_FLAGS_MARKER.matcher(rawConfig.substring(comma)).find() ? "malformed" : "absent";
        return ";fixture_lane=" + lane + ";adjacent_fields=" + fields + ";miuiFlags_value=" + miuiStatus;
    }

    static String fixtureLane(String packageName, String baseComponent) {
        String canonicalBase = canonical(baseComponent);
        if (!("dev.remrss.sample.rc5physicala".equals(packageName)
                || "dev.remrss.sample.rc5physicalb".equals(packageName))
                || !(packageName + "/dev.remrss.physical.MainActivity").equals(canonicalBase)) return null;
        return "dev.remrss.sample.rc5physicala".equals(packageName) ? "a" : "b";
    }

    /** Keep the literal only for the exact fixture key and bounded inert value forms. */
    private static String safeMiuiFlagsValue(String rawValue) {
        String value = rawValue.trim();
        if (MIUI_FLAGS_DECIMAL.matcher(value).matches()) {
            try {
                if (Long.parseLong(value) <= Integer.MAX_VALUE) return "int:" + value;
            } catch (NumberFormatException ignored) { }
            return "malformed";
        }
        if (MIUI_FLAGS_HEX.matcher(value).matches()) return "hex:" + value;
        if (MIUI_FLAGS_ENUM.matcher(value).matches()) return "enum:" + value;
        return "malformed";
    }

    /** Exact key names are emitted only for validated adjacent fields on the two fixed debug fixtures. */
    private static String safeAdjacentKeyName(String key) {
        // ADJACENT_CONFIG_FIELD already enforces [A-Za-z][A-Za-z0-9_]{0,30}.
        // Keep this explicit guard so future callers cannot widen the diagnostic surface accidentally.
        return key.matches("[A-Za-z][A-Za-z0-9_]{0,30}") ? key : "INVALID_KEY";
    }

    private static String safeFieldValueCategory(String rawValue) {
        String value = rawValue.trim();
        if (value.isEmpty()) return "empty";
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return "boolean";
        if (value.matches("-?\\d+(?:\\.\\d+)?")) return "numeric";
        if (value.matches("(?i)0x[0-9a-f]+")) {
            for (int i = 2; i < value.length(); i++) if (value.charAt(i) != '0') return "bitset_nonzero";
            return "bitset_zero";
        }
        if (isAospInputConfigName(value)) return "enum_" + value.toLowerCase(Locale.ROOT);
        if ("BLOCK_UNTRUSTED".equals(value) || "USE_OPACITY".equals(value) || "ALLOW".equals(value))
            return "enum_" + value.toLowerCase(Locale.ROOT);
        return "OTHER";
    }

    private static String flagSeparator(String value) {
        boolean pipe = value.indexOf('|') >= 0;
        boolean comma = value.indexOf(',') >= 0;
        boolean space = hasStandaloneFlagWhitespace(value);
        if ((pipe && comma) || (space && (pipe || comma))) return "mixed";
        if (pipe) return "pipe";
        if (comma) return "comma";
        if (space) return "space";
        return value.isEmpty() ? "none" : "single";
    }

    private static boolean hasStandaloneFlagWhitespace(String value) {
        for (int i = 0; i < value.length();) {
            if (!Character.isWhitespace(value.charAt(i))) { i++; continue; }
            int left = i - 1;
            while (i < value.length() && Character.isWhitespace(value.charAt(i))) i++;
            int right = i;
            if (left >= 0 && right < value.length()
                    && value.charAt(left) != '|' && value.charAt(left) != ','
                    && value.charAt(right) != '|' && value.charAt(right) != ',') return true;
        }
        return false;
    }

    private static boolean isAospInputConfigName(String value) {
        for (String name : AOSP_INPUT_CONFIG_NAMES) if (name.equals(value)) return true;
        return false;
    }

    private static String safeFlagToken(String value) {
        if (isAospInputConfigName(value)) return value;
        if (value.matches("(?i)0x[0-9a-f]+")) {
            for (int i = 2; i < value.length(); i++) if (value.charAt(i) != '0') return "bitset_nonzero";
            return "bitset_zero";
        }
        return "OTHER";
    }

    private static InputFlags inputFlagsStatus(String line) {
        Matcher config = INPUT_CONFIG.matcher(line);
        if (!config.find()) return InputFlags.MISSING;
        return inputFlagsStatusValue(config.group(1));
    }

    private static InputFlags inputFlagsStatusValue(String rawValue) {
        String value = rawValue.trim().replaceFirst(",$", "").trim();
        // HyperOS appends this one observed OEM field directly after inputConfig.
        // Accept it only with the independently-safe standard forms; all other OEM
        // suffixes and mixed/unknown standard flags remain fail-closed.
        if (value.contains(",")) {
            if (value.matches("(?:0x0|NOT_FOCUSABLE),\\s*miuiFlags=BOOST_RT"))
                return InputFlags.ALLOWED;
            return InputFlags.REJECTED;
        }
        boolean wrapped = value.startsWith("{") && value.endsWith("}");
        String normalized = wrapped ? value.substring(1, value.length() - 1).trim() : value;
        if ("0x0".equalsIgnoreCase(normalized))
            return wrapped ? InputFlags.WRAPPED_ALLOWED : InputFlags.ALLOWED;
        String[] flags = normalized.split("\\s*\\|\\s*");
        if (flags.length == 0 || normalized.isEmpty()) return InputFlags.REJECTED;
        for (String flag : flags) {
            if ("NOT_TOUCHABLE".equals(flag)) return InputFlags.NOT_TOUCHABLE;
            if ("NOT_VISIBLE".equals(flag)) return InputFlags.NOT_VISIBLE;
            if ("PAUSE_DISPATCHING".equals(flag)) return InputFlags.PAUSE_DISPATCHING;
        }
        for (String flag : flags) {
            if (!("NOT_FOCUSABLE".equals(flag) || "PREVENT_SPLITTING".equals(flag)
                    || "DUPLICATE_TOUCH_TO_WALLPAPER".equals(flag) || "SLIPPERY".equals(flag)
                    || "DISABLE_USER_ACTIVITY".equals(flag))) return InputFlags.REJECTED;
        }
        return wrapped ? InputFlags.WRAPPED_ALLOWED : InputFlags.ALLOWED;
    }

    private enum RegionStatus {
        MISSING, EMPTY, INVALID, VALID
    }

    private static final class RegionCheck {
        final RegionStatus status;
        final String diagnostic;
        RegionCheck(RegionStatus status, String diagnostic) {
            this.status = status; this.diagnostic = diagnostic;
        }
    }

    private static RegionCheck inputRegionStatus(String line) {
        Matcher region = INPUT_REGION.matcher(line);
        if (!region.find()) return new RegionCheck(line.contains("touchableRegion=")
                ? RegionStatus.INVALID : RegionStatus.MISSING,
                line.contains("touchableRegion=") ? "invalid" : "missing");
        String value = region.group(1).trim();
        if ("<empty>".equals(value) || value.isEmpty())
            return new RegionCheck(RegionStatus.EMPTY, "empty");
        // Preserve the prior acceptance predicate: the first rectangle must have positive
        // area. Classify a canonical AOSP union without recording any coordinates.
        Matcher number = NUMBER.matcher(value);
        int[] bounds = new int[4];
        int count = 0;
        while (number.find() && count < bounds.length) bounds[count++] = parseInt(number.group(), 0);
        if (count != 4 || bounds[2] <= bounds[0] || bounds[3] <= bounds[1])
            return new RegionCheck(RegionStatus.INVALID, "invalid");
        return new RegionCheck(RegionStatus.VALID, value.contains("|") ? "multi_rect" : "single_rect");
    }
    private static boolean hasInt(String text, Pattern pattern, int expected) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() && parseInt(matcher.group(1), -1) == expected;
    }
    private static Integer firstInt(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        int value = parseInt(matcher.group(1), -1);
        return value < 0 ? null : value;
    }
    private static boolean hasExactlyOneInt(String text, Pattern pattern, int expected) {
        Matcher matcher = pattern.matcher(text); int count = 0; int actual = -1;
        while (matcher.find()) { count++; actual = parseInt(matcher.group(1), -1); }
        return count == 1 && actual == expected;
    }
    private static boolean hasPositiveInt(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text); return matcher.find() && parseInt(matcher.group(1), -1) > 0;
    }
    private static boolean hasExactlyOnePositiveInt(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text); int count = 0; int actual = -1;
        while (matcher.find()) { count++; actual = parseInt(matcher.group(1), -1); }
        return count == 1 && actual > 0;
    }
    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return fallback; }
    }
    private static Result unknown(String reason) { return new Result(false, reason, ""); }
    private static Result unknown(String reason, String debugStructure) {
        return new Result(false, reason, "", debugStructure);
    }
    private static Result unknown(String reason, String debugStructure, String routeStructure) {
        return new Result(false, reason, "", debugStructure, routeStructure);
    }
}
