# Add GET /healthz with the build version

Source: github https://github.com/dev-danilocordeiro/backend-factory/issues/1

Load balancers need a health endpoint.

`GET /healthz` should return `200` with JSON `{"status":"ok","version":"<version>"}`.

The version should come from a variable that the build can set with `-ldflags "-X ..."`. When it is not set, report `"dev"`.
