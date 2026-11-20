package com.contextcompresso.vatstash;

import org.springframework.http.HttpStatus;

/** Carries the error code and HTTP status of the VatStash contract. */
public class VatStashException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public VatStashException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public VatStashException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static VatStashException invalidRequest(String message) {
        return new VatStashException("invalid_request", HttpStatus.BAD_REQUEST, message);
    }

    public static VatStashException invalidRoot(String message) {
        return new VatStashException("invalid_root", HttpStatus.BAD_REQUEST, message);
    }

    public static VatStashException workspaceNotFound(String what) {
        return new VatStashException("workspace_not_found", HttpStatus.NOT_FOUND,
                "No registered workspace for " + what);
    }

    public static VatStashException unavailable(String message, Throwable cause) {
        return new VatStashException("vatstash_unavailable", HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    public static VatStashException disabled() {
        return new VatStashException("vatstash_disabled", HttpStatus.SERVICE_UNAVAILABLE,
                "VatStash is disabled (contextcompresso.vatstash.enabled=false)");
    }
}
