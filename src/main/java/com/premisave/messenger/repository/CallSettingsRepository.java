package com.premisave.messenger.repository;

import com.premisave.messenger.entity.CallSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CallSettingsRepository extends MongoRepository<CallSettings, String> {
}
