package com.premisave.messenger.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Path rules for the folders an administrator can choose. No Spring and no disk writes here, so the rules are easy to
 * test: an administrator can only point storage at a folder inside one of the allowed roots from .env, and a folder name
 * can never contain "..", a leading slash or odd characters.
 */
public final class StoragePaths {

    /** One folder name, or several joined with a slash: letters, digits, dot, dash, underscore and space. */
    private static final Pattern FOLDER_NAME = Pattern.compile("^[A-Za-z0-9._ -]{1,64}(/[A-Za-z0-9._ -]{1,64}){0,4}$");

    private StoragePaths() { }

    /** A full path, cleaned up. Throws a plain message when it is empty, or not a full path. */
    public static Path absolute(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is empty");
        }
        String text = value.trim();
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(what + " has characters that are not allowed");
        }
        Path p;
        try {
            p = Paths.get(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(what + " is not a valid path");
        }
        if (!p.isAbsolute()) {
            throw new IllegalArgumentException(what + " must be a full path, starting with /");
        }
        return p.normalize();
    }

    /**
     * A folder name inside another folder, for example "premisave-attachments" or "2026/nas". Blank gives "". Throws a plain
     * message for ".." , a leading slash or characters outside letters, digits, dot, dash, underscore and space.
     */
    public static String folderName(String value, String what) {
        if (value == null || value.isBlank()) return "";
        String text = value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        if (text.isEmpty()) return "";
        if (text.startsWith("/") || !FOLDER_NAME.matcher(text).matches()) {
            throw new IllegalArgumentException(what + " can use letters, digits, dot, dash, underscore and space, "
                    + "with a slash between folders, and no leading slash");
        }
        for (String part : text.split("/")) {
            if (part.equals(".") || part.equals("..") || part.isBlank()) {
                throw new IllegalArgumentException(what + " cannot contain '.' or '..' as a folder");
            }
        }
        return text;
    }

    /** True when the path is one of the roots or inside one. Both are compared as cleaned-up paths. */
    public static boolean isInside(Path path, List<Path> roots) {
        Path p = path.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (p.startsWith(root.toAbsolutePath().normalize())) return true;
        }
        return false;
    }

    /**
     * The same check on real paths, so a link inside an allowed folder cannot lead out of it. The folder does not have to exist
     * yet: its nearest existing parent is resolved, and the folders still to be made are added to that. Nothing is created.
     * A root that does not exist is compared as it is written.
     */
    public static boolean isReallyInside(Path folder, List<Path> roots) throws IOException {
        Path wanted = folder.toAbsolutePath().normalize();
        Path existing = wanted;
        while (existing != null && !java.nio.file.Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) return false;
        Path real = existing.toRealPath().resolve(existing.relativize(wanted)).normalize();
        List<Path> realRoots = new ArrayList<>();
        for (Path root : roots) {
            Path r = root.toAbsolutePath().normalize();
            realRoots.add(java.nio.file.Files.exists(r) ? r.toRealPath() : r);
        }
        return isInside(real, realRoots);
    }

    /** Splits a comma separated list of full paths from .env. Entries that are not full paths are skipped. */
    public static List<Path> parseRoots(String commaSeparated) {
        List<Path> out = new ArrayList<>();
        if (commaSeparated == null) return out;
        for (String part : commaSeparated.split(",")) {
            if (part.isBlank()) continue;
            try {
                Path p = Paths.get(part.trim());
                if (p.isAbsolute() && !out.contains(p.normalize())) out.add(p.normalize());
            } catch (RuntimeException ignored) {
                // an invalid entry in .env is skipped, not fatal
            }
        }
        return out;
    }
}
