package com.example.orders.domain;

import java.util.List;

/** One page of orders plus the total number of stored orders. */
public record OrderPage(List<Order> items, int page, int size, int total) {

    public OrderPage {
        items = List.copyOf(items);
    }
}
