package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.AttachmentResponse;
import com.premisave.messenger.entity.Attachment;
import com.premisave.messenger.security.CurrentUserResolver;
import com.premisave.messenger.service.AttachmentService;
import com.premisave.messenger.util.ContentTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final CurrentUserResolver currentUserResolver;

    /**
     * Step 1 of sending a file. Any file type, up to storage.max-file-size.
     * POST /api/attachments  (multipart: file, chatId)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttachmentResponse> upload(
            @RequestParam MultipartFile file,
            @RequestParam String chatId,
            @RequestHeader("Authorization") String authHeader) {

        String userId = currentUserResolver.resolve(authHeader).getId();
        AttachmentResponse body = attachmentService.upload(file, chatId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Download or stream. Accepts either a Bearer token or the signed link
     * found in a message (exp and sig query parameters). Supports HTTP
     * Range, so audio and video can seek and large files can resume.
     */
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<Resource> content(
            @PathVariable String attachmentId,
            @RequestParam(required = false) Long exp,
            @RequestParam(required = false) String sig,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String userId = (sig == null && authHeader != null)
                ? currentUserResolver.resolve(authHeader).getId()
                : null;

        AttachmentService.Content content = attachmentService.openForDownload(attachmentId, userId, exp, sig);
        Attachment a = content.attachment();

        boolean inline = ContentTypes.isInlineSafe(a.getMimeType());
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(a.getFileName(), StandardCharsets.UTF_8)
                .build();

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(a.getMimeType());
        } catch (IllegalArgumentException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        // No explicit Content-Length: Spring sets it, and sets the right
        // one for a partial (206) response when the client sends Range.
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(new FileSystemResource(content.path()));
    }
}