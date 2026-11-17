package com.premisave.messenger.dto.request;

/** A new folder for the folder picker: its name, and the folder to make it in. */
public record StorageFolderRequest(String parent, String name) {
}
