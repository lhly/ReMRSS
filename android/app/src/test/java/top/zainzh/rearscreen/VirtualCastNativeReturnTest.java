package top.zainzh.rearscreen;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class VirtualCastNativeReturnTest {
    private static final String EDITOR = "example.editor/.Screen";
    private static final String WEB = "example.web/.Page";

    @Test public void capturesEveryIndependentRootIncludingASecondApp() {
        assertEquals(Map.of(7, "example.editor/example.editor.Screen", 8, "example.web/example.web.Page"),
                VirtualCastNativeReturn.inventory(List.of(root(7, EDITOR), root(8, WEB)), 42, 7));
    }

    @Test public void doesNotDelegateWhenInventoryOrLayoutIsInexact() {
        assertNull(VirtualCastNativeReturn.inventory(List.of(root(8, WEB)), 42, 7));
        assertNull(VirtualCastNativeReturn.inventory(List.of(root(7, EDITOR), root(7, WEB)), 42, 7));
        assertNull(VirtualCastNativeReturn.inventory(List.of(
                new VirtualCastNativeReturn.RootTask(7, 0, EDITOR, true, new int[]{7})), 42, 7));
        assertNull(VirtualCastNativeReturn.inventory(List.of(
                new VirtualCastNativeReturn.RootTask(7, 42, EDITOR, false, new int[]{7})), 42, 7));
        assertNull(VirtualCastNativeReturn.inventory(List.of(
                new VirtualCastNativeReturn.RootTask(7, 42, EDITOR, true, new int[]{8, 9})), 42, 7));
    }

    @Test public void completionRequiresAllCapturedIdsAndBasesOnMain() {
        Map<Integer, String> inventory = VirtualCastNativeReturn.inventory(
                List.of(root(7, EDITOR), root(8, WEB)), 42, 7);
        assertFalse(VirtualCastNativeReturn.returned(inventory, List.of(new TaskRow(7, 0, EDITOR))));
        assertFalse(VirtualCastNativeReturn.returned(inventory, List.of(
                new TaskRow(7, 0, EDITOR), new TaskRow(8, 42, WEB))));
        assertFalse(VirtualCastNativeReturn.returned(inventory, List.of(
                new TaskRow(7, 0, EDITOR), new TaskRow(8, 0, EDITOR))));
        assertTrue(VirtualCastNativeReturn.returned(inventory, List.of(
                new TaskRow(9, 1, WEB), new TaskRow(8, 0, WEB), new TaskRow(7, 0, EDITOR))));
    }

    private static VirtualCastNativeReturn.RootTask root(int id, String base) {
        return new VirtualCastNativeReturn.RootTask(id, 42, base, true, new int[]{id});
    }

    public static final class TaskRow {
        public final int taskId, displayId;
        public final FakeComponent baseActivity;
        TaskRow(int taskId, int displayId, String name) {
            this.taskId = taskId; this.displayId = displayId;
            baseActivity = new FakeComponent(name);
        }
    }

    public static final class FakeComponent {
        private final String name;
        FakeComponent(String name) { this.name = name; }
        public String flattenToString() { return name; }
    }
}
