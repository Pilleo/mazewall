package wire

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func TestProviderAliasesCannotOverrideCanonicalIdentity(t *testing.T) {
	for _, schema := range []int{2, 3} {
		x, rows := syntheticSession()
		if schema == 2 {
			x, rows = strictSession(0)
		}
		schemaName := map[int]string{2: "schema2", 3: "schema3"}[schema]
		t.Run(schemaName+"/canonical", func(t *testing.T) {
			c, err := consumeSession(NewDecoder(x), encodedSession(t, rows))
			if err != nil || len(c.Pairs) != 1 || c.SessionID != rows[0].SessionID || c.Provider != x.Provider {
				t.Fatal("canonical provider rejected", c, err)
			}
		})
		fields := map[string]string{
			"name":              x.Provider.Name,
			"gadget_revision":   x.Provider.GadgetRevision,
			"profiler_revision": x.Provider.ProfilerRevision,
			"patch_sha256":      x.Provider.PatchSHA256,
		}
		for field, value := range fields {
			for _, row := range []int{0, 1, 3} {
				name := schemaName + "/" + field + "/" + rows[row].Kind
				good, _ := json.Marshal(value)
				bad, _ := json.Marshal(strings.Repeat("9", len(value)))
				key, _ := json.Marshal(field)
				needle := append(append(bytes.Clone(key), ':'), good...)
				canonical := append(append(bytes.Clone(key), ':'), bad...)
				if bytes.Count(encodedSession(t, rows)[row], needle) != 1 {
					t.Fatal("provider mutation must replace exactly one canonical field", name)
				}
				t.Run(name+"/wrong-canonical", func(t *testing.T) {
					encoded := encodedSession(t, rows)
					encoded[row] = bytes.Replace(encoded[row], needle, canonical, 1)
					d := NewDecoder(x)
					if _, err := consumeSession(d, encoded); !errors.Is(err, ErrIdentity) {
						t.Fatal("contradictory canonical identity admitted", err)
					}
					requireNoCapture(t, d, ErrIdentity)
				})
				t.Run(name+"/unknown-extension", func(t *testing.T) {
					unknown, _ := json.Marshal("future_" + field)
					extra := append(append(bytes.Clone(unknown), ':'), bad...)
					replacement := append(append(bytes.Clone(needle), ','), extra...)
					encoded := encodedSession(t, rows)
					encoded[row] = bytes.Replace(encoded[row], needle, replacement, 1)
					c, err := consumeSession(NewDecoder(x), encoded)
					if err != nil || len(c.Pairs) != 1 || c.Provider != x.Provider || !bytes.Contains(c.RawJSONL, extra) {
						t.Fatal("genuine unknown provider extension rejected or lost", c, err)
					}
				})
				for _, aliasFirst := range []bool{false, true} {
					order := map[bool]string{false: "alias-last", true: "alias-first"}[aliasFirst]
					t.Run(name+"/"+order, func(t *testing.T) {
						alias, _ := json.Marshal(strings.ToUpper(field))
						extra := append(append(bytes.Clone(alias), ':'), good...)
						replacement := append(append(bytes.Clone(canonical), ','), extra...)
						if aliasFirst {
							replacement = append(append(bytes.Clone(extra), ','), canonical...)
						}
						encoded := encodedSession(t, rows)
						encoded[row] = bytes.Replace(encoded[row], needle, replacement, 1)
						d := NewDecoder(x)
						c, err := consumeSession(d, encoded)
						if !errors.Is(err, ErrJSON) || len(c.Pairs) != 0 || c.SessionID != "" {
							t.Fatalf("want ErrJSON and no capture, got %v (pairs=%d session=%q)", err, len(c.Pairs), c.SessionID)
						}
						requireNoCapture(t, d, ErrJSON)
					})
				}
			}
		}
	}
}
