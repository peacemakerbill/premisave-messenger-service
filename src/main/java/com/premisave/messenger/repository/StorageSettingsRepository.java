package com.premisave.messenger.repository;

import com.premisave.messenger.entity.StorageSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StorageSettingsRepository extends MongoRepository<StorageSettings, String> {
}
