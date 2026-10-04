package capture

import (
	"context"
	"fmt"
	"strings"

	ocispec "github.com/opencontainers/image-spec/specs-go/v1"
	orasoci "oras.land/oras-go/v2/content/oci"
)

// ORAS local stores resolve digest-only references, whereas Gadget passes the
// full image name. Adapt only resolution; fetching remains in the owned store.
type pinnedTarget struct {
	*orasoci.Store
	reference string
}

func (p pinnedTarget) Resolve(ctx context.Context, reference string) (ocispec.Descriptor, error) {
	if reference != p.reference {
		return ocispec.Descriptor{}, fmt.Errorf("unexpected image reference %q", reference)
	}
	_, hash, ok := strings.Cut(reference, "@")
	if !ok {
		return ocispec.Descriptor{}, fmt.Errorf("image reference lacks digest")
	}
	desc, err := p.Store.Resolve(ctx, hash)
	if err != nil {
		return ocispec.Descriptor{}, err
	}
	if desc.Digest.String() != hash {
		return ocispec.Descriptor{}, fmt.Errorf("resolved gadget digest mismatch")
	}
	return desc, nil
}
