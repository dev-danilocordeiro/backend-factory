package httpapi

// Version is the build version reported by GET /healthz. It defaults to "dev".
// Its full linker path is example.com/go-service/internal/httpapi.Version.
// Set it at build time with:
//
//	go build -ldflags "-X example.com/go-service/internal/httpapi.Version=1.2.3" ./cmd/server
var Version = "dev"
