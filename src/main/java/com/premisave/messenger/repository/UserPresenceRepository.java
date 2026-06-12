package com.premisave.messenger.repository;

import com.premisave.messenger.entity.UserPresence;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserPresenceRepository extends MongoRepository<UserPresence, String> {
}