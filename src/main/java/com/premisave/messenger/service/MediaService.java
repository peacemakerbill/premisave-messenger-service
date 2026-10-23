package com.premisave.messenger.service;

import com.premisave.messenger.config.StorageProperties;
import com.premisave.messenger.enums.AttachmentCategory;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.storage.FileStorage;
import com.premisave.messenger.storage.StoredFile;
import com.premisave.messenger.util.ContentTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Public media such as group photos, kept in the same storage as chat
 * attachments. Files here are served without a signature at
 * /api/media/files/..., so the stored type is decided from the file's
 * own bytes and anything that is not a known-safe type is saved as .bin
 * and always downloads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    static final String URL_PATH = "/api/media/files/";
    private static final Pattern FOLDER = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final Pattern NAME = Pattern.compile("^[0-9a-f-]{36}\\.[a-z0-9]{1,5}$");

    private final FileStorage fileStorage;
    private final StorageProperties props;

    /** Stores any file type and returns its public URL. */
    public String uploadMedia(MultipartFile file, String folder) {
        return upload(file, folder, false);
    }

    /** Stores an image only (bytes are checked, not the name or declared type). */
    public String uploadImage(MultipartFile file, String folder) {
        return upload(file, folder, true);
    }

    /** Deletes a file previously returned by this service. Anything else is ignored. */
    public void deleteByUrl(String url) {
        String prefix = baseUrl() + URL_PATH;
        if (url == null || !url.startsWith(prefix)) return;
        String[] parts = url.substring(prefix.length()).split("/");
        if (parts.length == 3 && valid(parts[0], parts[1], parts[2])) {
            fileStorage.deleteQuietly("public/" + String.join("/", parts));
        }
    }

    /** Resolves a public file for serving. Rejects anything not shaped like our own paths. */
    public Path openPublic(String folder, String month, String name) {
        if (!valid(folder, month, name)) {
            throw HttpStatusException.notFound("FILE_MISSING", "File not found");
        }
        return fileStorage.open("public/" + folder + "/" + month + "/" + name);
    }

    public static String mimeOf(String fileName) {
        return ContentTypes.mimeForExtension(fileName.substring(fileName.lastIndexOf('.') + 1));
    }

    // ----------------------------------------------------------------

    private String upload(MultipartFile file, String folder, boolean imageOnly) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty file is required");
        }
        String dir = folder == null || folder.isBlank() ? "images" : folder.toLowerCase();
        if (!FOLDER.matcher(dir).matches()) {
            throw new IllegalArgumentException("Invalid media folder");
        }

        long max = props.getMaxFileSize().toBytes();
        try (InputStream raw = file.getInputStream();
             PushbackInputStream in = new PushbackInputStream(raw, 16)) {

            byte[] head = new byte[16];
            int read = in.readNBytes(head, 0, head.length);
            in.unread(head, 0, read);

            String mime = ContentTypes.detect(file.getContentType(), file.getOriginalFilename(),
                    Arrays.copyOf(head, read));
            if (imageOnly && ContentTypes.categorize(mime) != AttachmentCategory.IMAGE) {
                throw new IllegalArgumentException("Only image files are allowed");
            }

            StoredFile stored = fileStorage.storePublic(in, dir, ContentTypes.extensionFor(mime), max);
            String url = baseUrl() + URL_PATH + stored.relativePath().substring("public/".length());
            log.info("Public media stored: {} ({} bytes, {})", url, stored.size(), mime);
            return url;

        } catch (IOException e) {
            log.error("Could not read upload stream: {}", e.getMessage());
            throw new IllegalArgumentException("Could not read the uploaded file");
        }
    }

    private String baseUrl() {
        return props.getPublicBaseUrl().replaceAll("/+$", "");
    }

    private static boolean valid(String folder, String month, String name) {
        return folder != null && month != null && name != null
                && FOLDER.matcher(folder).matches()
                && MONTH.matcher(month).matches()
                && NAME.matcher(name).matches();
    }
}