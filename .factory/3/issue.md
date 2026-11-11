# Paginate GET /orders

Source: github https://github.com/dev-danilocordeiro/backend-factory/issues/3

`GET /orders` returns every order. Lists will get long.

Add query parameters `page` (0-based, default 0) and `size` (default 20, max 100).

Response body becomes:

```json
{"items": [...], "page": 0, "size": 20, "total": 57}
```

`total` is the number of orders overall. Invalid values (negative page, size 0 or above 100, non-numbers) return 400.
