package top.zainzh.rearscreen;

/** Executes the user-selected original notification from the visible app process. */
interface IRearNotificationTarget {
    boolean launch(int virtualDisplayId);
    /** Recheck the live notification before a new activity is started. */
    boolean isCurrent();
    /** Verify only the launch already dispatched to this exact display; a consumed notification is expected. */
    boolean mayVerifyLaunch(int virtualDisplayId);
    boolean launchSource(int virtualDisplayId);
}
