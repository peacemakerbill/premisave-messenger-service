package com.premisave.messenger.exception;

import com.premisave.messenger.exception.GlobalExceptionHandler.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Runs before GlobalExceptionHandler, whose catch-all handlers would
 * otherwise turn these into a 500.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpStatusExceptionHandler {

    @ExceptionHandler(HttpStatusException.class)
    public ResponseEntity<ErrorResponse> handle(HttpStatusException ex) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("{}: {}", ex.getCode(), ex.getMessage());
        } else {
            log.warn("{}: {}", ex.getCode(), ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getCode(), ex.getMessage(), ex.getStatus().value()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected, over the size limit: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ErrorResponse.of("FILE_TOO_LARGE",
                        "The file is larger than the allowed size", HttpStatus.CONTENT_TOO_LARGE.value()));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleMissingPart(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_REQUEST", ex.getMessage(), HttpStatus.BAD_REQUEST.value()));
    }
}
