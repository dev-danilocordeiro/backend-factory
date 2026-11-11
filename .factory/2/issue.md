# POST /orders accepts zero or negative quantity

Source: github https://github.com/dev-danilocordeiro/backend-factory/issues/2

Steps to reproduce:

```
curl -X POST localhost:8080/orders -d '{"customer":"c1","sku":"A","quantity":-3}'
```

Actual: `201 Created`, and the order is stored with quantity -3.

Expected: `400 Bad Request` with an error message, and nothing stored. Quantity must be at least 1.
