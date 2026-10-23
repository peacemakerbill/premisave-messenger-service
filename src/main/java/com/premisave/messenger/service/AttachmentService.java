package com.premisave.messenger.service;

import com.premisave.messenger.config.StorageProperties;
import com.premisave.messenger.dto.response.AttachmentResponse;
import com.premisave.messenger.entity.Attachment;
import com.premisave.messenger.enums.AttachmentStatus;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.repository.AttachmentRepository;
import com.premisave.messenger.security.AttachmentUrlSigner;
import com.premisave.messenger.storage.FileStorage;
import com.premisave.messenger.storage.StoredFile;
import com.premisave.messenger.util.ContentTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Upload first, send second.
 *
 * 1. POST /api/attachments stores the bytes and returns an attachmentId.
 * 2. The message (REST or STOMP) carries that attachmentId.
 *
 * Sending a message never moves bytes, so a slow upload cannot block the
 * chat, a retry cannot duplicate a message, and the same id works from
 * REST and WebSocket alike.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final MongoTemplate mongoTemplate;
    private final FileStorage fileStorage;
    private final ChatService chatService;
    private final AttachmentUrlSigner urlSigner;
    private final StorageProperties props;

    /** Folder and chat id used for the files behind statuses. They belong to no chat. */
    public static final String STATUS_FOLDER = "status";

    public record Content(Attachment attachment, Path path) {
    }

    public AttachmentResponse upload(MultipartFile file, String chatId, String userId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty file is required");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId is required");
        }
        if (!chatService.canAccessChat(chatId, userId)) {
            throw HttpStatusException.forbidden("You are not a member of this chat");
        }

        long max = props.getMaxFileSize().toBytes();
        if (file.getSize() > max) {
            throw HttpStatusException.tooLarge("File is larger than " + props.getMaxFileSize().toMegabytes() + " MB");
        }

        String fileName = sanitizeFileName(file.getOriginalFilename());
        StoredFile stored;
        try (InputStream in = file.getInputStream()) {
            stored = fileStorage.store(in, chatId, max);
        } catch (IOException e) {
            log.error("Could not read upload stream: {}", e.getMessage());
            throw new IllegalArgumentException("Could not read the uploaded file");
        }

        String mimeType = ContentTypes.detect(file.getContentType(), fileName, stored.head());

        Attachment a = new Attachment();
        a.setChatId(chatId);
        a.setUploaderId(userId);
        a.setFileName(fileName);
        a.setMimeType(mimeType);
        a.setSize(stored.size());
        a.setSha256(stored.sha256());
        a.setCategory(ContentTypes.categorize(mimeType));
        a.setStoragePath(stored.relativePath());
        a.setStatus(AttachmentStatus.UPLOADED);
        a.setCreatedAt(LocalDateTime.now());

        try {
            a = attachmentRepository.save(a);
        } catch (RuntimeException e) {
            fileStorage.deleteQuietly(stored.relativePath());
            throw e;
        }

        log.info("Attachment {} uploaded by {} to chat {} ({} bytes, {})",
                a.getId(), userId, chatId, a.getSize(), a.getMimeType());
        return toResponse(a);
    }

    /**
     * Stores the file behind a status. Unlike a chat upload it is attached at once,
     * because the status is created in the same request, and it belongs to no chat.
     * Downloads work through the signed link in the status response.
     */
    public Attachment storeForStatus(MultipartFile file, String userId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty file is required");
        }
        long max = props.getMaxFileSize().toBytes();
        if (file.getSize() > max) {
            throw HttpStatusException.tooLarge("File is larger than " + props.getMaxFileSize().toMegabytes() + " MB");
        }

        String fileName = sanitizeFileName(file.getOriginalFilename());
        StoredFile stored;
        try (InputStream in = file.getInputStream()) {
            stored = fileStorage.store(in, STATUS_FOLDER, max);
        } catch (IOException e) {
            log.error("Could not read status upload stream: {}", e.getMessage());
            throw new IllegalArgumentException("Could not read the uploaded file");
        }

        String mimeType = ContentTypes.detect(file.getContentType(), fileName, stored.head());

        Attachment a = new Attachment();
        a.setChatId(STATUS_FOLDER);
        a.setUploaderId(userId);
        a.setFileName(fileName);
        a.setMimeType(mimeType);
        a.setSize(stored.size());
        a.setSha256(stored.sha256());
        a.setCategory(ContentTypes.categorize(mimeType));
        a.setStoragePath(stored.relativePath());
        a.setStatus(AttachmentStatus.ATTACHED);
        a.setCreatedAt(LocalDateTime.now());
        a.setAttachedAt(a.getCreatedAt());

        try {
            return attachmentRepository.save(a);
        } catch (RuntimeException e) {
            fileStorage.deleteQuietly(stored.relativePath());
            throw e;
        }
    }

    /**
     * Binds an upload to one outgoing message. A single atomic update does
     * the check and the claim together, so two sends of the same id cannot
     * both succeed, and nobody can claim someone else's upload.
     */
    public Attachment claim(String attachmentId, String userId, String chatId) {
        Query q = new Query(Criteria.where("_id").is(attachmentId)
                .and("uploaderId").is(userId)
                .and("chatId").is(chatId)
                .and("status").is(AttachmentStatus.UPLOADED));
        Update u = new Update()
                .set("status", AttachmentStatus.ATTACHED)
                .set("attachedAt", LocalDateTime.now());
        Attachment claimed = mongoTemplate.findAndModify(q, u,
                FindAndModifyOptions.options().returnNew(true), Attachment.class);
        if (claimed == null) {
            throw new IllegalArgumentException(
                    "Attachment not found, already sent, expired, or uploaded for a different chat");
        }
        return claimed;
    }

    /** Puts a claimed upload back when the message could not be saved. */
    public void release(String attachmentId) {
        mongoTemplate.updateFirst(
                new Query(Criteria.where("_id").is(attachmentId).and("status").is(AttachmentStatus.ATTACHED)),
                new Update().set("status", AttachmentStatus.UPLOADED).unset("attachedAt"),
                Attachment.class);
    }

    public void linkMessage(String attachmentId, String messageId) {
        mongoTemplate.updateFirst(
                new Query(Criteria.where("_id").is(attachmentId)),
                new Update().set("messageId", messageId),
                Attachment.class);
    }

    public String signedUrl(String attachmentId) {
        return attachmentId == null ? null : urlSigner.signedUrl(attachmentId);
    }

    /**
     * Opens a file for download. Access is granted by a valid signed link
     * or by a caller who is a current member of the attachment's chat.
     * An upload not yet sent is visible to its uploader only.
     */
    public Content openForDownload(String attachmentId, String userId, Long exp, String sig) {
        Attachment a = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> HttpStatusException.notFound("ATTACHMENT_NOT_FOUND", "Attachment not found"));

        if (a.getStatus() == AttachmentStatus.DELETED) {
            throw HttpStatusException.notFound("ATTACHMENT_NOT_FOUND", "Attachment not found");
        }

        boolean allowed;
        if (sig != null) {
            allowed = a.getStatus() == AttachmentStatus.ATTACHED && urlSigner.isValid(attachmentId, exp, sig);
        } else if (userId != null) {
            allowed = a.getStatus() == AttachmentStatus.UPLOADED
                    ? userId.equals(a.getUploaderId())
                    : chatService.canAccessChat(a.getChatId(), userId);
        } else {
            throw HttpStatusException.unauthorized("Sign in or use the signed link from the message");
        }
        if (!allowed) {
            throw HttpStatusException.forbidden("This link is invalid or has expired");
        }
        return new Content(a, fileStorage.open(a.getStoragePath()));
    }

    /** Removes the file behind a message that was deleted for everyone. */
    public void deleteForMessage(String attachmentId) {
        if (attachmentId == null) return;
        attachmentRepository.findById(attachmentId).ifPresent(a -> {
            fileStorage.deleteQuietly(a.getStoragePath());
            mongoTemplate.updateFirst(
                    new Query(Criteria.where("_id").is(a.getId())),
                    new Update().set("status", AttachmentStatus.DELETED),
                    Attachment.class);
            log.info("Attachment {} deleted", a.getId());
        });
    }

    /** Drops uploads nobody sent. Runs every 30 minutes. */
    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void purgeUnclaimed() {
        LocalDateTime cutoff = LocalDateTime.now().minus(props.getUnclaimedTtl());
        List<Attachment> stale;
        int removed = 0;
        do {
            stale = attachmentRepository.findTop200ByStatusAndCreatedAtBefore(AttachmentStatus.UPLOADED, cutoff);
            for (Attachment a : stale) {
                fileStorage.deleteQuietly(a.getStoragePath());
                attachmentRepository.deleteById(a.getId());
                removed++;
            }
        } while (stale.size() == 200);
        if (removed > 0) {
            log.info("Purged {} unclaimed uploads older than {}", removed, props.getUnclaimedTtl());
        }
    }

    private AttachmentResponse toResponse(Attachment a) {
        AttachmentResponse r = new AttachmentResponse();
        r.setAttachmentId(a.getId());
        r.setChatId(a.getChatId());
        r.setFileName(a.getFileName());
        r.setMimeType(a.getMimeType());
        r.setSize(a.getSize());
        r.setCategory(a.getCategory());
        r.setMessageType(a.getCategory().toMessageType());
        r.setUploadedAt(a.getCreatedAt());
        r.setExpiresAt(a.getCreatedAt().plus(props.getUnclaimedTtl()));
        return r;
    }

    static String sanitizeFileName(String raw) {
        if (raw == null) return "file";
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "file";
        if (name.length() > 150) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
            name = name.substring(0, 150 - ext.length()) + ext;
        }
        return name;
    }
}