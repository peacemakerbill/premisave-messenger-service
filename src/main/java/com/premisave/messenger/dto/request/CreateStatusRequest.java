package com.premisave.messenger.dto.request;

import lombok.Data;

/** A text status sent as JSON. Photo, video and audio statuses are sent as multipart with a file. */
@Data
public class CreateStatusRequest {

    private String text;

    // Optional hex colour the text sits on, for example #128C7E
    private String backgroundColor;

    // Hours the status stays. Left out, it uses status.default-hours. Never above status.max-hours.
    private Integer durationHours;
}