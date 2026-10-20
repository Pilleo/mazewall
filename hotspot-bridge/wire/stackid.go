package wire

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
)

type Scope struct {
	IdentityScope, ImageID, JVMBuildID, JVMSHA256 string
	Profile                                       Profile
	Provider                                      Provider
}

// CanonicalStackID groups observable provider content, not universal Java method
// or class-loader identities. Occurrence identity is intentionally not an input.
func CanonicalStackID(scope Scope, frames []Frame) (string, error) {
	if len(frames) == 0 {
		return "", errors.New("empty observed stack")
	}
	owned := append([]Frame(nil), frames...)
	for i := range owned {
		if owned[i].ErrorFrame {
			return "", errors.New("error frame is not a useful stack")
		}
		// Unknown raw fields may contain occurrence IDs; only typed content is canonical.
		owned[i].Extensions = nil
		if owned[i].Mapping != nil {
			m := *owned[i].Mapping
			m.Start, m.End = 0, 0
			m.Extensions = nil
			owned[i].Mapping = &m
		}
	}
	b, err := json.Marshal(struct {
		Scope  Scope
		Frames []Frame
	}{scope, owned})
	if err != nil {
		return "", err
	}
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:]), nil
}
