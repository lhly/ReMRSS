package top.zainzh.rearscreen;

/** Caller-owned one-shot authority for focusing one exact main-display task. */
interface ITaskFocusActionGate {
    boolean isCurrent(int taskId, String expectedBaseComponent, String expectedTopComponent);
}
