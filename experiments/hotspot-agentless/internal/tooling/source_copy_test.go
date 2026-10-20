package tooling

import (
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

func TestCopySourceTreeReadOnlyCachePreservesFiles(t *testing.T) {
	root := t.TempDir()
	source, destination := filepath.Join(root, "source"), filepath.Join(root, "copy")
	directories := []struct {
		name string
		mode os.FileMode
	}{{"cache", 0555}, {"cache/nested", 0555}, {"cache/nested/normal", 0750}}
	for _, dir := range directories {
		if err := os.MkdirAll(filepath.Join(source, dir.name), 0755); err != nil {
			t.Fatal(err)
		}
	}
	files := []struct {
		name string
		mode os.FileMode
	}{{"cache/LICENSE", 0444}, {"cache/nested/tool", 0555}, {"cache/nested/normal/source.go", 0640}}
	for _, file := range files {
		path := filepath.Join(source, file.name)
		if err := os.WriteFile(path, []byte(file.name), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.Chmod(path, file.mode); err != nil {
			t.Fatal(err)
		}
	}
	for _, dir := range directories {
		path := filepath.Join(source, dir.name)
		t.Cleanup(func() {
			if err := os.Chmod(path, 0755); err != nil {
				t.Error(err)
			}
		})
	}
	for i := len(directories) - 1; i >= 0; i-- {
		if err := os.Chmod(filepath.Join(source, directories[i].name), directories[i].mode); err != nil {
			t.Fatal(err)
		}
	}
	if err := copySourceTree(source, destination, true); err != nil {
		t.Fatal(err)
	}
	for _, file := range files {
		for _, tree := range []string{source, destination} {
			path := filepath.Join(tree, file.name)
			info, err := os.Stat(path)
			if err != nil || info.Mode().Perm() != file.mode {
				t.Fatalf("file mode %s: %v, %v", path, info, err)
			}
			data, err := os.ReadFile(path)
			if err != nil || string(data) != file.name {
				t.Fatalf("file bytes %s: %q, %v", path, data, err)
			}
		}
	}
	for _, dir := range directories {
		for tree, want := range map[string]os.FileMode{source: dir.mode, destination: dir.mode | 0700} {
			info, err := os.Stat(filepath.Join(tree, dir.name))
			if err != nil || info.Mode().Perm() != want {
				t.Fatalf("directory mode %s/%s: %v, %v", tree, dir.name, info, err)
			}
		}
	}
	if err := os.RemoveAll(destination); err != nil {
		t.Fatalf("staged copy cannot be cleaned up: %v", err)
	}
}

func TestCopySourceTreeExclusionsAndUnsafeMembers(t *testing.T) {
	for _, exclude := range []bool{false, true} {
		source := t.TempDir()
		for _, name := range []string{"build/generated", "nested/__pycache__/cached"} {
			path := filepath.Join(source, name)
			if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, []byte(name), 0644); err != nil {
				t.Fatal(err)
			}
		}
		destination := filepath.Join(t.TempDir(), "copy")
		if err := copySourceTree(source, destination, exclude); err != nil {
			t.Fatal(err)
		}
		for _, name := range []string{"build/generated", "nested/__pycache__/cached"} {
			_, err := os.Stat(filepath.Join(destination, name))
			if (exclude && !os.IsNotExist(err)) || (!exclude && err != nil) {
				t.Fatalf("exclude=%t, %s: %v", exclude, name, err)
			}
		}
	}
	for _, kind := range []string{"symlink", "fifo"} {
		t.Run(kind, func(t *testing.T) {
			source := t.TempDir()
			path := filepath.Join(source, "unsafe")
			var err error
			if kind == "symlink" {
				err = os.Symlink("outside", path)
			} else {
				err = syscall.Mkfifo(path, 0600)
			}
			if err != nil {
				t.Fatal(err)
			}
			err = copySourceTree(source, filepath.Join(t.TempDir(), "copy"), true)
			if err == nil || (!strings.Contains(err.Error(), "symbolic") && !strings.Contains(err.Error(), "unsupported source member")) {
				t.Fatalf("unsafe %s accepted: %v", kind, err)
			}
		})
	}
}
