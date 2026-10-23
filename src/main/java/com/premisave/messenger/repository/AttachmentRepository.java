package com.premisave.messenger.repository;

import com.premisave.messenger.entity.Attachment;
import com.premisave.messenger.enums.AttachmentStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AttachmentRepository extends MongoRepository<Attachment, String> {

    List<Attachment> findTop200ByStatusAndCreatedAtBefore(AttachmentStatus status, LocalDateTime before);
}
