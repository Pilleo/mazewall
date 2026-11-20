package capture

import (
	"errors"
	"fmt"
	"os"
	"testing"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/utils/host"
	"golang.org/x/sys/unix"
)

func TestLocalRuntimeAcceptsNonRootHostInitialization(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("requires a non-root test process to detect the upstream UID-only guard")
	}
	calls := 0
	rt, err := newLocalRuntime(func(config host.Config) error {
		calls++
		if config.AutoMountFilesystems {
			t.Fatal("collector must not mount host filesystems")
		}
		return nil
	})
	if err != nil || rt == nil {
		t.Fatalf("non-root host initialization: runtime=%v error=%v", rt, err)
	}
	if calls != 1 {
		t.Fatalf("host initialization calls=%d, want 1", calls)
	}
	if err := rt.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestLocalRuntimePreservesHostInitializationFailure(t *testing.T) {
	for _, cause := range []error{unix.EPERM, unix.EACCES, errors.New("host namespace mismatch")} {
		t.Run(cause.Error(), func(t *testing.T) {
			failure := fmt.Errorf("host initialization: %w", cause)
			rt, err := newLocalRuntime(func(host.Config) error { return failure })
			if rt != nil || err != failure || !errors.Is(err, cause) {
				t.Fatalf("host failure lost: runtime=%v error=%v, want original %v", rt, err, failure)
			}
		})
	}
}
