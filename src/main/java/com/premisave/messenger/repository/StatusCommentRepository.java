package com.premisave.messenger.repository;

import com.premisave.messenger.entity.StatusComment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface StatusCommentRepository extends MongoRepository<StatusComment, String> {

    List<StatusComment> findByStatusIdOrderByCreatedAtAsc(String statusId, Pageable pageable);

    void deleteByStatusId(String statusId);
}