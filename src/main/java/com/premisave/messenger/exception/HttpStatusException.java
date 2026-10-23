package com.premisave.messenger.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * An error the caller can act on, carrying its own HTTP status and code.
 * Rendered by HttpStatusExceptionHandler in the same shape as every other
 * error response.
 */
@Getter
@SuppressWarnings("serial")
public class HttpStatusException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public HttpStatusException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static HttpStatusException unauthorized(String message) {
        return new HttpStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static HttpStatusException forbidden(String message) {
        return new HttpStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static HttpStatusException notFound(String code, String message) {
        return new HttpStatusException(HttpStatus.NOT_FOUND, code, message);
    }

    public static HttpStatusException tooLarge(String message) {
        return new HttpStatusException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", message);
    }

    public static HttpStatusException storageUnavailable(String message) {
        return new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", message);
    }
}
