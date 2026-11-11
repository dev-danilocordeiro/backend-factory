package com.example.orders.domain;

/** Thrown when an order fails validation. */
public class InvalidOrderException extends RuntimeException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
