package driver

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"
)

type Docker struct {
	Root   string
	Prefix []string
}

func NewDocker(root, endpoint string) Docker {
	binary := os.Getenv("DOCKER")
	if binary == "" {
		binary = "docker"
	}
	prefix := []string{binary}
	if endpoint == "" {
		endpoint = os.Getenv("COLLECTOR_DOCKER_HOST")
	}
	if endpoint == "" {
		endpoint = os.Getenv("DOCKER_HOST")
	}
	if endpoint != "" {
		prefix = append(prefix, "--host", endpoint)
	}
	return Docker{Root: root, Prefix: prefix}
}

func Token(bytes int) (string, error) {
	value := make([]byte, bytes)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return hex.EncodeToString(value), nil
}

func Output(root string, timeout time.Duration, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	child := exec.CommandContext(ctx, args[0], args[1:]...)
	child.Dir = root
	var stderr bytes.Buffer
	child.Stderr = &stderr
	data, err := child.Output()
	if err != nil {
		return string(data), fmt.Errorf("%v: %w\n%s%s", args, errors.Join(err, ctx.Err()), data, stderr.String())
	}
	if stderr.Len() > 0 {
		fmt.Fprint(os.Stderr, stderr.String())
	}
	return string(data), nil
}

func (d Docker) Output(timeout time.Duration, args ...string) (string, error) {
	return Output(d.Root, timeout, append(append([]string{}, d.Prefix...), args...)...)
}

func (d Docker) Start(stdout, stderr *os.File, args ...string) (*exec.Cmd, error) {
	argv := append(append([]string{}, d.Prefix...), args...)
	child := exec.Command(argv[0], argv[1:]...)
	child.Dir = d.Root
	child.Stdout = stdout
	child.Stderr = stderr
	if err := child.Start(); err != nil {
		return nil, err
	}
	return child, nil
}

func WaitChild(child *exec.Cmd, timeout time.Duration) error {
	if child == nil {
		return nil
	}
	done := make(chan error, 1)
	go func() { done <- child.Wait() }()
	select {
	case err := <-done:
		return err
	case <-time.After(timeout):
		primary := fmt.Errorf("owned command exceeded %s: %v", timeout, child.Args)
		killErr := child.Process.Kill()
		select {
		case <-done:
			return errors.Join(primary, killErr)
		case <-time.After(5 * time.Second):
			return errors.Join(primary, killErr, fmt.Errorf("owned child did not reap"))
		}
	}
}

func WaitUntil(timeout time.Duration, name string, predicate func() (bool, error)) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		ready, err := predicate()
		if err != nil {
			return err
		}
		if ready {
			return nil
		}
		time.Sleep(20 * time.Millisecond)
	}
	return fmt.Errorf("%s did not complete within bound", name)
}

func Exists(path string) (bool, error) {
	_, err := os.Stat(path)
	if os.IsNotExist(err) {
		return false, nil
	}
	return err == nil, err
}

func WriteJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0644)
}

func ReadJSON(path string, value any) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if !utf8.Valid(data) {
		return fmt.Errorf("invalid UTF8 in %s", path)
	}
	return json.Unmarshal(data, value)
}

func HashFile(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	hash := sha256.New()
	_, readErr := io.Copy(hash, file)
	closeErr := file.Close()
	if err := errors.Join(readErr, closeErr); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func RemoveOwned(d Docker, ids ...string) error {
	var failure error
	for _, id := range ids {
		if strings.TrimSpace(id) == "" {
			continue
		}
		_, err := d.Output(20*time.Second, "rm", "-f", id)
		failure = errors.Join(failure, err)
	}
	return failure
}

func NewDirectory(parent, prefix string) (string, error) {
	if err := os.MkdirAll(parent, 0755); err != nil {
		return "", err
	}
	token, err := Token(4)
	if err != nil {
		return "", err
	}
	path := filepath.Join(parent, prefix+token)
	return path, os.Mkdir(path, 0755)
}
