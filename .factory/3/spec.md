# Spec: Paginate GET /orders

## Summary
`GET /orders` currently returns every stored order as a bare JSON array. After this change it accepts two optional query parameters. `page` is 0-based and defaults to 0. `size` defaults to 20 and may be at most 100. The response becomes a JSON object: `{"items": [...], "page": <n>, "size": <n>, "total": <n>}`, where `total` counts all stored orders. Invalid `page` or `size` values return HTTP 400 with the service's usual `{"error": "..."}` body.

## Acceptance criteria
1. Given 57 stored orders (`ord-1` … `ord-57`), when the client sends `GET /orders` with no query string, then the status is 200 and the body is a JSON object with `page` = 0, `size` = 20, `total` = 57. Its `items` array holds 20 orders, `ord-1` through `ord-20`, in creation order.
2. Given 57 stored orders, when the client sends `GET /orders?page=1&size=20`, then the status is 200 and `items` holds `ord-21` through `ord-40`, with `page` = 1, `size` = 20, `total` = 57.
3. Given 57 stored orders, when the client sends `GET /orders?page=2&size=20`, then the status is 200 and `items` holds the 17 orders `ord-41` through `ord-57`, with `page` = 2, `size` = 20, `total` = 57.
4. Given 57 stored orders, when the client sends `GET /orders?page=3&size=20` (past the last page), then the status is 200 and `items` is an empty array, with `page` = 3, `size` = 20, `total` = 57.
5. Given no stored orders, when the client sends `GET /orders`, then the status is 200 and the body is `{"items": [], "page": 0, "size": 20, "total": 0}`.
6. Given 57 stored orders, when the client sends `GET /orders?size=100`, then the status is 200 and `items` holds all 57 orders, with `size` = 100.
7. Given 5 stored orders, when the client sends `GET /orders?size=2`, then the status is 200 and `items` holds `ord-1` and `ord-2`, with `page` = 0, `size` = 2, `total` = 5. The missing `page` defaults to 0.
8. Given 5 stored orders, when the client sends `GET /orders?page=1`, then the status is 200 and the body has `page` = 1, `size` = 20, `total` = 5, with an empty `items` array. The missing `size` defaults to 20.
9. Given any stored orders, when the client sends `GET /orders?page=-1`, then the status is 400 and the body is a JSON object with a string `error` field.
10. Given any stored orders, when the client sends `GET /orders?size=0`, then the status is 400 with an `error` body.
11. Given any stored orders, when the client sends `GET /orders?size=101`, then the status is 400 with an `error` body.
12. Given any stored orders, when the client sends `GET /orders?size=-5`, then the status is 400 with an `error` body.
13. Given any stored orders, when the client sends `GET /orders?page=abc`, `GET /orders?size=ten`, `GET /orders?page=1.5`, `GET /orders?page=` or `GET /orders?size=`, then each request returns 400 with an `error` body.
14. Given a stored order `ord-1`, when the client sends `GET /orders/ord-1`, then the status is 200 and the body is the single order object, unchanged from today. When the client sends `POST /orders` with a valid order, then the status is 201, unchanged from today.
15. Given any stored orders, when the client sends `GET /orders?page=0&size=20&foo=bar`, then the status is 200 and the unknown parameter `foo` is ignored.
16. The existing `ArchitectureTest` layer rules still pass.

## Out of scope
- Sorting or filtering parameters, and any `sort` or `order` options.
- Cursor-based pagination, `Link` headers, and `next`/`prev` URLs or a `totalPages` field in the body.
- Paginating any endpoint other than `GET /orders`.
- Keeping the old bare-array response for existing clients. This is a breaking change to the response shape, and the existing test that reads the body as a `List` will need updating.
- Persistence changes. The in-memory repository stays.

## Open questions
- I assumed a page past the last one returns 200 with an empty `items` array, not 404 (criterion 4).
- I assumed `items` keep creation order (`ord-1`, `ord-2`, …), because that is how `findAll` behaves today.
- I assumed that a parameter with an empty value (`page=`) counts as a non-number and returns 400.
- I assumed that unknown query parameters are ignored.
- How to handle a repeated parameter (`page=1&page=2`) is left undefined. No criterion depends on it.
- The issue does not specify the wording of the `error` message, so the criteria only require a string `error` field.

STATUS: READY
