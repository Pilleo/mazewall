package capture

import (
	"bytes"
	"context"
	"testing"

	"github.com/opencontainers/go-digest"
	ocispec "github.com/opencontainers/image-spec/specs-go/v1"
	orasoci "oras.land/oras-go/v2/content/oci"
)

func TestPinnedStoreResolvesDigestWithoutMutableTag(t *testing.T) {
	ctx := context.Background()
	store, err := orasoci.New(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	body := []byte(`{"schemaVersion":2,"manifests":[]}`)
	desc := ocispec.Descriptor{MediaType: ocispec.MediaTypeImageIndex, Digest: digest.FromBytes(body), Size: int64(len(body))}
	if err := store.Push(ctx, desc, bytes.NewReader(body)); err != nil {
		t.Fatal(err)
	}
	if err := store.Tag(ctx, desc, "example:mutable"); err != nil {
		t.Fatal(err)
	}
	ref := "example@" + desc.Digest.String()
	pinned := pinnedTarget{Store: store, reference: ref}
	got, err := pinned.Resolve(ctx, ref)
	if err != nil || got.Digest != desc.Digest {
		t.Fatalf("digest resolution: %+v, %v", got, err)
	}
	if _, err := pinned.Resolve(ctx, "example:mutable"); err == nil {
		t.Fatal("accepted mutable reference")
	}
}
