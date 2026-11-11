package gates

import (
	"path"
	"strings"
)

// Scope says which paths a stage may change. Patterns use path.Match syntax
// per segment, plus a trailing "/**" meaning "anything below".
type Scope struct {
	Allow []string
	Deny  []string // checked first; protects the gates from the agent
}

// Violations returns the changed paths outside the scope.
func (s Scope) Violations(changed []string) []string {
	var bad []string
	for _, f := range changed {
		if matchAny(s.Deny, f) || !matchAny(s.Allow, f) {
			bad = append(bad, f)
		}
	}
	return bad
}

// ProtectedPaths are never writable by any agent: the pipeline's own
// configuration and the files that define the gates.
var ProtectedPaths = []string{
	".claude/**",
	".github/**",
	"gates/**",
	"cmd/**",
	"internal/**",
	"**/.golangci.yml",
	"**/checkstyle.xml",
	"**/ArchitectureTest.java",
}

// ImplementScope lets the implement stage change the service, nothing else.
func ImplementScope(service string) Scope {
	return Scope{Allow: []string{strings.TrimSuffix(service, "/") + "/**"}, Deny: ProtectedPaths}
}

// TestScope lets the test stage change test files of the service only.
func TestScope(service string, stack Stack) Scope {
	svc := strings.TrimSuffix(service, "/")
	allow := []string{svc + "/**/*_test.go", svc + "/*_test.go"}
	if stack != StackGo {
		allow = []string{svc + "/src/test/**"}
	}
	return Scope{Allow: allow, Deny: ProtectedPaths}
}

// ReadOnlyScope allows no changes at all.
func ReadOnlyScope() Scope { return Scope{} }

func matchAny(patterns []string, f string) bool {
	for _, p := range patterns {
		if match(p, f) {
			return true
		}
	}
	return false
}

// match supports "**" as a whole segment matching zero or more segments.
func match(pattern, name string) bool {
	return matchSegs(strings.Split(pattern, "/"), strings.Split(name, "/"))
}

func matchSegs(p, n []string) bool {
	for len(p) > 0 {
		if p[0] == "**" {
			if len(p) == 1 {
				return len(n) > 0
			}
			for i := 0; i <= len(n); i++ {
				if matchSegs(p[1:], n[i:]) {
					return true
				}
			}
			return false
		}
		if len(n) == 0 {
			return false
		}
		if ok, _ := path.Match(p[0], n[0]); !ok {
			return false
		}
		p, n = p[1:], n[1:]
	}
	return len(n) == 0
}
