package com.premisave.messenger.exception;

import lombok.Getter;

/**
 * Thrown when a call to a downstream microservice (auth-service, etc)
 * fails - connection refused, timeout, or an error response.
 *
 * Carries the service name explicitly rather than relying on parsing
 * it out of a Feign exception message or URL, so the API response can
 * expose it as a clean, structured field.
 */
@Getter
@SuppressWarnings("serial")
public class DownstreamServiceException extends RuntimeException {

    private final String serviceName;

    public DownstreamServiceException(String serviceName, String message, Throwable cause) {
        super(message, cause);
        this.serviceName = serviceName;
    }

    public DownstreamServiceException(String serviceName, String message) {
        super(message);
        this.serviceName = serviceName;
    }
}