package com.premisave.messenger.repository;

import com.premisave.messenger.entity.Group;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface GroupRepository extends MongoRepository<Group, String> {

    Optional<Group> findByName(String name);

    List<Group> findByMemberIdsContaining(String userId);
}