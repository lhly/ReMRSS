package top.zainzh.rearscreen;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public final class RearVirtualImeTest {
    @Test public void localPolicyIsVerifiedOnlyOnTheOwnedDisplay() throws Exception {
        Fake os = new Fake();
        assertThrows(IllegalArgumentException.class, () -> RearVirtualIme.enable(0, 1, os));
        assertThrows(IllegalArgumentException.class, () -> RearVirtualIme.enable(1, 1, os));
        assertTrue(os.calls.isEmpty());
        RearVirtualIme.enable(42, 1, os);
        assertEquals(List.of("set:42:0", "get:42"), os.calls);
    }

    @Test public void localPolicyWithoutReadbackDoesNotEnableTheFocusHook() {
        Fake os = new Fake();
        os.policy = 1;
        assertThrows(IllegalStateException.class, () -> RearVirtualIme.enable(42, 1, os));
    }

    @Test public void lockedHomeCanDeferThenEnableImeWithoutFocusingOrTouchingAPhysicalDisplay() throws Exception {
        Fake os = new Fake();
        os.locked = true;
        os.policy = 2;
        assertThrows(IllegalArgumentException.class, () -> RearVirtualIme.deferLockedHome(0, 1, os));
        assertThrows(IllegalArgumentException.class, () -> RearVirtualIme.deferLockedHome(1, 1, os));
        RearVirtualIme.deferLockedHome(42, 1, os);
        os.policy = 0;
        RearVirtualIme.enable(42, 1, os);
        assertEquals(List.of("set:42:2", "get:42", "set:42:0", "get:42"), os.calls);
    }

    @Test public void appBoundTouchFocusesTheCurrentTaskOnThatDisplay() throws Exception {
        Fake os = new Fake();
        RearVirtualIme ime = RearVirtualIme.enable(42, 1, os);
        os.calls.clear();
        assertTrue(ime.beforeDown(() -> true));
        os.topTask = 43;
        assertTrue(ime.beforeDown(() -> true));
        assertEquals(List.of("keyguard", "top:42", "focus:7", "keyguard", "top:42", "focus:43"), os.calls);
    }

    @Test public void lockedOrClosedSessionsNeverRequestFocus() throws Exception {
        Fake os = new Fake();
        RearVirtualIme ime = RearVirtualIme.enable(42, 1, os);
        os.calls.clear();
        assertFalse(ime.beforeDown(() -> false));
        os.locked = true;
        assertFalse(ime.beforeDown(() -> true));
        assertEquals(List.of("keyguard"), os.calls);
    }

    @Test public void sessionEndingDuringTheTaskReadDoesNotFocusTheOldTask() throws Exception {
        Fake os = new Fake();
        RearVirtualIme ime = RearVirtualIme.enable(42, 1, os);
        AtomicBoolean current = new AtomicBoolean(true);
        os.onRead = () -> current.set(false);
        os.calls.clear();
        assertFalse(ime.beforeDown(current::get));
        assertEquals(List.of("keyguard", "top:42"), os.calls);
    }

    private static final class Fake implements RearVirtualIme.Platform {
        final List<String> calls = new ArrayList<>();
        int policy, topTask = 7;
        boolean locked;
        Runnable onRead = () -> { };
        @Override public void setPolicy(int display, int policy) { calls.add("set:" + display + ":" + policy); }
        @Override public int getPolicy(int display) { calls.add("get:" + display); return policy; }
        @Override public boolean keyguardLocked() { calls.add("keyguard"); return locked; }
        @Override public int topTaskOnDisplay(int display) { calls.add("top:" + display); onRead.run(); return topTask; }
        @Override public void focusTask(int task) { calls.add("focus:" + task); }
    }
}
