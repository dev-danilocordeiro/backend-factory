package httpapi

// Version is the build version reported by GET /healthz. Set it at build time with
//
//	go build -ldflags "-X example.com/go-service/internal/httpapi.Version=1.2.3" ./cmd/server
//
// An unset or empty value is reported as "dev". It must stay a var holding a
// plain string literal, or -X cannot set it.
var Version = "dev"

func currentVersion() string {
	if Version == "" {
		return "dev"
	}
	return Version
}
