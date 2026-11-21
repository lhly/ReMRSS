package top.zainzh.rearscreen;

/** Executes the user-selected original notification from the visible app process. */
interface IRearNotificationTarget {
    boolean launch(int virtualDisplayId);
}
