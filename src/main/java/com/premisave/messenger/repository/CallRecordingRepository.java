package com.premisave.messenger.repository;

import com.premisave.messenger.entity.CallRecording;
import com.premisave.messenger.enums.RecordingStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CallRecordingRepository extends MongoRepository<CallRecording, String> {

    Optional<CallRecording> findByEgressId(String egressId);

    /** Newest first. */
    List<CallRecording> findByCallIdOrderByCreatedAtDesc(String callId);

    List<CallRecording> findByCallIdIn(Collection<String> callIds);

    List<CallRecording> findByStatusIn(Collection<RecordingStatus> statuses);

    Optional<CallRecording> findFirstByRoomNameAndStatusOrderByCreatedAtDesc(String roomName, RecordingStatus status);
}
