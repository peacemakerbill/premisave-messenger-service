package com.premisave.messenger.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Where files are stored, as an administrator set it from the dashboard. One record, with the id "storage".
 *
 * Each part is empty until an administrator saves it. While it is empty the .env values apply. Once saved, the dashboard
 * value wins, and "Reset to .env" empties it again.
 */
@Document(collection = "storage_settings")
public class StorageSettings {

    public static final String ID = "storage";

    @Id
    private String id = ID;

    /** Attachments, group photos and status media. Null means the .env values apply. */
    private Attachments attachments;

    /** Call recordings. Null means the .env values apply. */
    private Recordings recordings;

    /**
     * Folders attachments were stored in before an administrator moved them. Files are looked up there when they are not in
     * the current folder, so changing the folder never loses a file. Most recent first.
     */
    private List<String> previousAttachmentBases = new ArrayList<>();

    private String updatedBy;
    private LocalDateTime updatedAt;

    public static class Attachments {
        /** LOCAL or NETWORK. */
        private String mode;
        /** LOCAL: the folder, as a full path. */
        private String root;
        /** NETWORK: where the share is mounted, as a full path. */
        private String networkMountPath;
        /** NETWORK: the folder inside the share. */
        private String networkDirectory;
        /** NETWORK: refuse to use the folder unless it really is a network share. */
        private boolean networkRequireMount = true;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public String getNetworkMountPath() { return networkMountPath; }
        public void setNetworkMountPath(String networkMountPath) { this.networkMountPath = networkMountPath; }
        public String getNetworkDirectory() { return networkDirectory; }
        public void setNetworkDirectory(String networkDirectory) { this.networkDirectory = networkDirectory; }
        public boolean isNetworkRequireMount() { return networkRequireMount; }
        public void setNetworkRequireMount(boolean networkRequireMount) { this.networkRequireMount = networkRequireMount; }
    }

    public static class Recordings {
        /** A folder inside the recorder's shared folder. Empty means the shared folder itself. */
        private String subfolder = "";
        /** Recording is refused when less than this many megabytes are free. 0 turns the check off. */
        private int minFreeMb;

        public String getSubfolder() { return subfolder; }
        public void setSubfolder(String subfolder) { this.subfolder = subfolder; }
        public int getMinFreeMb() { return minFreeMb; }
        public void setMinFreeMb(int minFreeMb) { this.minFreeMb = minFreeMb; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Attachments getAttachments() { return attachments; }
    public void setAttachments(Attachments attachments) { this.attachments = attachments; }
    public Recordings getRecordings() { return recordings; }
    public void setRecordings(Recordings recordings) { this.recordings = recordings; }
    public List<String> getPreviousAttachmentBases() { return previousAttachmentBases; }
    public void setPreviousAttachmentBases(List<String> previousAttachmentBases) {
        this.previousAttachmentBases = previousAttachmentBases == null ? new ArrayList<>() : previousAttachmentBases;
    }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
