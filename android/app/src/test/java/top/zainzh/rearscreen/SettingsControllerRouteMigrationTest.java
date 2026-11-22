package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsControllerRouteMigrationTest {
    @Test public void restoreRemovedModuleDetailToModulesTab() {
        assertEquals(2, SettingsController.restorePage(30, 2));
        assertEquals(2, SettingsController.restorePage(30, 1));
    }

    @Test public void preserveOtherRoutesWhenRestoringOldTasks() {
        assertEquals(100, SettingsController.restorePage(3, 1));
        assertEquals(3, SettingsController.restorePage(2, 1));
        assertEquals(3, SettingsController.restorePage(3, 2));
        assertEquals(40, SettingsController.restorePage(40, 2));
    }
}
