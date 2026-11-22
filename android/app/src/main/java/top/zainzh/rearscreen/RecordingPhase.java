package top.zainzh.rearscreen;

/** Main-thread state shared by the recording overlay, tile and foreground notification. */
enum RecordingPhase {
    IDLE, PREPARING, RECORDING, SAVING, SAVED, START_FAILED, STOP_FAILED, SAVE_FAILED;

    boolean canStart() {
        return this == IDLE || this == SAVED || this == START_FAILED || this == SAVE_FAILED;
    }

    boolean canStop() { return this == RECORDING || this == STOP_FAILED; }

    boolean isBusy() { return this == PREPARING || this == SAVING; }
}
