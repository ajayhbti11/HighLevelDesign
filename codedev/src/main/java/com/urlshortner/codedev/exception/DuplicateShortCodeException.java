package com.urlshortner.codedev.exception;

public class DuplicateShortCodeException extends RuntimeException {
    public DuplicateShortCodeException() {
        super("Short code already exists");
    }
    public DuplicateShortCodeException(String message) {
        super(message);
    }
}
