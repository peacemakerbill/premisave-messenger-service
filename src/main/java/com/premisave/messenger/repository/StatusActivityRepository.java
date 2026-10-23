package com.premisave.messenger.repository;

import com.premisave.messenger.entity.StatusActivity;
import com.premisave.messenger.enums.StatusActivityKind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StatusActivityRepository extends MongoRepository<StatusActivity, String> {

    Optional<StatusActivity> findByStatusIdAndUserIdAndKind(String statusId, String userId, StatusActivityKind kind);

    List<StatusActivity> findByStatusIdAndKindOrderByCreatedAtDesc(String statusId, StatusActivityKind kind, Pageable pageable);

    List<StatusActivity> findByUserIdAndStatusIdIn(String userId, Collection<String> statusIds);

    void deleteByStatusId(String statusId);
}