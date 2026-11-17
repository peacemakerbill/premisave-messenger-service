package com.premisave.messenger.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Tells whether a folder is on a network file system. Reads /proc/self/mountinfo and finds the most specific mount that
 * contains the path, so a local folder on a separate partition does not pass for a share. Verifies on Linux only; elsewhere it
 * falls back to "the directory exists". Shared by attachment storage, recording storage and the settings checks.
 */
public final class MountCheck {

    private static final System.Logger LOG = System.getLogger(MountCheck.class.getName());

    private static final Set<String> NETWORK_FS = Set.of(
            "cifs", "smb3", "smbfs", "nfs", "nfs4", "9p", "ceph", "glusterfs",
            "fuse.sshfs", "fuse.rclone", "fuse.davfs", "davfs", "fuse.glusterfs");

    private MountCheck() { }

    public static boolean isOnNetworkShare(Path path) {
        return isOnNetworkShare(path, Paths.get("/proc/self/mountinfo"));
    }

    /** The mount table is a parameter so the check can be tested with a sample file. */
    static boolean isOnNetworkShare(Path path, Path mountInfo) {
        if (!Files.isReadable(mountInfo)) {
            LOG.log(System.Logger.Level.WARNING, "Cannot verify mounts on this OS, only checking that {0} exists", path);
            return Files.isDirectory(path);
        }
        try {
            if (!Files.isDirectory(path)) return false;
            Path real = path.toRealPath();
            Path bestMount = null;
            String bestType = null;
            for (String line : Files.readAllLines(mountInfo)) {
                // mountinfo: id parent major:minor root mountpoint opts [optional...] - fstype source superopts
                int dash = line.indexOf(" - ");
                String[] pre = (dash >= 0 ? line.substring(0, dash) : line).split(" ");
                String[] post = dash >= 0 ? line.substring(dash + 3).split(" ") : new String[0];
                if (pre.length < 5 || post.length < 1) continue;
                Path mountPoint = Paths.get(unescape(pre[4]));
                if (real.startsWith(mountPoint)
                        && (bestMount == null || mountPoint.getNameCount() >= bestMount.getNameCount())) {
                    bestMount = mountPoint;
                    bestType = post[0];
                }
            }
            if (bestType == null) return false;
            boolean network = NETWORK_FS.contains(bestType);
            if (!network) {
                LOG.log(System.Logger.Level.WARNING, "{0} is on a ''{1}'' file system, not a network share", real, bestType);
            }
            return network;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Could not read the mount table: {0}", e.getMessage());
            return false;
        }
    }

    /** mountinfo escapes space, tab, newline and backslash as octal. */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 3 < s.length() && s.substring(i + 1, i + 4).matches("[0-7]{3}")) {
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
