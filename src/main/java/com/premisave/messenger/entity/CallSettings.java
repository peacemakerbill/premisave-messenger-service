package com.premisave.messenger.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** Switches for calls that the administrators control from the dashboard. One record, with the id "calls". */
@Data
@Document(collection = "call_settings")
public class CallSettings {

    public static final String ID = "calls";

    @Id
    private String id = ID;

    /** When false, nobody can record a call, and the recording buttons and recordings are hidden for everyone. */
    private boolean recordingEnabled;

    private String updatedBy;
    private LocalDateTime updatedAt;
}
