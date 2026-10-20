package candidate

import "testing"

func TestApplicationPrefixesMatchExactOwnerAndMethod(t *testing.T) {
	for _, test := range []struct {
		name     string
		names    []string
		useful   int
		rejected bool
	}{
		{"renderer", []string{"libc.openat", "void Fixture.leaf(java.nio.file.Path)", "void Fixture.routeA(java.nio.file.Path)"}, 1, false},
		{"different owner", []string{"void WrongFixture.leafCorrupt()", "void WrongFixture.routeACorrupt()"}, 0, false},
		{"native only", []string{"libc.openat"}, 0, false},
		{"unnamed native frame", []string{"", "void Fixture.leaf()", "void Fixture.routeA()"}, 1, false},
		{"malformed display", []string{"prefix void Fixture.leaf()", "Fixture.routeA() trailing"}, 0, false},
		{"wrong leaf", []string{"void Fixture.leafCorrupt()", "void Fixture.routeA()"}, 0, true},
		{"wrong route", []string{"void Fixture.leaf()", "void Fixture.routeACorrupt()"}, 0, true},
		{"reordered", []string{"Fixture.routeA()", "Fixture.leaf()"}, 0, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			manifest := decode[RuntimeManifest](t, runtimeManifestJSON)
			rows := decode[[]Record](t, runtimeRowsJSON)
			rows[1].Evidence.Frames = []Frame{}
			for _, name := range test.names {
				rows[1].Evidence.Frames = append(rows[1].Evidence.Frames, Frame{FunctionName: name})
			}
			result, err := VerifyRuntime(manifest, rows)
			if (err != nil) != test.rejected || err == nil && result.Useful != test.useful {
				t.Fatalf("incorrect method decision: %+v %v", result, err)
			}
			if test.rejected {
				virtual := decode[[]Record](t, virtualRowsJSON)
				virtual[1].Evidence.Frames = rows[1].Evidence.Frames
				if _, err := VerifyVirtual(decode[map[string]VirtualOracle](t, virtualOracleJSON), virtual, virtualTarget); err == nil {
					t.Fatal("virtual verifier accepted wrong exact prefix")
				}
			}
		})
	}
}

func TestApplicationPrefixSupportsExactConstructorsAndRejectsMixedManifestOwners(t *testing.T) {
	frames := []Frame{{FunctionName: "void pkg.Fixture$Nested.<init>()"}, {FunctionName: "pkg.Fixture$Nested.<clinit>()"}}
	if matched, err := routeMatches(frames, []string{"pkg.Fixture$Nested.<init>", "pkg.Fixture$Nested.<clinit>"}); err != nil || !matched {
		t.Fatalf("constructor prefix: %v %v", matched, err)
	}
	if _, err := routeMatches([]Frame{{FunctionName: "Fixture.leaf()"}, {FunctionName: "Fixture.Other.routeA()"}}, []string{"Fixture.leaf", "Fixture.Other.routeA"}); err == nil {
		t.Fatal("mixed expected owners admitted")
	}
}
