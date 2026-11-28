package tooling

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"maps"
	"os"
	"path/filepath"
	"slices"
)

const (
	preparedMarkerName  = ".mw-prepared.json"
	sourceInventoryName = ".mw-source-inventory.json"
)

type sourceInventory map[string]string

func inventoryPreparedSources(directory string) (sourceInventory, error) {
	inventory := make(sourceInventory)
	err := filepath.WalkDir(directory, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if path == directory && !entry.IsDir() {
			return fmt.Errorf("prepared dependency is not a directory: %s", path)
		}
		if entry.IsDir() {
			return nil
		}
		if !entry.Type().IsRegular() {
			return fmt.Errorf("unsupported prepared dependency member: %s", path)
		}
		relative, err := filepath.Rel(directory, path)
		if err != nil {
			return err
		}
		switch relative {
		case preparedMarkerName, sourceInventoryName:
			return nil
		}
		digest, err := fileHash(path)
		if err != nil {
			return fmt.Errorf("hash prepared dependency member: %w", err)
		}
		inventory[filepath.ToSlash(relative)] = digest
		return nil
	})
	return inventory, err
}

func recordPreparedDependency(directory string, provenance map[string]any) error {
	inventory, err := inventoryPreparedSources(directory)
	if err != nil {
		return err
	}
	if err := writeJSON(filepath.Join(directory, sourceInventoryName), inventory); err != nil {
		return err
	}
	return writeJSON(filepath.Join(directory, preparedMarkerName), provenance)
}

func verifiedPreparedDependency(directory string, provenance map[string]any) (bool, error) {
	prepared, err := markerMatches(filepath.Join(directory, preparedMarkerName), provenance)
	if err != nil || !prepared {
		return false, err
	}
	actual, err := inventoryPreparedSources(directory)
	if err != nil {
		return false, err
	}
	path := filepath.Join(directory, sourceInventoryName)
	data, err := os.ReadFile(path)
	if err != nil {
		return false, fmt.Errorf("read prepared source inventory (move the generated dependency aside and prepare again): %w", err)
	}
	var expected sourceInventory
	if err := json.Unmarshal(data, &expected); err != nil {
		return false, fmt.Errorf("decode prepared source inventory %s: %w", path, err)
	}
	for _, name := range slices.Sorted(maps.Keys(expected)) {
		digest, found := actual[name]
		if !found || digest != expected[name] {
			return false, fmt.Errorf("missing or changed prepared dependency member: %s", filepath.Join(directory, name))
		}
	}
	for _, name := range slices.Sorted(maps.Keys(actual)) {
		if _, found := expected[name]; !found {
			return false, fmt.Errorf("unexpected prepared dependency member: %s", filepath.Join(directory, name))
		}
	}
	return true, nil
}
