package com.urlshortner.codedev.exception;

public class UrlGoneException extends RuntimeException {
    public UrlGoneException() {
        super("URL has expired or been disabled");
    }
    public UrlGoneException(String message) {
        super(message);
    }
}
