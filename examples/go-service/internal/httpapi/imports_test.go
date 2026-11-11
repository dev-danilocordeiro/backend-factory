package httpapi_test

import (
	"go/parser"
	"go/token"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// TestHTTPAPIDoesNotImportStore keeps the HTTP layer talking only to order.Service.
func TestHTTPAPIDoesNotImportStore(t *testing.T) {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		if strings.HasSuffix(f, "_test.go") {
			continue
		}
		parsed, err := parser.ParseFile(token.NewFileSet(), f, nil, parser.ImportsOnly)
		if err != nil {
			t.Fatal(err)
		}
		for _, imp := range parsed.Imports {
			if path, _ := strconv.Unquote(imp.Path.Value); path == "example.com/go-service/internal/store" {
				t.Errorf("%s imports %s", f, path)
			}
		}
	}
}
