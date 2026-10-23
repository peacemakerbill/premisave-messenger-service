package com.premisave.messenger.repository;

import com.premisave.messenger.entity.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface StatusRepository extends MongoRepository<Status, String> {

    /** Statuses still showing, oldest first so they play in the order they were posted. */
    List<Status> findByOwnerIdAndExpiresAtAfterOrderByCreatedAtAsc(String ownerId, LocalDateTime now);

    List<Status> findByOwnerIdInAndExpiresAtAfterOrderByCreatedAtAsc(Collection<String> ownerIds, LocalDateTime now);

    /** Statuses that have disappeared, newest first. */
    List<Status> findByOwnerIdAndExpiresAtLessThanEqualOrderByCreatedAtDesc(String ownerId, LocalDateTime now, Pageable pageable);
}