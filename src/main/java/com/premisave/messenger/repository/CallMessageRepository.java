package com.premisave.messenger.repository;

import com.premisave.messenger.entity.CallMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CallMessageRepository extends MongoRepository<CallMessage, String> {

    /** Newest first, so the caller takes a page and reverses it. */
    List<CallMessage> findByCallIdOrderByCreatedAtDesc(String callId, Pageable pageable);
}
