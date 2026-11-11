package com.example.orders.domain;

/** Thrown when a page or size request parameter is out of range. */
public final class InvalidPageException extends RuntimeException {

    public InvalidPageException(String message) {
        super(message);
    }
}
