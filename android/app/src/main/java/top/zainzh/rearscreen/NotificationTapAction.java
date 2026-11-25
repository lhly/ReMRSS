package top.zainzh.rearscreen;

/** The user's choice is independent of whether the notification is a page or a capsule. */
enum NotificationTapAction {
    MAIN("main", R.string.ui_notification_click_open),
    REAR("rear", R.string.ui_notification_click_open_rear),
    REAR_IDLE("rear_idle", R.string.ui_notification_click_open_rear_idle),
    DISMISS("dismiss", R.string.ui_notification_click_dismiss);

    static final String PREF = "notification_click_action";
    enum Effect {
        OPEN_MAIN, OPEN_REAR, REPLACE_REAR, DISMISS;

        Effect forRearForeground(String source, RearDisplayCastOccupancy.Snapshot state) {
            if (this != REPLACE_REAR || source == null || state == null || state.displayId <= 0
                    || state.failure != null || !"activity_task".equals(state.source)
                    || state.displayState != android.view.Display.STATE_ON
                    || state.powerBefore != state.powerAfter) return this;
            RearDisplayCastOccupancy.Task foreground = null;
            for (RearDisplayCastOccupancy.Task task : state.tasks) {
                if (task.clearlyHidden(state.displayState)) continue;
                if (foreground != null || !Boolean.TRUE.equals(task.visible)
                        || !Boolean.FALSE.equals(task.sleeping)) return this;
                foreground = task;
            }
            // Use the actually visible Activity, not the original cast's package or a background task.
            return foreground != null && foreground.userId == 0 && source.equals(foreground.topPackage)
                    ? DISMISS : this;
        }
    }

    final String value;
    final int label;

    NotificationTapAction(String value, int label) {
        this.value = value;
        this.label = label;
    }

    static NotificationTapAction resolve(String value, boolean legacyDismiss, boolean legacyRear) {
        for (NotificationTapAction action : values())
            if (action.value.equals(value)) return action;
        if (legacyDismiss) return DISMISS;
        return legacyRear ? REAR_IDLE : MAIN;
    }

    static NotificationTapAction read(android.content.SharedPreferences settings) {
        return resolve(settings.getString(PREF, null),
                settings.getBoolean("notification_dismiss_only", false),
                settings.getBoolean(RearNotificationCastRequest.PREF, false));
    }

    boolean usesRear() { return this == REAR || this == REAR_IDLE; }

    Effect effect(boolean rearBusy) {
        switch (this) {
            case MAIN: return Effect.OPEN_MAIN;
            case REAR: return rearBusy ? Effect.REPLACE_REAR : Effect.OPEN_REAR;
            case REAR_IDLE: return rearBusy ? Effect.DISMISS : Effect.OPEN_REAR;
            default: return Effect.DISMISS;
        }
    }
}
