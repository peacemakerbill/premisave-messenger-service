package com.premisave.messenger.entity;

import com.premisave.messenger.enums.StatusActivityKind;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** A view, like or emoji reaction. The unique index keeps it to one of each kind per person. */
@Data
@Document(collection = "status_activity")
@CompoundIndexes({
    @CompoundIndex(name = "status_activity_unique_idx", def = "{'statusId': 1, 'userId': 1, 'kind': 1}", unique = true),
    @CompoundIndex(name = "status_activity_list_idx", def = "{'statusId': 1, 'kind': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "status_activity_user_idx", def = "{'userId': 1, 'kind': 1, 'statusId': 1}")
})
public class StatusActivity {

    @Id
    private String id;

    private String statusId;
    private String userId;
    private StatusActivityKind kind;

    // Only for REACTION
    private String emoji;

    private LocalDateTime createdAt;
}