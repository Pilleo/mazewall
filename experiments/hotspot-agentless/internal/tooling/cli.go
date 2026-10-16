package tooling

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"
)

// Run is the command boundary shared by the collector and physical experiment snapshot.
func Run(root string, args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("collector tools command required")
	}
	switch args[0] {
	case "prepare", "patch-id":
		flags := flag.NewFlagSet(args[0], flag.ContinueOnError)
		drop := flags.Bool("drop-first-definition", false, "test-only definition-drop build")
		identity := flags.Bool("identity-observations", false, "test-only identity diagnostics")
		if err := flags.Parse(args[1:]); err != nil {
			return err
		}
		if flags.NArg() != 0 || (*drop && *identity) {
			return fmt.Errorf("identity diagnostics and definition-drop fault are separate builds")
		}
		mode := standard
		if *drop {
			mode = dropFirstDefinition
		}
		if *identity {
			mode = identityObservations
		}
		if args[0] == "prepare" {
			return prepare(root, mode)
		}
		id, err := patchID(root, mode)
		if err != nil {
			return err
		}
		fmt.Println(id)
		return nil
	case "image":
		flags := flag.NewFlagSet("image", flag.ContinueOnError)
		key := flags.String("key", "", "locked image key")
		if err := flags.Parse(args[1:]); err != nil {
			return err
		}
		if flags.NArg() != 0 {
			return fmt.Errorf("unexpected image arguments")
		}
		data, err := os.ReadFile(root + "/dependencies.lock.json")
		if err != nil {
			return err
		}
		var lock dependencyLock
		if err := json.Unmarshal(data, &lock); err != nil {
			return err
		}
		image, ok := lock.Images[*key]
		if !ok {
			return fmt.Errorf("unknown locked image: %s", *key)
		}
		fmt.Println(image)
		return nil
	case "oci-ref":
		flags := flag.NewFlagSet("oci-ref", flag.ContinueOnError)
		store := flags.String("store", "", "existing OCI store")
		name := flags.String("name", "", "exact local image name")
		if err := flags.Parse(args[1:]); err != nil {
			return err
		}
		if flags.NArg() != 0 || *store == "" || *name == "" {
			return fmt.Errorf("OCI store and exact image name required")
		}
		path, err := ownedAsset(*store, "index.json")
		if err != nil {
			return err
		}
		var index ociIndex
		if err := boundedJSON(path, &index); err != nil {
			return err
		}
		var digest string
		for _, raw := range index.Manifests {
			var entry struct {
				Digest      string
				Annotations map[string]string
			}
			if err := json.Unmarshal(raw, &entry); err != nil {
				return err
			}
			if strings.HasSuffix(entry.Annotations["org.opencontainers.image.ref.name"], "/"+*name) {
				if digest != "" {
					return fmt.Errorf("ambiguous local gadget image")
				}
				digest = entry.Digest
			}
		}
		if _, err := digestName(digest); err != nil {
			return err
		}
		fmt.Println("ghcr.io/inspektor-gadget/gadget/" + strings.SplitN(*name, ":", 2)[0] + "@" + digest)
		return nil
	case "pack":
		flags := flag.NewFlagSet("pack", flag.ContinueOnError)
		collector := flags.String("collector", "", "actual collector executable")
		store := flags.String("store", "", "local OCI store")
		image := flags.String("image", "", "digest-selected gadget image")
		output := flags.String("output", "", "new asset destination")
		if err := flags.Parse(args[1:]); err != nil {
			return err
		}
		if flags.NArg() != 0 || *collector == "" || *store == "" || *image == "" || *output == "" {
			return fmt.Errorf("pack requires collector/store/image/output")
		}
		return packAssets(*collector, *store, *image, *output)
	case "verify", "install", "release":
		flags := flag.NewFlagSet(args[0], flag.ContinueOnError)
		bundle := flags.String("bundle", "", "existing asset bundle")
		var output, version string
		if args[0] != "verify" {
			flags.StringVar(&output, "output", "", "new explicit destination")
		}
		if args[0] == "release" {
			flags.StringVar(&version, "library-version", "", "paired library version")
		}
		if err := flags.Parse(args[1:]); err != nil {
			return err
		}
		if flags.NArg() != 0 || *bundle == "" {
			return fmt.Errorf("explicit bundle required")
		}
		switch args[0] {
		case "verify":
			_, err := verifyAssets(*bundle)
			return err
		case "install":
			return installAssets(*bundle, output)
		case "release":
			return releaseDistribution(root, *bundle, output, version)
		}
	case "footprint":
		flags := flag.NewFlagSet("footprint", flag.ContinueOnError)
		baseline := flags.String("baseline", "", "actual baseline ELF")
		candidate := flags.String("candidate", "", "actual candidate ELF")
		if err := flags.Parse(args[1:]); err != nil {
			return err
		}
		if flags.NArg() != 0 || *baseline == "" || *candidate == "" {
			return fmt.Errorf("actual baseline/candidate required")
		}
		identity, err := verifiedPreparationIdentity(root)
		if err != nil {
			return err
		}
		report, err := verifyReleaseFootprint(*baseline, *candidate, identity)
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(report)
	default:
		return fmt.Errorf("unknown collector tools command: %s", args[0])
	}
	return fmt.Errorf("unsupported collector tools command")
}
