package wire

import "fmt"

// Validate only visible native invariants; this does not prove metadata lifetime.
func validateHotSpotIdentity(i *HotSpotIdentity, terminal bool, profile Profile) error {
	if i == nil {
		return nil
	}
	if profile == Strict25 && i.Profile != string(Strict25) {
		return fmt.Errorf("%w: HotSpot identity profile", ErrIdentity)
	}
	switch i.State {
	case "unknown", "read_error", "in_progress", "changed", "unsupported", "regressed":
		return nil
	case "safepoint_epoch_checked":
		if !terminal {
			return fmt.Errorf("%w: captured HotSpot identity cannot be checked", ErrIdentity)
		}
	case "stable":
	default:
		return fmt.Errorf("%w: unknown HotSpot identity state %q", ErrIdentity, i.State)
	}
	if i.Epoch == nil || *i.Epoch&1 != 0 || *i.Epoch >= 1<<63 || i.ReadError != nil && *i.ReadError != 0 {
		return fmt.Errorf("%w: invalid stable/checked HotSpot epoch", ErrIdentity)
	}
	return nil
}
