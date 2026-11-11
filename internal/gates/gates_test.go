package gates

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"testing"
)

func TestDetect(t *testing.T) {
	cases := map[string]Stack{"go.mod": StackGo, "pom.xml": StackMaven, "build.gradle.kts": StackGradle}
	for file, want := range cases {
		dir := t.TempDir()
		os.WriteFile(filepath.Join(dir, file), nil, 0o644)
		got, err := Detect(dir)
		if err != nil || got != want {
			t.Errorf("%s: got %q %v", file, got, err)
		}
	}
	if _, err := Detect(t.TempDir()); err == nil {
		t.Error("empty dir must fail")
	}
}

func TestRunStopsAtFirstRed(t *testing.T) {
	scripts, root := t.TempDir(), t.TempDir()
	os.MkdirAll(filepath.Join(root, "svc"), 0o755)
	os.WriteFile(filepath.Join(root, "svc", "go.mod"), []byte("module x\n"), 0o644)
	// lint fails, printing an absolute path; arch and test must be skipped.
	script := "#!/bin/sh\nif [ \"$1\" = lint ]; then echo \"lint broke in $PWD/$2/a.go\"; exit 3; fi\nexit 0\n"
	os.WriteFile(filepath.Join(scripts, "go.sh"), []byte(script), 0o755)

	rep, err := Runner{ScriptsDir: scripts}.Run(context.Background(), root, "svc")
	if err != nil {
		t.Fatal(err)
	}
	if rep.Green() {
		t.Fatal("must be red")
	}
	red := rep.FirstRed()
	if red == nil || red.Gate != "lint" || red.ExitCode != 3 || red.Output != "lint broke in svc/a.go\n" {
		t.Fatalf("bad red gate: %+v", red)
	}
	if !slices.Equal(rep.Skipped, []string{"arch", "test"}) {
		t.Fatalf("skipped: %v", rep.Skipped)
	}
}

func TestRunGreen(t *testing.T) {
	scripts, root := t.TempDir(), t.TempDir()
	os.WriteFile(filepath.Join(root, "pom.xml"), nil, 0o644)
	os.WriteFile(filepath.Join(scripts, "java-maven.sh"), []byte("#!/bin/sh\nexit 0\n"), 0o755)
	rep, err := Runner{ScriptsDir: scripts}.Run(context.Background(), root, ".")
	if err != nil || !rep.Green() || len(rep.Results) != len(Order) || rep.Results[0].Command != "gates/java-maven.sh build ." {
		t.Fatalf("%+v %v", rep, err)
	}
}

func TestScopes(t *testing.T) {
	impl := ImplementScope("examples/go-service")
	got := impl.Violations([]string{
		"examples/go-service/internal/orders/service.go",
		"examples/go-service/.golangci.yml",
		"gates/go.sh",
		"README.md",
	})
	want := []string{"examples/go-service/.golangci.yml", "gates/go.sh", "README.md"}
	if !slices.Equal(got, want) {
		t.Fatalf("implement: %v", got)
	}

	test := TestScope("examples/java-service", StackMaven)
	got = test.Violations([]string{
		"examples/java-service/src/test/java/a/OrderTest.java",
		"examples/java-service/src/test/java/a/ArchitectureTest.java",
		"examples/java-service/src/main/java/a/Order.java",
	})
	if len(got) != 2 {
		t.Fatalf("test scope: %v", got)
	}

	goTest := TestScope("examples/go-service", StackGo)
	if v := goTest.Violations([]string{"examples/go-service/internal/http/h_test.go", "examples/go-service/main_test.go"}); v != nil {
		t.Fatalf("go test scope: %v", v)
	}

	if v := ReadOnlyScope().Violations([]string{"x"}); len(v) != 1 {
		t.Fatalf("read-only: %v", v)
	}
}
