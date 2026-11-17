package top.zainzh.rearscreen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhysicalTaskPresentationEvidenceTest {
    private static final String WMS = "Window #0 Window{a1 u0 b.pkg/b.pkg.Main}:\n"
            + " mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=1 fl=0x0}\n"
            + " mDisplayId=4 mTaskId=42 mOwnerUid=10042 mHasSurface=true mDrawState=HAS_DRAWN\n"
            + " mPolicyVisibility=true mHidden=false mViewVisibility=0x0 mIsOnScreen=true\n";
    private static final String INPUT = "name='a1 b.pkg/b.pkg.Main' id=2 displayId=4 inputConfig=0x0 "
            + "alpha=1.00 frame=[0,0][1080,1920] touchableRegion=[0,0][1080,1920] "
            + "ownerPid=567 ownerUid=10042 token=not-compared\n";

    @Test public void sameDrawnWindowAndTouchableDispatcherRowAreReady() {
        assertTrue(PhysicalTaskPresentationEvidence.evaluate(WMS, INPUT, 42, "b.pkg",
                "b.pkg/.Main", 4, 10042).ready);
        assertEquals(-1, PhysicalTaskPresentationEvidence.evaluate(WMS, INPUT, 42, "b.pkg",
                "b.pkg/.Main", 4, 10042).dispatcherOwnerPid);
    }

    @Test public void acceptsAospDumpFieldNamesAndDispatcherCommaShape() {
        String aospWindow = "Window #0 Window{a1 u0 b.pkg/b.pkg.Main}:\n"
                + " mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=BASE_APPLICATION fl=0x0}\n"
                + " mDisplayId=4 taskId=42 mOwnerUid=10042 mHasSurface=true mDrawState=HAS_DRAWN\n"
                + " isOnScreen=true isVisible=true\n";
        String aospInput = "name=a1 b.pkg/b.pkg.Main, id=2 displayId=4 inputConfig=0x0, "
                + "alpha=1.00 frame=[0,0][1080,1920] touchableRegion=[0,0][1080,1920] "
                + "ownerPid=567 ownerUid=10042 token=not-compared\n";
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(aospWindow,
                aospInput, 42, "b.pkg", "b.pkg/.Main", 4, 10042);
        assertTrue(result.reason, result.ready);
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(
                aospWindow.replace("taskId=42", "taskId=41"), aospInput, 42, "b.pkg",
                "b.pkg/.Main", 4, 10042).ready);
    }

    @Test public void missingOrMismatchedShownInputWindowIsUnknown() {
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(WMS, "", 42, "b.pkg",
                "b.pkg/.Main", 4, 10042).ready);
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(WMS,
                INPUT.replace("name='a1", "name='a2"), 42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(WMS,
                INPUT.replace("displayId=4", "displayId=3"), 42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(WMS,
                INPUT.replace("ownerUid=10042", "ownerUid=10043"), 42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
    }

    @Test public void onlyExactMainDisplayRowWithExpectedOwnerAndPositiveIdsIsClassifiedAsStale() {
        PhysicalTaskPresentationEvidence.Result stale = PhysicalTaskPresentationEvidence.evaluate(WMS,
                INPUT.replace("displayId=4", "displayId=0"), 42, "b.pkg", "b.pkg/.Main", 4, 10042);
        assertEquals("input_main_display_stale", stale.reason);
        assertFalse(stale.ready);
        assertEquals(-1, stale.dispatcherOwnerPid);
        assertEquals("input_display_mismatch", PhysicalTaskPresentationEvidence.evaluate(WMS,
                INPUT.replace("displayId=4", "displayId=3"), 42, "b.pkg", "b.pkg/.Main", 4, 10042).reason);
        assertEquals("input_uid_mismatch", PhysicalTaskPresentationEvidence.evaluate(WMS,
                INPUT.replace("displayId=4", "displayId=0").replace("ownerUid=10042", "ownerUid=10043"),
                42, "b.pkg", "b.pkg/.Main", 4, 10042).reason);
        assertEquals(-1, PhysicalTaskPresentationEvidence.evaluate(WMS,
                INPUT.replace("displayId=4", "displayId=0").replace("ownerUid=10042", "ownerUid=10043"),
                42, "b.pkg", "b.pkg/.Main", 4, 10042).dispatcherOwnerPid);
        assertEquals("input_id_invalid", PhysicalTaskPresentationEvidence.evaluate(WMS,
                INPUT.replace("displayId=4", "displayId=0").replace("id=2", "id=0"),
                42, "b.pkg", "b.pkg/.Main", 4, 10042).reason);
        assertEquals("input_pid_invalid", PhysicalTaskPresentationEvidence.evaluate(WMS,
                INPUT.replace("displayId=4", "displayId=0").replace("ownerPid=567", "ownerPid=0"),
                42, "b.pkg", "b.pkg/.Main", 4, 10042).reason);
    }

    @Test public void fixtureRouteProbeSeparatesMainSegmentAndInlineDisplayFromMDisplayOnFailureAndReady() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String mainDispatcher = "InputDispatcher State:\n"
                + " Display: 0\n"
                + "  FocusedWindows:\n"
                + "   displayId=0, name='f1 " + component + "'\n"
                + "  Windows:\n"
                + fixtureInput("f1", component, "mDisplayId=0 displayId=4");
        PhysicalTaskPresentationEvidence.Result main = PhysicalTaskPresentationEvidence.evaluate(
                wms, mainDispatcher, 42, pkg, component, 4, 10042, true);
        assertEquals("input_main_display_stale", main.reason);
        assertEquals(567, main.dispatcherOwnerPid);
        assertEquals(-1, PhysicalTaskPresentationEvidence.evaluate(wms, mainDispatcher, 42,
                pkg, component, 4, 10042, false).dispatcherOwnerPid);
        assertEquals("dispatcher_segment=unique:0 dispatcher_displayId=unique:4 "
                + "dispatcher_mDisplayId=unique:0 focus=main row_count=one", main.routeStructure);

        String inverseStaleDispatcher = "InputDispatcher State:\n"
                + " Display: 4\n"
                + "  FocusedWindows:\n"
                + "   displayId=4, name='f1 " + component + "'\n"
                + "  Windows:\n"
                + fixtureInput("f1", component, "displayId=0 mDisplayId=4");
        PhysicalTaskPresentationEvidence.Result inverseStale = PhysicalTaskPresentationEvidence.evaluate(
                wms, inverseStaleDispatcher, 42, pkg, component, 4, 10042, true);
        assertEquals("input_main_display_stale", inverseStale.reason);
        assertEquals("dispatcher_segment=unique:4 dispatcher_displayId=unique:0 "
                + "dispatcher_mDisplayId=unique:4 focus=rear row_count=one", inverseStale.routeStructure);

        String rearDispatcher = "InputDispatcher State:\n"
                + " Display: 4\n"
                + "  FocusedWindows:\n"
                + "   displayId=4, name='f1 " + component + "'\n"
                + "  Windows:\n"
                + fixtureInput("f1", component, "displayId=4 mDisplayId=0");
        PhysicalTaskPresentationEvidence.Result rear = PhysicalTaskPresentationEvidence.evaluate(
                wms, rearDispatcher, 42, pkg, component, 4, 10042, true);
        assertTrue(rear.reason, rear.ready);
        assertEquals("dispatcher_segment=unique:4 dispatcher_displayId=unique:4 "
                + "dispatcher_mDisplayId=unique:0 focus=rear row_count=one", rear.routeStructure);
        assertEquals("b", PhysicalTaskPresentationEvidence.fixtureLane(pkg, component));
        assertEquals("a", PhysicalTaskPresentationEvidence.fixtureLane(
                "dev.remrss.sample.rc5physicala", "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity"));
        assertEquals(null, PhysicalTaskPresentationEvidence.evaluate(
                wms, rearDispatcher, 42, pkg, component, 4, 10042, false).routeStructure);
    }

    @Test public void routeProbeClassifiesConflictsAndDoesNotCountUnrelatedRowsOrFocus() {
        String pkg = "dev.remrss.sample.rc5physicala";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String unrelated = "InputDispatcher State:\n Display: 4\n  FocusedWindows:\n"
                + "   displayId=4, name='f2 " + component + "'\n  Windows:\n"
                + fixtureInput("f2", component, "displayId=4 mDisplayId=0");
        PhysicalTaskPresentationEvidence.Result none = PhysicalTaskPresentationEvidence.evaluate(
                wms, unrelated, 42, pkg, component, 4, 10042, true);
        assertEquals("input_window_missing", none.reason);
        assertEquals("dispatcher_segment=missing dispatcher_displayId=missing "
                + "dispatcher_mDisplayId=missing focus=none row_count=none", none.routeStructure);

        String ambiguous = "InputDispatcher State:\n Display: 0\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=0 mDisplayId=4")
                + " Display: 4\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=4 mDisplayId=0");
        PhysicalTaskPresentationEvidence.Result conflict = PhysicalTaskPresentationEvidence.evaluate(
                wms, ambiguous, 42, pkg, component, 4, 10042, true);
        assertEquals("input_window_ambiguous", conflict.reason);
        assertEquals("dispatcher_segment=conflict dispatcher_displayId=conflict "
                + "dispatcher_mDisplayId=conflict focus=unknown row_count=multiple", conflict.routeStructure);
        assertFalse(conflict.routeStructure.contains(component));
        assertFalse(conflict.routeStructure.contains("f1"));
    }

    @Test public void debugMainDisplaySampleCanClassifyTheExactBWindowAgainstSelectedRearLane() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component).replace("mDisplayId=4", "mDisplayId=0");
        String input = "InputDispatcher State:\n Display: 0\n  FocusedWindows:\n"
                + "   DisplayId: 0, name='f1 " + component + "'\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=0");
        PhysicalTaskPresentationEvidence.Result main = PhysicalTaskPresentationEvidence.evaluate(
                wms, input, 42, pkg, component, 0, 10042, true, 4);
        assertEquals("ready", main.reason);
        assertEquals("dispatcher_segment=unique:0 dispatcher_displayId=unique:0 "
                + "dispatcher_mDisplayId=missing focus=main row_count=one", main.routeStructure);
        assertEquals("invalid_input", PhysicalTaskPresentationEvidence.evaluate(
                wms, input, 42, pkg, component, 0, 10042, true).reason);
        PhysicalTaskPresentationEvidence.Result wrongOwner = PhysicalTaskPresentationEvidence.evaluate(
                wms, input.replace("ownerUid=10042", "ownerUid=10043"),
                42, pkg, component, 0, 10042, true, 4);
        assertEquals("input_uid_mismatch", wrongOwner.reason);
        assertEquals(null, wrongOwner.routeStructure);
        PhysicalTaskPresentationEvidence.Result badInputId = PhysicalTaskPresentationEvidence.evaluate(
                wms, input.replace("id=2", "id=0"), 42, pkg, component, 0, 10042, true, 4);
        assertEquals("input_id_invalid", badInputId.reason);
        assertEquals(null, badInputId.routeStructure);
        PhysicalTaskPresentationEvidence.Result badOwnerPid = PhysicalTaskPresentationEvidence.evaluate(
                wms, input.replace("ownerPid=567", "ownerPid=0"), 42, pkg, component, 0, 10042, true, 4);
        assertEquals("input_pid_invalid", badOwnerPid.reason);
        assertEquals(null, badOwnerPid.routeStructure);
    }

    @Test public void staleRearSampleExposesOnlyTheExactWindowKeyAndInputDumpClockSeparatelyFromResult() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String stale = "InputDispatcher State:\n Display: 0\n  FocusedWindows:\n"
                + "   displayId=0, name='f1 " + component + "'\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=0 mDisplayId=4");
        PhysicalTaskPresentationEvidence.InputSampleClock clock =
                new PhysicalTaskPresentationEvidence.InputSampleClock(1760000000000L, 1760000000007L,
                        123456789L, 123456796L);
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                wms, stale, 42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals("input_main_display_stale", result.reason);
        assertEquals("", result.windowId);
        assertEquals("lane=b window=f1 display=4 epoch_start_ms=1760000000000 "
                + "epoch_end_ms=1760000000007 elapsed_start_ms=123456789 "
                + "elapsed_end_ms=123456796 class=input_main_display_stale", result.presentationSampleV1);
        assertEquals("lane=b window=f1 display=4 epoch_start_ms=1760000000000 "
                + "epoch_end_ms=1760000000007 elapsed_start_ms=123456789 "
                + "elapsed_end_ms=123456796 class=input_main_display_stale",
                PhysicalTaskPresentationEvidence.validatePresentationSampleV1(
                        result.presentationSampleV1, "b"));

        PhysicalTaskPresentationEvidence.Result mainProbe = PhysicalTaskPresentationEvidence.evaluate(
                wms.replace("mDisplayId=4", "mDisplayId=0"), stale, 42, pkg, component,
                0, 10042, true, 4, clock);
        assertEquals(null, mainProbe.presentationSampleV1);
        assertEquals(null, PhysicalTaskPresentationEvidence.validatePresentationSampleV1(
                result.presentationSampleV1, "a"));
        assertEquals(null, PhysicalTaskPresentationEvidence.validatePresentationSampleV1(
                result.presentationSampleV1 + " arbitrary", "b"));
    }

    @Test public void layerBindingV2JoinsExactWmsAndDispatcherRowsWithUptimeWithoutChangingV1() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String stale = "InputDispatcher State:\n Display: 0\n  FocusedWindows:\n"
                + "   displayId=0, name='f1 " + component + "'\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=0 mDisplayId=4");
        PhysicalTaskPresentationEvidence.InputSampleClock clock =
                new PhysicalTaskPresentationEvidence.InputSampleClock(1760000000000L, 1760000000007L,
                        123456789L, 123456796L, 987650L, 987657L);
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                wms, stale, 42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals("input_main_display_stale", result.reason);
        assertEquals("lane=b window=f1 display=4 epoch_start_ms=1760000000000 "
                + "epoch_end_ms=1760000000007 elapsed_start_ms=123456789 "
                + "elapsed_end_ms=123456796 class=input_main_display_stale", result.presentationSampleV1);
        String binding = "lane=b window=f1 display=4 task=42 dispatcher_id=2 owner_uid=10042 "
                + "epoch_start_ms=1760000000000 epoch_end_ms=1760000000007 "
                + "elapsed_start_ms=123456789 elapsed_end_ms=123456796 "
                + "uptime_start_ms=987650 uptime_end_ms=987657 class=input_main_display_stale";
        assertEquals(binding, result.presentationLayerBindingV2);
        assertEquals(binding, PhysicalTaskPresentationEvidence.validatePresentationLayerBindingV2(binding, "b", 42));
        assertEquals(null, PhysicalTaskPresentationEvidence.validatePresentationLayerBindingV2(binding, "a", 42));
        assertEquals(null, PhysicalTaskPresentationEvidence.validatePresentationLayerBindingV2(binding, "b", 43));
        assertEquals(null, PhysicalTaskPresentationEvidence.validatePresentationLayerBindingV2(binding + " raw", "b", 42));

        PhysicalTaskPresentationEvidence.Result noUptime = PhysicalTaskPresentationEvidence.evaluate(
                wms, stale, 42, pkg, component, 4, 10042, true, 4,
                new PhysicalTaskPresentationEvidence.InputSampleClock(1760000000000L, 1760000000007L,
                        123456789L, 123456796L));
        assertEquals(null, noUptime.presentationLayerBindingV2);
    }

    @Test public void layerBindingV2RejectsAmbiguousOrMalformedMatchedDispatcherIdentity() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String input = "InputDispatcher State:\n Display: 0\n  FocusedWindows:\n"
                + "   displayId=0, name='f1 " + component + "'\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=0 mDisplayId=4");
        PhysicalTaskPresentationEvidence.InputSampleClock clock =
                new PhysicalTaskPresentationEvidence.InputSampleClock(10L, 11L, 20L, 21L, 30L, 31L);
        assertEquals(null, PhysicalTaskPresentationEvidence.evaluate(wms,
                input + fixtureInput("f1", component, "displayId=0 mDisplayId=4"),
                42, pkg, component, 4, 10042, true, 4, clock).presentationLayerBindingV2);
        assertEquals(null, PhysicalTaskPresentationEvidence.evaluate(wms,
                input.replace("id=2", "id=2 id=3"), 42, pkg, component, 4, 10042, true, 4, clock)
                .presentationLayerBindingV2);
        assertEquals(null, PhysicalTaskPresentationEvidence.evaluate(wms,
                input.replace("ownerUid=10042", "ownerUid=10042 ownerUid=10043"),
                42, pkg, component, 4, 10042, true, 4, clock).presentationLayerBindingV2);
        assertEquals(null, PhysicalTaskPresentationEvidence.evaluate(wms, input, 42, pkg, component,
                4, 10042, true, 4,
                new PhysicalTaskPresentationEvidence.InputSampleClock(10L, 11L, 20L, 21L, 31L, 30L))
                .presentationLayerBindingV2);
        assertEquals(null, PhysicalTaskPresentationEvidence.validatePresentationLayerBindingV2(
                "lane=b window=f1 display=4 task=42 dispatcher_id=0 owner_uid=10042 epoch_start_ms=1 "
                        + "epoch_end_ms=2 elapsed_start_ms=3 elapsed_end_ms=4 uptime_start_ms=5 "
                        + "uptime_end_ms=6 class=ready", "b", 42));
    }

    @Test public void ambiguousOrMismatchedWmsInputRowsNeverExposeTheSampleWindowKey() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String input = "InputDispatcher State:\n Display: 0\n  FocusedWindows:\n"
                + "   displayId=0, name='f1 " + component + "'\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=0 mDisplayId=4");
        PhysicalTaskPresentationEvidence.InputSampleClock clock =
                new PhysicalTaskPresentationEvidence.InputSampleClock(10L, 11L, 20L, 21L);

        PhysicalTaskPresentationEvidence.Result ambiguousWms = PhysicalTaskPresentationEvidence.evaluate(
                wms + fixtureWms("f2", pkg, component), input, 42, pkg, component, 4,
                10042, true, 4, clock);
        assertEquals("window_task_ambiguous", ambiguousWms.reason);
        assertEquals(null, ambiguousWms.presentationSampleV1);

        PhysicalTaskPresentationEvidence.Result differentWindowKey = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms("f2", pkg, component), input, 42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals("input_window_missing", differentWindowKey.reason);
        assertEquals(null, differentWindowKey.presentationSampleV1);

        PhysicalTaskPresentationEvidence.Result ambiguousInput = PhysicalTaskPresentationEvidence.evaluate(
                wms, input + fixtureInput("f1", component, "displayId=0 mDisplayId=4"),
                42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals("input_window_ambiguous", ambiguousInput.reason);
        assertEquals(null, ambiguousInput.presentationSampleV1);

        PhysicalTaskPresentationEvidence.Result wrongOwner = PhysicalTaskPresentationEvidence.evaluate(
                wms, input.replace("ownerUid=10042", "ownerUid=10043"), 42, pkg, component,
                4, 10042, true, 4, clock);
        assertEquals("input_uid_mismatch", wrongOwner.reason);
        assertEquals(null, wrongOwner.presentationSampleV1);

        PhysicalTaskPresentationEvidence.Result badId = PhysicalTaskPresentationEvidence.evaluate(
                wms, input.replace("id=2", "id=0"), 42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals(null, badId.presentationSampleV1);
        PhysicalTaskPresentationEvidence.Result badPid = PhysicalTaskPresentationEvidence.evaluate(
                wms, input.replace("ownerPid=567", "ownerPid=0"), 42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals(null, badPid.presentationSampleV1);

        PhysicalTaskPresentationEvidence.Result duplicateUid = PhysicalTaskPresentationEvidence.evaluate(
                wms, input.replace("ownerUid=10042", "ownerUid=10042 ownerUid=10043"),
                42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals(null, duplicateUid.presentationSampleV1);
        PhysicalTaskPresentationEvidence.Result duplicateId = PhysicalTaskPresentationEvidence.evaluate(
                wms, input.replace("id=2", "id=2 id=3"), 42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals(null, duplicateId.presentationSampleV1);

        PhysicalTaskPresentationEvidence.Result duplicateWmsDisplay = PhysicalTaskPresentationEvidence.evaluate(
                wms.replace("mDisplayId=4", "mDisplayId=4 displayId=3"), input,
                42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals("input_main_display_stale", duplicateWmsDisplay.reason);
        assertEquals(null, duplicateWmsDisplay.presentationSampleV1);
        PhysicalTaskPresentationEvidence.Result duplicateWmsUid = PhysicalTaskPresentationEvidence.evaluate(
                wms.replace("mOwnerUid=10042", "mOwnerUid=10042 mOwnerUid=10043"), input,
                42, pkg, component, 4, 10042, true, 4, clock);
        assertEquals("input_main_display_stale", duplicateWmsUid.reason);
        assertEquals(null, duplicateWmsUid.presentationSampleV1);
    }

    @Test public void routeProbeUsesOnlyKnownFocusScopesAndIsAbsentForNonFixturePackages() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String noFocusScope = "InputDispatcher State:\n Display: 4\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=4 mDisplayId=4");
        PhysicalTaskPresentationEvidence.Result unknown = PhysicalTaskPresentationEvidence.evaluate(
                wms, noFocusScope, 42, pkg, component, 4, 10042, true);
        assertTrue(unknown.ready);
        assertEquals("unknown", unknown.routeStructure.substring(unknown.routeStructure.indexOf("focus=") + 6,
                unknown.routeStructure.indexOf(" row_count=")));

        String nonFixturePackage = "dev.example.app";
        String nonFixtureComponent = nonFixturePackage + "/dev.remrss.physical.MainActivity";
        PhysicalTaskPresentationEvidence.Result nonFixture = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms("f1", nonFixturePackage, nonFixtureComponent),
                noFocusScope.replace(pkg, nonFixturePackage), 42, nonFixturePackage,
                nonFixtureComponent, 4, 10042, true);
        assertEquals(null, nonFixture.routeStructure);
    }

    @Test public void routeProbeKeepsMissingDispatcherSourcesIndependent() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String noSegment = "InputDispatcher State:\n Windows:\n"
                + fixtureInput("f1", component, "displayId=4 mDisplayId=4");
        PhysicalTaskPresentationEvidence.Result segmentMissing = PhysicalTaskPresentationEvidence.evaluate(
                wms, noSegment, 42, pkg, component, 4, 10042, true);
        assertTrue(segmentMissing.ready);
        assertEquals("dispatcher_segment=missing dispatcher_displayId=unique:4 "
                + "dispatcher_mDisplayId=unique:4 focus=unknown row_count=one", segmentMissing.routeStructure);

        String noMDisplay = "InputDispatcher State:\n Display: 4\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=4");
        PhysicalTaskPresentationEvidence.Result mDisplayMissing = PhysicalTaskPresentationEvidence.evaluate(
                wms, noMDisplay, 42, pkg, component, 4, 10042, true);
        assertTrue(mDisplayMissing.ready);
        assertEquals("dispatcher_segment=unique:4 dispatcher_displayId=unique:4 "
                + "dispatcher_mDisplayId=missing focus=unknown row_count=one", mDisplayMissing.routeStructure);

        String displayWindow = "Input Dispatcher State:\n Display: 4\n  Windows:\n"
                + "   0: name='f1 " + component + "', displayId=4, hasFocus=true\n";
        PhysicalTaskPresentationEvidence.Result segmentRow = PhysicalTaskPresentationEvidence.evaluate(
                wms, displayWindow, 42, pkg, component, 4, 10042, true);
        assertEquals("input_window_missing", segmentRow.reason);
        assertEquals("dispatcher_segment=unique:4 dispatcher_displayId=unique:4 "
                + "dispatcher_mDisplayId=missing focus=unknown row_count=one", segmentRow.routeStructure);
    }

    @Test public void routeProbeReportsBothDisplayFocusWithoutLeakingMatchingWindowIdentity() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String dispatcher = "InputDispatcher State:\n Display: 4\n  FocusedWindows:\n"
                + "   displayId=0, name='f1 " + component + "'\n"
                + "   displayId=4, name='f1 " + component + "'\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=4 mDisplayId=0");
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                wms, dispatcher, 42, pkg, component, 4, 10042, true);
        assertTrue(result.ready);
        assertTrue(result.routeStructure.contains("focus=both row_count=one"));
        assertFalse(result.routeStructure.contains(pkg));
        assertFalse(result.routeStructure.contains("f1"));
    }

    @Test public void routeProbeRequiresFullWindowIdentityAndUnambiguousFocusDisplays() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        String wrongComponent = "InputDispatcher State:\n Display: 0\n  FocusedWindows:\n"
                + "   displayId=0, name='f1 " + pkg + "/dev.remrss.physical.OtherActivity'\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=4 mDisplayId=0");
        assertTrue(PhysicalTaskPresentationEvidence.evaluate(wms, wrongComponent, 42, pkg,
                component, 4, 10042, true).routeStructure.contains("focus=unknown"));

        String noId = "InputDispatcher State:\n Display: 0\n  FocusedWindows:\n"
                + "   displayId=0, name='" + component + "'\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=4 mDisplayId=0");
        assertTrue(PhysicalTaskPresentationEvidence.evaluate(wms, noId, 42, pkg,
                component, 4, 10042, true).routeStructure.contains("focus=unknown"));

        String conflictingFocus = "InputDispatcher State:\n Display: 4\n  FocusedWindows:\n"
                + "   displayId=0 mDisplayId=4, name='f1 " + component + "'\n  Windows:\n"
                + fixtureInput("f1", component, "displayId=4 mDisplayId=0");
        assertTrue(PhysicalTaskPresentationEvidence.evaluate(wms, conflictingFocus, 42, pkg,
                component, 4, 10042, true).routeStructure.contains("focus=unknown"));
    }

    @Test public void routeProbeAcceptsOemDisplayAliasesAndStopsAtFocusScopeBoundary() {
        String pkg = "dev.remrss.sample.rc5physicalb";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = fixtureWms("f1", pkg, component);
        for (String header : new String[]{"DisplayId: 4", "DisplayId=4", "Display=4"}) {
            String dispatcher = "InputDispatcher State:\n " + header + "\n  FocusedWindows:\n"
                    + "   DisplayId: 4 name='f1 " + component + "'\n  Windows:\n"
                    + fixtureInput("f1", component, "displayId=4 mDisplayId=4");
            PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                    wms, dispatcher, 42, pkg, component, 4, 10042, true);
            assertTrue(header, result.routeStructure.contains("dispatcher_segment=unique:4"));
            assertTrue(header, result.routeStructure.contains("focus=rear"));
        }
        String endedScope = "InputDispatcher State:\n Display: 0\n  FocusedWindows:\n"
                + "  Windows:\n   name='f1 " + component + "' displayId=4 mDisplayId=4\n"
                + fixtureInput("f1", component, "displayId=4 mDisplayId=4");
        assertTrue(PhysicalTaskPresentationEvidence.evaluate(wms, endedScope, 42, pkg,
                component, 4, 10042, true).routeStructure.contains("focus=none"));
    }

    @Test public void routeProbeWireValidatorAcceptsOnlyCanonicalFixedStructure() {
        String good = "lane=b service_contract=96 dispatcher_segment=unique:4 dispatcher_displayId=unique:0 "
                + "dispatcher_mDisplayId=unique:4 focus=unknown row_count=one";
        assertEquals(good, PhysicalTaskPresentationEvidence.validateRouteProbeV1(good, "b"));
        assertEquals(null, PhysicalTaskPresentationEvidence.validateRouteProbeV1(good, "a"));
        assertEquals(null, PhysicalTaskPresentationEvidence.validateRouteProbeV1(good + " raw", "b"));
        assertEquals(null, PhysicalTaskPresentationEvidence.validateRouteProbeV1(
                good.replace("service_contract=96", "service_contract=95"), "b"));
    }

    private static String fixtureWms(String id, String pkg, String component) {
        return "Window #0 Window{" + id + " u0 " + component + "}:\n"
                + " mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=BASE_APPLICATION fl=0x0}\n"
                + " mDisplayId=4 mTaskId=42 mOwnerUid=10042 mHasSurface=true mDrawState=HAS_DRAWN\n"
                + " isOnScreen=true isVisible=true\n";
    }

    private static String fixtureInput(String id, String component, String displayFields) {
        return "   0: name=" + id + " " + component + ", id=2 " + displayFields
                + " inputConfig=0x0 alpha=1.00 frame=[0,0][10,10] touchableRegion=[0,0][10,10]"
                + " ownerPid=567 ownerUid=10042 token=private\n";
    }

    @Test public void invalidInputIdentityFieldsHaveDistinctFixedReasons() {
        assertReason("input_display_missing", INPUT.replace("displayId=4 ", ""));
        assertReason("input_display_mismatch", INPUT.replace("displayId=4", "displayId=3"));
        assertReason("input_uid_missing", INPUT.replace("ownerUid=10042", ""));
        assertReason("input_uid_mismatch", INPUT.replace("ownerUid=10042", "ownerUid=10043"));
        assertReason("input_id_invalid", INPUT.replace("id=2", "id=0"));
        assertReason("input_window_missing", INPUT.replace("id=2 ", ""));
        assertReason("input_pid_invalid", INPUT.replace("ownerPid=567", "ownerPid=0"));
        assertReason("input_pid_invalid", INPUT.replace("ownerPid=567 ", ""));
    }

    private static void assertReason(String expected, String input) {
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                WMS, input, 42, "b.pkg", "b.pkg/.Main", 4, 10042);
        assertFalse(result.ready);
        assertEquals(expected, result.reason);
    }

    @Test public void drawnWindowMustBelongToExactCandidateTaskNotSameComponentA() {
        String a = WMS.replace("Window #0 Window{a1", "Window #0 Window{a0")
                .replace("mTaskId=42", "mTaskId=41");
        String b = WMS.replace("Window #0 Window{a1", "Window #1 Window{b2");
        String both = a + b;
        String inputA = INPUT.replace("a1", "a0");
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(a,
                inputA, 42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
        assertTrue(PhysicalTaskPresentationEvidence.evaluate(both,
                inputA + INPUT.replace("a1", "b2"), 42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(both,
                inputA, 42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(
                WMS.replace("mTaskId=42", "mTaskId=42 mTaskId=41"), INPUT,
                42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
    }

    @Test public void startingOverlayHiddenUntouchableAndEmptyRegionFailClosed() {
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(WMS.replace("HAS_DRAWN", "READY_TO_SHOW"), INPUT,
                42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(WMS.replace("ty=1", "ty=3"), INPUT,
                42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(WMS, INPUT.replace("inputConfig=0x0", "inputConfig=NOT_TOUCHABLE"),
                42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
        assertFalse(PhysicalTaskPresentationEvidence.evaluate(WMS, INPUT.replace("[0,0][1080,1920] ownerPid", "[0,0][0,0] ownerPid"),
                42, "b.pkg", "b.pkg/.Main", 4, 10042).ready);
    }

    @Test public void aospCommaSerializedNotFocusableWindowAndRegionRemainAccepted() {
        String aospInput = "name=a1 b.pkg/b.pkg.Main, id=2, displayId=4, inputConfig=NOT_FOCUSABLE, "
                + "alpha=1.00, frame=[0,0][1080,1920], applicationInfo.token=opaque, "
                + "touchableRegion=[0,0][1080,1920], ownerPid=567, ownerUid=10042, hasToken=true\n";
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                WMS, aospInput, 42, "b.pkg", "b.pkg/.Main", 4, 10042);
        assertTrue(result.reason, result.ready);
    }

    @Test public void flagsAndRegionRejectWithDistinctFixedReasonsAndSafeStructureOnly() {
        assertDebug("input_flags_missing", "flags_status=missing;flags_shape=empty;flags_sep=none;flags_tokens=none;region=not_checked",
                INPUT.replace("inputConfig=0x0 ", ""));
        assertDebug("input_flags_rejected", "flags_status=not_touchable;flags_shape=plain;flags_sep=single;flags_tokens=NOT_TOUCHABLE;region=not_checked",
                INPUT.replace("inputConfig=0x0", "inputConfig=NOT_TOUCHABLE"));
        assertDebug("input_flags_rejected", "flags_status=not_visible;flags_shape=plain;flags_sep=single;flags_tokens=NOT_VISIBLE;region=not_checked",
                INPUT.replace("inputConfig=0x0", "inputConfig=NOT_VISIBLE"));
        assertDebug("input_flags_rejected", "flags_status=pause_dispatching;flags_shape=plain;flags_sep=single;flags_tokens=PAUSE_DISPATCHING;region=not_checked",
                INPUT.replace("inputConfig=0x0", "inputConfig=PAUSE_DISPATCHING"));
        assertDebug("input_flags_rejected", "flags_status=other_rejected;flags_shape=plain;flags_sep=single;flags_tokens=OTHER;region=not_checked",
                INPUT.replace("inputConfig=0x0", "inputConfig=UNKNOWN_FLAG"));
        // Braced flag syntax remains rejected until an actual OEM sample proves this form.
        assertDebug("input_flags_rejected", "flags_status=wrapped_allowed;flags_shape=braced;flags_sep=single;flags_tokens=NOT_FOCUSABLE;region=not_checked",
                INPUT.replace("inputConfig=0x0", "inputConfig={NOT_FOCUSABLE}"));
        assertDebug("input_flags_rejected", "flags_status=other_rejected;flags_shape=plain;flags_sep=pipe;flags_tokens=NOT_FOCUSABLE+TRUSTED_OVERLAY;region=not_checked",
                INPUT.replace("inputConfig=0x0", "inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY"));
        assertDebug("input_flags_rejected", "flags_status=other_rejected;flags_shape=plain;flags_sep=comma;flags_tokens=NOT_FOCUSABLE+IS_WALLPAPER;region=not_checked",
                INPUT.replace("inputConfig=0x0", "inputConfig=NOT_FOCUSABLE, IS_WALLPAPER"));
        assertDebug("input_flags_rejected", "flags_status=other_rejected;flags_shape=bitset;flags_sep=single;flags_tokens=bitset_nonzero;region=not_checked",
                INPUT.replace("inputConfig=0x0", "inputConfig=0x3f"));
        PhysicalTaskPresentationEvidence.Result unknownFlag = PhysicalTaskPresentationEvidence.evaluate(
                WMS, INPUT.replace("inputConfig=0x0", "inputConfig=private.secret.value"), 42,
                "b.pkg", "b.pkg/.Main", 4, 10042, true);
        assertEquals("flags_status=other_rejected;flags_shape=plain;flags_sep=single;flags_tokens=OTHER;region=not_checked",
                unknownFlag.debugStructure);
        assertFalse(unknownFlag.debugStructure.contains("private.secret.value"));
        PhysicalTaskPresentationEvidence.Result releaseUnknown = PhysicalTaskPresentationEvidence.evaluate(
                WMS, INPUT.replace("inputConfig=0x0", "inputConfig=private.secret.value"), 42,
                "b.pkg", "b.pkg/.Main", 4, 10042, false);
        assertFalse(releaseUnknown.ready);
        assertEquals("input_flags_rejected", releaseUnknown.reason);
        assertEquals(null, releaseUnknown.debugStructure);

        assertDebug("input_region_missing", "flags_status=allowed;flags_shape=bitset;flags_sep=single;flags_tokens=bitset_zero;region=missing",
                INPUT.replace("touchableRegion=[0,0][1080,1920] ", ""));
        assertDebug("input_region_empty", "flags_status=allowed;flags_shape=bitset;flags_sep=single;flags_tokens=bitset_zero;region=empty",
                INPUT.replace("touchableRegion=[0,0][1080,1920]", "touchableRegion=<empty>"));
        assertDebug("input_region_invalid", "flags_status=allowed;flags_shape=bitset;flags_sep=single;flags_tokens=bitset_zero;region=invalid",
                INPUT.replace("touchableRegion=[0,0][1080,1920]", "touchableRegion=[0,0][0,0]"));
        assertDebug("input_region_invalid", "flags_status=allowed;flags_shape=bitset;flags_sep=single;flags_tokens=bitset_zero;region=invalid",
                INPUT.replace("touchableRegion=[0,0][1080,1920]", "touchableRegion=unknown"));
        assertDebug("input_region_invalid", "flags_status=allowed;flags_shape=bitset;flags_sep=single;flags_tokens=bitset_zero;region=invalid",
                INPUT.replace("] ownerPid=567", "],ownerPid=567"));
        // The old strict reader required ownerPid after the region. Do not absorb later
        // numeric fields into a synthetic rectangle when that delimiter is absent.
        assertDebug("input_region_invalid", "flags_status=allowed;flags_shape=bitset;flags_sep=single;flags_tokens=bitset_zero;region=invalid",
                INPUT.replace(" touchableRegion=[0,0][1080,1920] ownerPid=567",
                        " ownerPid=567 touchableRegion=[0,0][1080,1920] extraBounds=[1,2][900,1000]"));
    }

    @Test public void canonicalMultiRectangleRegionHasSafeShapeDiagnostic() {
        String input = INPUT.replace("touchableRegion=[0,0][1080,1920]",
                "touchableRegion=[0,0][20,20]|[30,30][50,50]");
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                WMS, input, 42, "b.pkg", "b.pkg/.Main", 4, 10042);
        assertTrue(result.reason, result.ready);
        assertEquals(null, result.debugStructure);
    }

    @Test public void fixtureOnlyDiagnosticCapturesAdjacentFieldKeysAndSafeValueCategories() {
        String packageA = "dev.remrss.sample.rc5physicala";
        String componentA = packageA + "/dev.remrss.physical.MainActivity";
        String fixtureWms = "Window #0 Window{f1 u0 " + componentA + "}:\n"
                + " mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=BASE_APPLICATION fl=0x0}\n"
                + " mDisplayId=4 mTaskId=42 mOwnerUid=10042 mHasSurface=true mDrawState=HAS_DRAWN\n"
                + " isOnScreen=true isVisible=true\n";
        String fixtureInput = "name=f1 " + componentA + ", id=2 displayId=4 "
                + "inputConfig=0x0, touchOcclusionMode=BLOCK_UNTRUSTED, alpha=1.00 "
                + "frame=[0,0][1080,1920] touchableRegion=[0,0][1080,1920] "
                + "ownerPid=567 ownerUid=10042 token=not-compared\n";
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms, fixtureInput, 42, packageA, componentA, 4, 10042, true);
        assertFalse(result.ready);
        assertEquals("input_flags_rejected", result.reason);
        assertEquals("flags_status=other_rejected;flags_shape=plain;flags_sep=comma;"
                + "flags_tokens=bitset_zero+OTHER;fixture_lane=a;adjacent_fields=touchOcclusionMode:enum_block_untrusted;"
                + "miuiFlags_value=absent;region=not_checked", result.debugStructure);
        assertFalse(result.debugStructure.contains("not-compared"));
        assertFalse(result.debugStructure.contains("567"));

        String commaFlag = fixtureInput.replace("inputConfig=0x0, touchOcclusionMode=BLOCK_UNTRUSTED",
                "inputConfig=NOT_FOCUSABLE, NOT_TOUCHABLE");
        PhysicalTaskPresentationEvidence.Result commaFlagResult = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms, commaFlag, 42, packageA, componentA, 4, 10042, true);
        assertEquals("input_flags_rejected", commaFlagResult.reason);
        assertTrue(commaFlagResult.debugStructure.contains("flags_tokens=NOT_FOCUSABLE+NOT_TOUCHABLE"));
        assertTrue(commaFlagResult.debugStructure.contains("fixture_lane=a;adjacent_fields=unparsed;miuiFlags_value=absent"));

        String noAdjacentField = fixtureInput
                .replace("inputConfig=0x0, touchOcclusionMode=BLOCK_UNTRUSTED, alpha=", "inputConfig=0x0, alpha=")
                .replace("touchableRegion=[0,0][1080,1920]", "touchableRegion=<empty>");
        PhysicalTaskPresentationEvidence.Result noAdjacent = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms, noAdjacentField, 42, packageA, componentA, 4, 10042, true);
        assertEquals("flags_status=allowed;flags_shape=bitset;flags_sep=single;flags_tokens=bitset_zero;"
                + "fixture_lane=a;adjacent_fields=none;miuiFlags_value=absent;region=empty", noAdjacent.debugStructure);

        String flagsA = fixtureInput.replace("inputConfig=0x0", "inputConfig=NOT_FOCUSABLE")
                .replace("BLOCK_UNTRUSTED", "ALLOW");
        PhysicalTaskPresentationEvidence.Result resultA = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms, flagsA, 42, packageA, componentA, 4, 10042, true);
        assertEquals("flags_status=other_rejected;flags_shape=plain;flags_sep=comma;"
                + "flags_tokens=NOT_FOCUSABLE+OTHER;fixture_lane=a;adjacent_fields=touchOcclusionMode:enum_allow;"
                + "miuiFlags_value=absent;region=not_checked", resultA.debugStructure);

        String unknownField = fixtureInput.replace("touchOcclusionMode=BLOCK_UNTRUSTED", "mSecretField=private.value");
        PhysicalTaskPresentationEvidence.Result resultUnknown = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms, unknownField, 42, packageA, componentA, 4, 10042, true);
        assertTrue(resultUnknown.debugStructure.contains("adjacent_fields=mSecretField:OTHER"));
        assertFalse(resultUnknown.debugStructure.contains("private.value"));
        assertTrue(resultUnknown.debugStructure.contains("miuiFlags_value=absent"));

        String nonFixtureWms = fixtureWms.replace(packageA, "dev.example.app");
        String nonFixtureInput = fixtureInput.replace(packageA, "dev.example.app");
        PhysicalTaskPresentationEvidence.Result nonFixture = PhysicalTaskPresentationEvidence.evaluate(
                nonFixtureWms, nonFixtureInput, 42, "dev.example.app",
                "dev.example.app/dev.remrss.physical.MainActivity", 4, 10042, true);
        assertFalse(nonFixture.ready);
        assertFalse(nonFixture.debugStructure.contains("adjacent_fields="));
    }

    @Test public void fixtureDiagnosticNamesOnlyValidatedKeyAndNeverItsValue() {
        String fixtureWms = WMS.replace("b.pkg/b.pkg.Main", "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity");
        String fixtureInput = INPUT.replace("b.pkg/b.pkg.Main", "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity")
                .replace("inputConfig=0x0", "inputConfig=NOT_FOCUSABLE, mOemInputPolicy=opaque_private_value");
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms, fixtureInput, 42, "dev.remrss.sample.rc5physicala",
                "dev.remrss.sample.rc5physicala/dev.remrss.physical.MainActivity", 4, 10042, true);
        assertTrue(result.debugStructure.contains("fixture_lane=a;adjacent_fields=mOemInputPolicy:OTHER"));
        assertTrue(result.debugStructure.contains("miuiFlags_value=absent"));
        assertFalse(result.debugStructure.contains("opaque_private_value"));
    }

    @Test public void fixtureMiuiFlagsDiagnosticEmitsOnlyBoundedCanonicalValues() {
        String packageA = "dev.remrss.sample.rc5physicala";
        String componentA = packageA + "/dev.remrss.physical.MainActivity";
        String fixtureWms = "Window #0 Window{f1 u0 " + componentA + "}:\n"
                + " mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=BASE_APPLICATION fl=0x0}\n"
                + " mDisplayId=4 mTaskId=42 mOwnerUid=10042 mHasSurface=true mDrawState=HAS_DRAWN\n"
                + " isOnScreen=true isVisible=true\n";
        String fixtureInput = "name=f1 " + componentA + ", id=2 displayId=4 inputConfig=";
        assertMiuiDiagnostic(fixtureWms, fixtureInput + "NOT_FOCUSABLE, miuiFlags=12, alpha=1.00", packageA, componentA,
                "miuiFlags_value=int:12");
        assertMiuiDiagnostic(fixtureWms, fixtureInput + "NOT_FOCUSABLE, miuiFlags=0xff, alpha=1.00", packageA, componentA,
                "miuiFlags_value=hex:0xff");
        assertMiuiDiagnostic(fixtureWms, fixtureInput + "NOT_FOCUSABLE, miuiFlags=FLAG_ONE, alpha=1.00", packageA, componentA,
                "miuiFlags_value=enum:FLAG_ONE");
        // The exact BOOST_RT suffix is covered by the narrow admission test below.
        PhysicalTaskPresentationEvidence.Result standardNotTouchable =
                PhysicalTaskPresentationEvidence.evaluate(fixtureWms,
                        fixtureInput + "NOT_TOUCHABLE, alpha=1.00"
                                + " frame=[0,0][1080,1920] touchableRegion=[0,0][1080,1920]"
                                + " ownerPid=567 ownerUid=10042 token=not-compared\n",
                        42, packageA, componentA, 4, 10042, true);
        assertEquals("input_flags_rejected", standardNotTouchable.reason);
        assertTrue(standardNotTouchable.debugStructure,
                standardNotTouchable.debugStructure.startsWith("flags_status=not_touchable;"));
        String packageB = "dev.remrss.sample.rc5physicalb";
        String componentB = packageB + "/dev.remrss.physical.MainActivity";
        String fixtureWmsB = fixtureWms.replace(packageA, packageB);
        assertMiuiDiagnostic(fixtureWmsB,
                fixtureInput.replace(packageA, packageB).replace(componentA, componentB)
                        + "NOT_FOCUSABLE, miuiFlags=0x2, alpha=1.00",
                packageB, componentB, "miuiFlags_value=hex:0x2");
        assertMiuiDiagnostic(fixtureWms, fixtureInput + "NOT_FOCUSABLE, miuiFlags=-1, alpha=1.00", packageA, componentA,
                "miuiFlags_value=malformed");
        assertMiuiDiagnostic(fixtureWms, fixtureInput + "NOT_FOCUSABLE, miuiFlags=private value, alpha=1.00", packageA, componentA,
                "miuiFlags_value=malformed");
        assertMiuiDiagnostic(fixtureWms, fixtureInput + "NOT_FOCUSABLE, miuiFlags=12,, alpha=1.00", packageA, componentA,
                "miuiFlags_value=malformed");
        assertMiuiDiagnostic(fixtureWms, fixtureInput + "NOT_FOCUSABLE, miuiFlags, alpha=1.00", packageA, componentA,
                "miuiFlags_value=malformed");
        assertMiuiDiagnostic(fixtureWms, fixtureInput + "NOT_FOCUSABLE, miuiFlags=12, miuiFlags=13, alpha=1.00",
                packageA, componentA, "miuiFlags_value=malformed");
        assertMiuiDiagnostic(fixtureWms, fixtureInput + "NOT_FOCUSABLE, miuiFlags=12, other=1, miuiFlags=13, alpha=1.00",
                packageA, componentA, "miuiFlags_value=malformed");

        PhysicalTaskPresentationEvidence.Result missingConfig = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms, fixtureInput.replace(" inputConfig=", " ")
                        + "alpha=1.00 frame=[0,0][1080,1920] touchableRegion=[0,0][1080,1920]"
                        + " ownerPid=567 ownerUid=10042 token=not-compared\n",
                42, packageA, componentA, 4, 10042, true);
        assertEquals("input_flags_missing", missingConfig.reason);
        assertTrue(missingConfig.debugStructure.contains(
                "fixture_lane=a;adjacent_fields=unparsed;miuiFlags_value=unparsed;region=not_checked"));
    }

    @Test public void exactBoostRtSuffixDoesNotReplaceStandardInputFlagValidation() {
        String pkg = "dev.remrss.sample.rc5physicala";
        String component = pkg + "/dev.remrss.physical.MainActivity";
        String wms = "Window #0 Window{f1 u0 " + component + "}:\n"
                + " mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=BASE_APPLICATION fl=0x0}\n"
                + " mDisplayId=4 mTaskId=42 mOwnerUid=10042 mHasSurface=true mDrawState=HAS_DRAWN\n"
                + " isOnScreen=true isVisible=true\n";
        String input = "name=f1 " + component + ", id=2 displayId=4 inputConfig=";
        String tail = " frame=[0,0][1080,1920] touchableRegion=[0,0][1080,1920]"
                + " ownerPid=567 ownerUid=10042 token=not-compared\n";
        for (String standard : new String[] { "0x0", "NOT_FOCUSABLE" }) {
            assertTrue("standard=" + standard, PhysicalTaskPresentationEvidence.evaluate(
                    wms, input + standard + ", miuiFlags=BOOST_RT," + tail,
                    42, pkg, component, 4, 10042, false).ready);
        }
        for (String rejected : new String[] {
                "0x0, miuiFlags=OTHER", "0x0, miuiFlags=BOOST_RT, miuiFlags=BOOST_RT",
                "0x0, miuiFlags=BOOST_RT, other=1", "NOT_TOUCHABLE, miuiFlags=BOOST_RT",
                "UNKNOWN_FLAG, miuiFlags=BOOST_RT", "{NOT_FOCUSABLE, miuiFlags=BOOST_RT",
                "NOT_FOCUSABLE}, miuiFlags=BOOST_RT", "NOT_FOCUSABLE | TRUSTED_OVERLAY, miuiFlags=BOOST_RT"
        }) {
            assertFalse("must reject: " + rejected, PhysicalTaskPresentationEvidence.evaluate(
                    wms, input + rejected + tail, 42, pkg, component, 4, 10042, false).ready);
        }
    }

    private static void assertMiuiDiagnostic(String wms, String input, String packageName,
            String component, String expectedDiagnostic) {
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                wms, input + " frame=[0,0][1080,1920] touchableRegion=[0,0][1080,1920]"
                        + " ownerPid=567 ownerUid=10042 token=not-compared\n",
                42, packageName, component, 4, 10042, true);
        assertEquals("input_flags_rejected", result.reason);
        String expectedLane = packageName.endsWith("physicala") ? "a" : "b";
        assertTrue(result.debugStructure, result.debugStructure.contains("fixture_lane=" + expectedLane + ";"));
        assertTrue(result.debugStructure, result.debugStructure.contains(expectedDiagnostic));
        assertFalse(result.debugStructure.contains("private value"));
        if (expectedDiagnostic.endsWith("malformed")) {
            assertFalse(result.debugStructure.contains("miuiFlags_value=int:"));
            assertFalse(result.debugStructure.contains("miuiFlags_value=hex:"));
            assertFalse(result.debugStructure.contains("miuiFlags_value=enum:"));
        }
    }

    @Test public void fixtureDiagnosticBoundsAdjacentFieldCountAndRefusesUnknownPrefix() {
        String packageB = "dev.remrss.sample.rc5physicalb";
        String componentB = packageB + "/dev.remrss.physical.MainActivity";
        String fixtureWms = "Window #0 Window{f1 u0 " + componentB + "}:\n"
                + " mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=BASE_APPLICATION fl=0x0}\n"
                + " mDisplayId=4 mTaskId=42 mOwnerUid=10042 mHasSurface=true mDrawState=HAS_DRAWN\n"
                + " isOnScreen=true isVisible=true\n";
        String fixtureInput = "name=f1 " + componentB + ", id=2 displayId=4 "
                + "inputConfig=0x0, first=1, second=true, third=NOT_FOCUSABLE, alpha=1.00 "
                + "frame=[0,0][1080,1920] touchableRegion=[0,0][1080,1920] "
                + "ownerPid=567 ownerUid=10042 token=not-compared\n";
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms, fixtureInput, 42, packageB, componentB, 4, 10042, true);
        assertTrue(result.debugStructure.contains("fixture_lane=b;adjacent_fields=first:numeric|second:boolean|more"));
        assertFalse(result.debugStructure.contains("third"));

        String unknownPrefix = fixtureInput.replace("inputConfig=0x0", "inputConfig=UNKNOWN_FLAG");
        PhysicalTaskPresentationEvidence.Result refused = PhysicalTaskPresentationEvidence.evaluate(
                fixtureWms, unknownPrefix, 42, packageB, componentB, 4, 10042, true);
        assertTrue(refused.debugStructure.contains("fixture_lane=b;adjacent_fields=prefix_rejected;miuiFlags_value=absent"));
        assertFalse(refused.debugStructure.contains("first:numeric"));
    }

    private static void assertDebug(String reason, String diagnostic, String input) {
        PhysicalTaskPresentationEvidence.Result result = PhysicalTaskPresentationEvidence.evaluate(
                WMS, input, 42, "b.pkg", "b.pkg/.Main", 4, 10042);
        assertFalse(result.ready);
        assertEquals(reason, result.reason);
        assertEquals(diagnostic, result.debugStructure);
    }
}
