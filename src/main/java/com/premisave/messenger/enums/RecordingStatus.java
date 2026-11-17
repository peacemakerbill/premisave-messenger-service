package com.premisave.messenger.enums;

/** Where a call recording is in its life. */
public enum RecordingStatus {
    /** Asked for, the recorder is getting ready. */
    STARTING,
    /** Recording right now. */
    RECORDING,
    /** Stopped, the file is being finished. */
    STOPPING,
    /** Finished and ready to watch. */
    READY,
    /** Did not produce a usable file. */
    FAILED;

    /** True while the call is still being captured or the file is still being written. */
    public boolean isLive() {
        return this == STARTING || this == RECORDING || this == STOPPING;
    }

    /** True while people in the call should see the red "Recording" sign. */
    public boolean isCapturing() {
        return this == STARTING || this == RECORDING;
    }
}
