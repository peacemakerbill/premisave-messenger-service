package com.premisave.messenger.controller;

import com.premisave.messenger.service.MediaService;
import com.premisave.messenger.util.ContentTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.Duration;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    /** Stores a file and returns its public URL. Needs a Bearer token. */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadMedia(
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "images") String type) {

        return ResponseEntity.ok(mediaService.uploadMedia(file, type));
    }

    /**
     * Public read for files returned by the upload endpoints (group photos
     * and the like). No token, so plain image tags work. Unknown types are
     * served as downloads, never rendered.
     */
    @GetMapping("/files/{folder}/{month}/{name:.+}")
    public ResponseEntity<Resource> file(
            @PathVariable String folder,
            @PathVariable String month,
            @PathVariable String name) {

        Path path = mediaService.openPublic(folder, month, name);
        String mime = MediaService.mimeOf(name);

        ContentDisposition disposition = (ContentTypes.isInlineSafe(mime)
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(name)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .body(new FileSystemResource(path));
    }
}