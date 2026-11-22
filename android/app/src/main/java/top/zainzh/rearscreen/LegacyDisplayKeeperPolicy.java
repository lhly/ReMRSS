package top.zainzh.rearscreen;

/** Pure gate shared by the existing global wakeup loops and their restoration path. */
final class LegacyDisplayKeeperPolicy {
    private LegacyDisplayKeeperPolicy() { }

    static boolean mayWake(boolean userEnabled, boolean sceneLeaseSuspended, boolean sceneLeaseActive) {
        return userEnabled && !sceneLeaseSuspended && !sceneLeaseActive;
    }

    static boolean mayRestoreAfterScene(boolean restorePriorMode, boolean userEnabled,
            boolean matchingSceneLease, boolean anotherSceneLeaseActive) {
        return restorePriorMode && userEnabled && matchingSceneLease && !anotherSceneLeaseActive;
    }
}
