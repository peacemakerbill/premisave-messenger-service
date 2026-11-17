package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.RecordingStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** One recording of a call. */
@Data
public class CallRecordingResponse {

    private String id;
    private String callId;
    private RecordingStatus status;
    private String startedBy;
    private String startedByName;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private long durationSeconds;
    private long sizeBytes;

    /** A link that plays or downloads the video without a sign-in header. Only set when the recording is READY. */
    private String playUrl;

    /** Why it failed, when it did. */
    private String error;

    /** Whether the person asking may delete it. */
    private boolean canDelete;
}
