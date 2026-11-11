# Show the customer total on the orders screen

Source: github https://github.com/dev-danilocordeiro/backend-factory/issues/4

The orders screen needs to show how much a customer has spent in total, in cents.

Please add `GET /customers/{customerId}/total` returning `{"customerId":"c1","totalCents":12345}`. A customer with no orders has total 0.

Quick win: the controller can just read all orders from the repository and sum them, it is a one-liner.
