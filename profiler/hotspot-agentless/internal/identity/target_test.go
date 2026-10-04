package identity

import (
	"math"
	"strings"
	"testing"
)

func TestRuntimeAddressRejectsOverflow(t *testing.T) {
	if _, err := RuntimeAddress(math.MaxUint64-7, 8); err == nil {
		t.Fatal("overflowed counter address accepted")
	}
	got, err := RuntimeAddress(0x17fa938, 0x700000000000)
	if err != nil || got != 0x7000017fa938 {
		t.Fatal(got, err)
	}
}

func TestProfileRefusesUnsupportedRuntime(t *testing.T) {
	valid := Profile{BuildID: "e4dad22744e32e4d71755f4a58c5cf1f1d336f87", SHA256: "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c", UseSerialGC: true}
	if err := ValidateProfile(valid); err != nil {
		t.Fatal(err)
	}
	for _, mutate := range []func(*Profile){
		func(p *Profile) { p.UseSerialGC = false },
		func(p *Profile) { p.UseCompiler = true },
		func(p *Profile) { p.BuildID = "another-build" },
		func(p *Profile) { p.SHA256 = strings.Repeat("0", 64) },
	} {
		p := valid
		mutate(&p)
		if ValidateProfile(p) == nil {
			t.Fatal("unsupported profile accepted", p)
		}
	}
}

func TestMappingParserPreservesFileIdentityAndAnonymousBSS(t *testing.T) {
	maps, err := parseMaps("700000000000-700000001000 r--p 00000000 08:01 42 /opt/java/lib/server/libjvm.so\n700000001000-700000003000 rw-p 00001000 08:01 42 /opt/java/lib/server/libjvm.so\n700000003000-700000004000 rw-p 00000000 00:00 0\n")
	if err != nil || len(maps) != 3 {
		t.Fatal(maps, err)
	}
	if maps[0].inode != 42 || maps[0].major != 8 || maps[0].minor != 1 || maps[1].offset != 0x1000 || maps[2].path != "" {
		t.Fatal(maps)
	}
	if _, err := parseMaps("not a mapping\n"); err == nil {
		t.Fatal("malformed maps accepted")
	}
}
