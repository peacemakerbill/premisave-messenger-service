package com.premisave.messenger.entity;

import com.premisave.messenger.enums.RecordingStatus;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * One recording of a call. The video file is on the server's recordings folder at fileName (relative to that folder).
 * A call can have several recordings when a host stops and starts again. They are kept until someone deletes them.
 */
@Data
@Document(collection = "call_recordings")
@CompoundIndex(name = "call_recordings_call_idx", def = "{'callId': 1, 'createdAt': -1}")
public class CallRecording {

    @Id
    private String id;

    private String callId;
    private String roomName;

    /** The recorder's own id for this job. Set once the recorder accepted it. */
    @Indexed(sparse = true, name = "call_recordings_egress_idx")
    private String egressId;

    private String startedBy;
    private String startedByName;

    private RecordingStatus status;

    /** Path inside the recordings folder, for example callId/recordingId.mp4. */
    private String fileName;
    private long sizeBytes;
    private long durationSeconds;

    /** Why it failed, in plain words. */
    private String error;

    private LocalDateTime createdAt;
    /** When the recorder began capturing. */
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    /** The last time the status changed. The cleanup job uses it to find recordings that got stuck. */
    private LocalDateTime updatedAt;
}
