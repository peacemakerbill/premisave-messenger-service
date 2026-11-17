package com.premisave.messenger.repository;

import com.premisave.messenger.entity.GroupInvite;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface GroupInviteRepository extends MongoRepository<GroupInvite, String> {

    Optional<GroupInvite> findByCode(String code);

    List<GroupInvite> findByGroupIdAndRevokedFalse(String groupId);
}