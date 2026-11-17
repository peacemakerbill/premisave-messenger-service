package com.premisave.messenger.repository;

import com.premisave.messenger.entity.Call;
import com.premisave.messenger.enums.CallStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CallRepository extends MongoRepository<Call, String> {

    Optional<Call> findByRoomName(String roomName);

    Optional<Call> findByLinkCode(String linkCode);

    /** The call running in a chat right now, if any. */
    Optional<Call> findFirstByChatIdAndStatusInOrderByCreatedAtDesc(String chatId, Collection<CallStatus> statuses);

    List<Call> findByStatusIn(Collection<CallStatus> statuses);

    /** Calls that still have this person in them or ringing for them. */
    @Query("{'status': {$in: ?1}, 'participants': {$elemMatch: {'userId': ?0, 'state': {$in: ['JOINED', 'INVITED']}}}}")
    List<Call> findOpenCallsOf(String userId, Collection<CallStatus> statuses);

    /** Their history, newest first. Pass the page size in the Pageable (unsorted; the sort is fixed here). */
    @Query(value = "{'participants.userId': ?0, 'hiddenFor': {$ne: ?0}, 'status': {$nin: ['WAITING']}, 'createdAt': {$lt: ?1}}",
            sort = "{'createdAt': -1}")
    List<Call> findHistory(String userId, LocalDateTime before, Pageable pageable);
}
