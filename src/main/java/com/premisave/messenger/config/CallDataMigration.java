package com.premisave.messenger.config;

import com.premisave.messenger.entity.CallMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Messages in a call's chat used to be deleted after 30 days by a MongoDB time-to-live index. They are now kept, so the
 * people who were in the call can read the chat later. Removing the annotation is not enough: the index stays in the
 * database until it is dropped, so it is dropped here (once; later starts find nothing to drop).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallDataMigration implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean exists = mongoTemplate.indexOps(CallMessage.class).getIndexInfo().stream()
                    .anyMatch(i -> "call_messages_ttl_idx".equals(i.getName()));
            if (exists) {
                mongoTemplate.indexOps(CallMessage.class).dropIndex("call_messages_ttl_idx");
                log.info("Dropped the 30 day expiry on call chat messages, they are now kept");
            }
        } catch (Exception e) {
            log.warn("Could not check the call chat expiry index: {}", e.getMessage());
        }
    }
}
