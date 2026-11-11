package httpapi_test

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

const pkgImportPath = "example.com/go-service/internal/httpapi"

// parseNonTestFiles parses every non-test .go file in this package directory.
func parseNonTestFiles(t *testing.T) (*token.FileSet, []*ast.File) {
	t.Helper()
	paths, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatalf("glob: %v", err)
	}
	fset := token.NewFileSet()
	var files []*ast.File
	for _, p := range paths {
		if strings.HasSuffix(p, "_test.go") {
			continue
		}
		src, err := os.ReadFile(p)
		if err != nil {
			t.Fatalf("read %s: %v", p, err)
		}
		f, err := parser.ParseFile(fset, p, src, parser.ParseComments)
		if err != nil {
			t.Fatalf("parse %s: %v", p, err)
		}
		files = append(files, f)
	}
	if len(files) == 0 {
		t.Fatal("no non-test source files found")
	}
	return fset, files
}

// Criterion 4: Version is a package-level string var whose doc comment
// names the full linker path to pass to -X, and defaults to "dev".
func TestVersionVarDocumentsLinkerPath(t *testing.T) {
	_, files := parseNonTestFiles(t)
	for _, f := range files {
		for _, decl := range f.Decls {
			gd, ok := decl.(*ast.GenDecl)
			if !ok || gd.Tok != token.VAR {
				continue
			}
			for _, spec := range gd.Specs {
				vs := spec.(*ast.ValueSpec)
				for i, name := range vs.Names {
					if name.Name != "Version" {
						continue
					}
					doc := vs.Doc
					if doc == nil {
						doc = gd.Doc
					}
					if doc == nil {
						t.Fatal("Version has no doc comment")
					}
					full := pkgImportPath + ".Version"
					if !strings.Contains(doc.Text(), full) {
						t.Fatalf("Version doc comment does not mention %q:\n%s", full, doc.Text())
					}
					if len(vs.Values) <= i {
						t.Fatal("Version has no initial value")
					}
					lit, ok := vs.Values[i].(*ast.BasicLit)
					if !ok || lit.Kind != token.STRING {
						t.Fatalf("Version must be initialised with a string literal (needed for -X)")
					}
					if v, _ := strconv.Unquote(lit.Value); v != "dev" {
						t.Fatalf("Version default = %q, want \"dev\"", v)
					}
					if vs.Type != nil {
						if id, ok := vs.Type.(*ast.Ident); !ok || id.Name != "string" {
							t.Fatalf("Version type must be string")
						}
					}
					return
				}
			}
		}
	}
	t.Fatal("package-level var Version not found in httpapi")
}

// Criterion 6: non-test code in internal/httpapi must not import internal/store.
func TestNonTestCodeDoesNotImportStore(t *testing.T) {
	fset, files := parseNonTestFiles(t)
	for _, f := range files {
		for _, imp := range f.Imports {
			p, _ := strconv.Unquote(imp.Path.Value)
			if p == "example.com/go-service/internal/store" || strings.HasPrefix(p, "example.com/go-service/internal/store/") {
				t.Errorf("%s imports %s", fset.Position(imp.Pos()).Filename, p)
			}
		}
	}
}
