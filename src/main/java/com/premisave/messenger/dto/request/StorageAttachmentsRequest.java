package com.premisave.messenger.dto.request;

/**
 * Where attachments are kept, as an administrator sends it. LOCAL uses {@code root}. NETWORK uses the mount path and the
 * folder inside the share.
 */
public record StorageAttachmentsRequest(
        String mode,
        String root,
        String networkMountPath,
        String networkDirectory,
        Boolean networkRequireMount) {
}
