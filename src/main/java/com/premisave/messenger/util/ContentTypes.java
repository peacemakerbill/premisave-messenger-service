package com.premisave.messenger.util;

import com.premisave.messenger.enums.AttachmentCategory;

import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Content type detection and the rules for what a browser may render inline.
 * The client's declared type is only a hint: magic bytes win for the common
 * formats, then the file extension.
 */
public final class ContentTypes {

    public static final String OCTET_STREAM = "application/octet-stream";

    /** Types safe to display inline. Everything else is forced to download. */
    private static final Set<String> INLINE_SAFE = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/avif",
            "video/mp4", "video/webm", "video/ogg", "video/quicktime",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/webm", "audio/wav", "audio/x-wav", "audio/flac",
            "application/pdf");

    private ContentTypes() {
    }

    public static String detect(String declared, String fileName, byte[] head) {
        String sniffed = sniff(head);
        if (sniffed != null) {
            return sniffed;
        }
        String type = normalize(declared);
        if (type != null && !OCTET_STREAM.equals(type)) {
            return type;
        }
        String guessed = fileName == null ? null : URLConnection.guessContentTypeFromName(fileName);
        if (guessed == null && fileName != null) {
            guessed = byExtension(fileName);
        }
        return guessed != null ? guessed : OCTET_STREAM;
    }

    public static AttachmentCategory categorize(String mimeType) {
        if (mimeType == null) return AttachmentCategory.DOCUMENT;
        if (mimeType.startsWith("image/") && !mimeType.equals("image/svg+xml")) return AttachmentCategory.IMAGE;
        if (mimeType.startsWith("video/")) return AttachmentCategory.VIDEO;
        if (mimeType.startsWith("audio/")) return AttachmentCategory.AUDIO;
        return AttachmentCategory.DOCUMENT;
    }

    public static boolean isInlineSafe(String mimeType) {
        return mimeType != null && INLINE_SAFE.contains(mimeType);
    }

    private static String normalize(String type) {
        if (type == null || type.isBlank()) return null;
        int semi = type.indexOf(';');
        String t = (semi >= 0 ? type.substring(0, semi) : type).trim().toLowerCase(Locale.ROOT);
        return t.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") ? t : null;
    }

    private static String byExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return switch (fileName.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "heic" -> "image/heic";
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "ogg", "oga", "opus" -> "audio/ogg";
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "csv" -> "text/csv";
            case "txt", "log" -> "text/plain";
            case "json" -> "application/json";
            case "zip" -> "application/zip";
            case "apk" -> "application/vnd.android.package-archive";
            default -> null;
        };
    }

    private static String sniff(byte[] h) {
        if (h == null || h.length < 4) return null;
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "image/png";
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return "image/gif";
        if (h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') return "application/pdf";
        if (h.length >= 12 && ascii(h, 0, 4).equals("RIFF") && ascii(h, 8, 12).equals("WEBP")) return "image/webp";
        if (h.length >= 12 && ascii(h, 4, 8).equals("ftyp")) {
            String brand = ascii(h, 8, 12);
            if (brand.startsWith("avif")) return "image/avif";
            if (brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1")) return "image/heic";
            if (brand.startsWith("qt")) return "video/quicktime";
            if (brand.startsWith("M4A")) return "audio/mp4";
            return "video/mp4";
        }
        return null;
    }

    private static String ascii(byte[] b, int from, int to) {
        return new String(b, from, to - from, StandardCharsets.ISO_8859_1);
    }
}
