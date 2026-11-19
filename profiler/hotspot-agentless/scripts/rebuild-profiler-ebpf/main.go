package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"time"
)

const (
	pinnedBuilderDigest        = "ghcr.io/inspektor-gadget/gadget-builder@sha256:dba339d2a2f189ae5fcdc08730e6cac128d4ed30c3af42e5fb93a5c8cf4c77aa"
	defaultDockerHostSocket    = "unix:///var/run/docker.sock"
	containerEntrypoint        = "/usr/bin/timeout"
	invocationLabelKey         = "io.mazewall.hotspot.invocation"
	retirementReserveSecs      = 20
	killAfterSecs              = 5
	startClientTailSecs        = 2
	defaultStepTimeoutSecs     = 5
	createStepTimeoutSecs      = 30
	preStartMarginSecs         = createStepTimeoutSecs + defaultStepTimeoutSecs + 1
	terminalInspectReserveSecs = defaultStepTimeoutSecs
	activeInternalReserveSecs  = preStartMarginSecs + killAfterSecs + startClientTailSecs + terminalInspectReserveSecs
	minPayloadSeconds          = 5
	maxPayloadSeconds          = 500
	watchdogObserveTimeoutSecs = 8
	watchdogPollInterval       = 150 * time.Millisecond
	maxCidFileSize             = 64
	maxCaptureBytes            = 6 * 1024 * 1024 // 6 MiB
)

const nativeBuildPayload = `set -euo pipefail
cd support
printf "// Copyright The OpenTelemetry Authors\n// SPDX-License-Identifier: Apache-2.0\n\n" > types_epoch.go.tmp
CC=clang go tool cgo -godefs types_def.go >> types_epoch.go.tmp
sed -i 's/^package support$/package support \/\/ import "go.opentelemetry.io\/ebpf-profiler\/support"/' types_epoch.go.tmp
/usr/local/go/bin/gofmt < types_epoch.go.tmp > types.go
rm types_epoch.go.tmp
cd ebpf
chmod +x print_instruction_count.sh
export PATH=/usr/lib/llvm-18/bin:$PATH
find . -maxdepth 1 \( -name "*.o" -o -name "*.d" \) -delete
make -s -o errors.h TARGET_ARCH=amd64 BPF_CLANG=clang BPF_LINK=llvm-link-18 LLC=llc-18 STRIP=llvm-strip
make -s -o errors.h TARGET_ARCH=arm64 BPF_CLANG=clang BPF_LINK=llvm-link-18 LLC=llc-18 STRIP=llvm-strip
`

type containerState int

const (
	stateUncreated containerState = iota
	stateCreatedUnverified
	stateStartUncertain
	stateExited
	stateRetired
	stateIncomplete
)

func (s containerState) String() string {
	switch s {
	case stateUncreated:
		return "uncreated"
	case stateCreatedUnverified:
		return "created_unverified"
	case stateStartUncertain:
		return "start_uncertain"
	case stateExited:
		return "exited"
	case stateRetired:
		return "retired"
	case stateIncomplete:
		return "incomplete"
	default:
		return "unknown"
	}
}

type endpointAuthority struct {
	cliPath    string
	hostSocket string
	approved   bool
}

type watchdogAuthority struct {
	entrypointPath string
	qualified      bool
}

type buildConfig struct {
	rootDir                 string
	providerDir             string
	builderImage            string
	builderImageID          string
	scriptPath              string
	deadline                time.Time
	activeDeadline          time.Time
	endpoint                endpointAuthority
	watchdog                watchdogAuthority
	isProduction            bool
	testOnlyPayloadOverride string
	nonceFunc               func() (string, error)
	receiptWriter           func(path string, data []byte) error
	nowFunc                 func() time.Time
}

func (c buildConfig) now() time.Time {
	if c.nowFunc != nil {
		return c.nowFunc()
	}
	return time.Now()
}

type candidateCreationWitness struct {
	cid      string
	invocDir string
	nonce    string
	cidFile  string
}

type ownershipToken struct {
	cid           string
	nonce         string
	containerName string
	cidFile       string
	invocDir      string
	endpoint      endpointAuthority
}

type expectedMount struct {
	Type        string
	Source      string
	Destination string
	RW          bool
}

type containerSpec struct {
	name          string
	nonce         string
	cidFile       string
	imageRef      string
	imageID       string
	entrypoint    []string
	cmd           []string
	networkMode   string
	pidMode       string
	restartPolicy string
	user          string
	mounts        []expectedMount
	payloadBudget int
}

type boundedCaptureWriter struct {
	buf      bytes.Buffer
	limit    int
	overflow bool
}

func newBoundedCaptureWriter(limit int) *boundedCaptureWriter {
	return &boundedCaptureWriter{limit: limit}
}

func (w *boundedCaptureWriter) Write(p []byte) (n int, err error) {
	n = len(p)
	remaining := w.limit - w.buf.Len()
	if remaining > 0 {
		if len(p) <= remaining {
			w.buf.Write(p)
		} else {
			w.buf.Write(p[:remaining])
			w.overflow = true
		}
	} else if len(p) > 0 {
		w.overflow = true
	}
	return n, nil
}

func (w *boundedCaptureWriter) Bytes() []byte {
	return w.buf.Bytes()
}

func generateNonce() (string, error) {
	var buf [16]byte
	if _, err := io.ReadFull(rand.Reader, buf[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf[:]), nil
}

func isValidCID(cid string) bool {
	if len(cid) != 64 {
		return false
	}
	for i := 0; i < len(cid); i++ {
		c := cid[i]
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return false
		}
	}
	return true
}

func effectiveBuildPayload(cfg buildConfig) (string, error) {
	if cfg.testOnlyPayloadOverride != "" {
		if cfg.isProduction {
			return "", errors.New("INCOMPLETE(production build payload is locked; test override forbidden)")
		}
		return cfg.testOnlyPayloadOverride, nil
	}
	return nativeBuildPayload, nil
}

func constructContainerSpec(cfg buildConfig, nonce string, invocDir string) (containerSpec, error) {
	if cfg.activeDeadline.IsZero() {
		return containerSpec{}, errors.New("INCOMPLETE(active deadline is required); no eligible preparation")
	}
	payload, err := effectiveBuildPayload(cfg)
	if err != nil {
		return containerSpec{}, err
	}
	remaining := cfg.activeDeadline.Sub(cfg.now())
	if remaining <= 0 {
		return containerSpec{}, errors.New("INCOMPLETE(insufficient payload budget); no eligible preparation")
	}
	// Active deadline already excludes retirement reserve; deduct only internal active reserves once.
	remainingSecs := int(remaining / time.Second)
	payloadBudget := remainingSecs - activeInternalReserveSecs
	if payloadBudget > maxPayloadSeconds {
		payloadBudget = maxPayloadSeconds
	}
	if payloadBudget < minPayloadSeconds {
		return containerSpec{}, errors.New("INCOMPLETE(insufficient payload budget); no eligible preparation")
	}

	containerName := "mw-profiler-ebpf-" + nonce
	cidFile := filepath.Join(invocDir, "cid")
	userStr := fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid())

	spec := containerSpec{
		name:          containerName,
		nonce:         nonce,
		cidFile:       cidFile,
		imageRef:      cfg.builderImage,
		imageID:       cfg.builderImageID,
		entrypoint:    []string{cfg.watchdog.entrypointPath},
		cmd:           []string{"--kill-after=5s", fmt.Sprintf("%ds", payloadBudget), "/bin/bash", "-c", payload},
		networkMode:   "none",
		pidMode:       "",
		restartPolicy: "no",
		user:          userStr,
		mounts: []expectedMount{
			{
				Type:        "bind",
				Source:      cfg.providerDir,
				Destination: "/provider",
				RW:          true,
			},
		},
		payloadBudget: payloadBudget,
	}
	return spec, nil
}

func validateSpecPreconditions(spec containerSpec, expectedPayload string) error {
	if spec.name == "" || !strings.HasPrefix(spec.name, "mw-profiler-ebpf-") {
		return fmt.Errorf("invalid container name: %q", spec.name)
	}
	if spec.nonce == "" || len(spec.nonce) != 32 {
		return fmt.Errorf("invalid nonce: %q", spec.nonce)
	}
	if spec.cidFile == "" {
		return errors.New("cidFile path is empty")
	}
	if spec.imageRef != pinnedBuilderDigest {
		return fmt.Errorf("imageRef %q does not match pinned digest %q", spec.imageRef, pinnedBuilderDigest)
	}
	if spec.imageID == "" || !strings.HasPrefix(spec.imageID, "sha256:") || len(spec.imageID) != 71 || !isValidCID(spec.imageID[7:]) {
		return fmt.Errorf("INCOMPLETE(builder actual image ID unqualified: %q); no eligible preparation", spec.imageID)
	}
	if spec.imageRef == spec.imageID {
		return errors.New("imageRef and imageID must be distinct concepts")
	}
	if len(spec.entrypoint) != 1 || spec.entrypoint[0] != containerEntrypoint {
		return fmt.Errorf("invalid entrypoint: %v", spec.entrypoint)
	}
	if expectedPayload == "" {
		return errors.New("expected payload cannot be empty")
	}
	if len(spec.cmd) != 5 || spec.cmd[0] != "--kill-after=5s" || spec.cmd[2] != "/bin/bash" || spec.cmd[3] != "-c" || spec.cmd[4] != expectedPayload {
		return fmt.Errorf("invalid cmd specification: %v", spec.cmd)
	}
	if spec.networkMode != "none" {
		return fmt.Errorf("invalid network mode: %s (must be none)", spec.networkMode)
	}
	if spec.pidMode != "" {
		return fmt.Errorf("invalid PID mode: %s (must be private/empty)", spec.pidMode)
	}
	if spec.restartPolicy != "no" {
		return fmt.Errorf("invalid restart policy: %s (must be no)", spec.restartPolicy)
	}
	if len(spec.mounts) != 1 || spec.mounts[0].Type != "bind" || spec.mounts[0].Destination != "/provider" || !spec.mounts[0].RW {
		return fmt.Errorf("invalid mounts: %v", spec.mounts)
	}
	return nil
}

type inspectMount struct {
	Type        string `json:"Type"`
	Source      string `json:"Source"`
	Destination string `json:"Destination"`
	RW          bool   `json:"RW"`
}

type inspectHostConfig struct {
	NetworkMode   string `json:"NetworkMode"`
	PidMode       string `json:"PidMode"`
	RestartPolicy struct {
		Name string `json:"Name"`
	} `json:"RestartPolicy"`
}

type inspectConfig struct {
	Image      string            `json:"Image"`
	User       string            `json:"User"`
	Labels     map[string]string `json:"Labels"`
	Entrypoint []string          `json:"Entrypoint"`
	Cmd        []string          `json:"Cmd"`
}

type inspectState struct {
	Status     *string `json:"Status"`
	Running    *bool   `json:"Running"`
	Paused     *bool   `json:"Paused"`
	Restarting *bool   `json:"Restarting"`
	Dead       *bool   `json:"Dead"`
	ExitCode   *int    `json:"ExitCode"`
	Error      *string `json:"Error"`
}

type inspectData struct {
	ID         string            `json:"ID"`
	Name       string            `json:"Name"`
	Image      string            `json:"Image"`
	State      inspectState      `json:"State"`
	HostConfig inspectHostConfig `json:"HostConfig"`
	Config     inspectConfig     `json:"Config"`
	Mounts     []inspectMount    `json:"Mounts"`
}

func verifyInspectInitial(data *inspectData, cand candidateCreationWitness, spec containerSpec, endpoint endpointAuthority) (ownershipToken, error) {
	if !isValidCID(data.ID) || data.ID != cand.cid {
		return ownershipToken{}, fmt.Errorf("CID mismatch: expected %s, got %s", cand.cid, data.ID)
	}
	expectedName := "/" + spec.name
	if data.Name != expectedName {
		return ownershipToken{}, fmt.Errorf("container name mismatch: expected %s, got %s", expectedName, data.Name)
	}
	if data.Config.Labels == nil || data.Config.Labels[invocationLabelKey] != spec.nonce {
		return ownershipToken{}, fmt.Errorf("nonce label mismatch: expected %s, got %v", spec.nonce, data.Config.Labels)
	}
	if data.Config.Image != spec.imageRef {
		return ownershipToken{}, fmt.Errorf("Config.Image mismatch: expected %s, got %s", spec.imageRef, data.Config.Image)
	}
	if data.Image != spec.imageID {
		return ownershipToken{}, fmt.Errorf("Image ID mismatch: expected %s, got %s", spec.imageID, data.Image)
	}
	if !reflect.DeepEqual(data.Config.Entrypoint, spec.entrypoint) {
		return ownershipToken{}, fmt.Errorf("entrypoint mismatch: expected %v, got %v", spec.entrypoint, data.Config.Entrypoint)
	}
	if !reflect.DeepEqual(data.Config.Cmd, spec.cmd) {
		return ownershipToken{}, fmt.Errorf("cmd mismatch: expected %v, got %v", spec.cmd, data.Config.Cmd)
	}
	if data.Config.User != spec.user {
		return ownershipToken{}, fmt.Errorf("user mismatch: expected %s, got %s", spec.user, data.Config.User)
	}
	if data.HostConfig.NetworkMode != "none" {
		return ownershipToken{}, fmt.Errorf("NetworkMode mismatch: expected none, got %s", data.HostConfig.NetworkMode)
	}
	if data.HostConfig.PidMode != "" {
		return ownershipToken{}, fmt.Errorf("PidMode mismatch: expected empty/private, got %s", data.HostConfig.PidMode)
	}
	if data.HostConfig.RestartPolicy.Name != "no" && data.HostConfig.RestartPolicy.Name != "" {
		return ownershipToken{}, fmt.Errorf("RestartPolicy mismatch: expected no, got %s", data.HostConfig.RestartPolicy.Name)
	}

	if data.State.Status == nil || *data.State.Status != "created" {
		st := "nil"
		if data.State.Status != nil {
			st = *data.State.Status
		}
		return ownershipToken{}, fmt.Errorf("initial state status mismatch: expected created, got %s", st)
	}
	if data.State.Running == nil || *data.State.Running {
		return ownershipToken{}, errors.New("container is unexpectedly running at initial inspection")
	}
	if data.State.Paused == nil || *data.State.Paused {
		return ownershipToken{}, errors.New("initial inspect missing or adverse Paused field")
	}
	if data.State.Restarting == nil || *data.State.Restarting {
		return ownershipToken{}, errors.New("initial inspect missing or adverse Restarting field")
	}
	if data.State.Dead == nil || *data.State.Dead {
		return ownershipToken{}, errors.New("initial inspect missing or adverse Dead field")
	}
	if data.State.Error == nil || *data.State.Error != "" {
		errStr := "<nil>"
		if data.State.Error != nil {
			errStr = *data.State.Error
		}
		return ownershipToken{}, fmt.Errorf("container state reports error: %s", errStr)
	}

	if len(data.Mounts) != len(spec.mounts) {
		return ownershipToken{}, fmt.Errorf("mount count mismatch: expected %d, got %d", len(spec.mounts), len(data.Mounts))
	}
	for i, m := range spec.mounts {
		dm := data.Mounts[i]
		if dm.Type != m.Type || dm.Source != m.Source || dm.Destination != m.Destination || dm.RW != m.RW {
			return ownershipToken{}, fmt.Errorf("mount %d mismatch: expected %+v, got %+v", i, m, dm)
		}
	}

	return ownershipToken{
		cid:           data.ID,
		nonce:         spec.nonce,
		containerName: spec.name,
		cidFile:       spec.cidFile,
		invocDir:      cand.invocDir,
		endpoint:      endpoint,
	}, nil
}

func verifyTerminalInspect(data *inspectData, token ownershipToken, spec containerSpec) (int, error) {
	if !isValidCID(data.ID) || data.ID != token.cid {
		return -1, fmt.Errorf("terminal inspect CID mismatch: expected %s, got %s", token.cid, data.ID)
	}
	if data.Config.Labels == nil || data.Config.Labels[invocationLabelKey] != token.nonce {
		return -1, fmt.Errorf("terminal inspect nonce label mismatch: expected %s, got %v", token.nonce, data.Config.Labels)
	}
	if data.Config.Image != spec.imageRef {
		return -1, fmt.Errorf("terminal inspect Config.Image mismatch: expected %s, got %s", spec.imageRef, data.Config.Image)
	}
	if data.Image != spec.imageID {
		return -1, fmt.Errorf("terminal inspect Image ID mismatch: expected %s, got %s", spec.imageID, data.Image)
	}
	if data.State.Status == nil || *data.State.Status != "exited" {
		st := "nil"
		if data.State.Status != nil {
			st = *data.State.Status
		}
		return -1, fmt.Errorf("terminal status mismatch: expected exited, got %s", st)
	}
	if data.State.Running == nil || *data.State.Running {
		return -1, errors.New("container is still running at terminal inspection")
	}
	if data.State.Paused == nil || *data.State.Paused {
		return -1, errors.New("terminal inspect missing or adverse Paused field")
	}
	if data.State.Restarting == nil || *data.State.Restarting {
		return -1, errors.New("terminal inspect missing or adverse Restarting field")
	}
	if data.State.Dead == nil || *data.State.Dead {
		return -1, errors.New("terminal inspect missing or adverse Dead field")
	}
	if data.State.ExitCode == nil {
		return -1, errors.New("terminal inspect missing exit code")
	}
	if data.State.Error == nil || *data.State.Error != "" {
		errStr := "<nil>"
		if data.State.Error != nil {
			errStr = *data.State.Error
		}
		ec := -1
		if data.State.ExitCode != nil {
			ec = *data.State.ExitCode
		}
		return ec, fmt.Errorf("terminal state error: %s", errStr)
	}
	return *data.State.ExitCode, nil
}

type runnerFunc func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error)

func verifyContainerAbsence(ctx context.Context, runner runnerFunc, endpoint endpointAuthority, cid string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	deadline, ok := ctx.Deadline()
	stepTimeout := defaultStepTimeoutSecs * time.Second
	if ok {
		if rem := time.Until(deadline); rem <= 0 {
			return errors.New("retirement reserve window exhausted before absence check")
		} else if rem < stepTimeout {
			stepTimeout = rem
		}
	}
	callCtx, cancel := context.WithTimeout(ctx, stepTimeout)
	defer cancel()

	stdout, stderr, exitCode, err := runner(callCtx, endpoint.cliPath, "--host", endpoint.hostSocket, "container", "ls", "--all", "--no-trunc", "--quiet", "--filter", "id="+cid)
	if err != nil || exitCode != 0 {
		return fmt.Errorf("container ls failed (exit %d): %s: %w", exitCode, string(stderr), err)
	}
	outStr := strings.TrimSpace(string(stdout))
	if outStr == "" {
		return nil
	}
	if strings.Contains(outStr, cid) {
		return fmt.Errorf("container %s still present in container ls output", cid)
	}
	return fmt.Errorf("unexpected container ls output: %q", outStr)
}

func retireOwnedToken(ctx context.Context, runner runnerFunc, token ownershipToken, force bool) error {
	var stickyErr error

	execCleanupCmd := func(args ...string) ([]byte, []byte, int, error) {
		if err := ctx.Err(); err != nil {
			return nil, nil, -1, err
		}
		deadline, ok := ctx.Deadline()
		stepTimeout := defaultStepTimeoutSecs * time.Second
		if ok {
			if rem := time.Until(deadline); rem <= 0 {
				return nil, nil, -1, errors.New("retirement reserve window exhausted before cleanup command")
			} else if rem < stepTimeout {
				stepTimeout = rem
			}
		}
		callCtx, cancel := context.WithTimeout(ctx, stepTimeout)
		defer cancel()
		return runner(callCtx, token.endpoint.cliPath, args...)
	}

	if force {
		_, stopErrOut, stopCode, stopErr := execCleanupCmd("--host", token.endpoint.hostSocket, "stop", "--timeout", "2", token.cid)
		if stopErr != nil || stopCode != 0 {
			stickyErr = errors.Join(stickyErr, fmt.Errorf("stop failed (exit %d): %s: %w", stopCode, string(stopErrOut), stopErr))
		}
	}

	rmArgs := []string{"--host", token.endpoint.hostSocket, "rm"}
	if force {
		rmArgs = append(rmArgs, "-f")
	}
	rmArgs = append(rmArgs, token.cid)
	_, rmErrOut, rmCode, rmErr := execCleanupCmd(rmArgs...)
	if rmErr != nil || rmCode != 0 {
		stickyErr = errors.Join(stickyErr, fmt.Errorf("rm failed (exit %d): %s: %w", rmCode, string(rmErrOut), rmErr))
	}

	if err := verifyContainerAbsence(ctx, runner, token.endpoint, token.cid); err != nil {
		stickyErr = errors.Join(stickyErr, fmt.Errorf("absence check failed: %w", err))
	}

	if stickyErr != nil {
		return fmt.Errorf("INCOMPLETE(cleanup outcome unverified; container absence uncertified: %w)", stickyErr)
	}
	return nil
}

type receiptSession struct {
	invocDir   string
	receiptMap map[string]any
	savedState containerState
	writer     func(path string, data []byte) error
}

func newReceiptSession(invocDir string, nonce, containerName, cidFile, image string, writer func(path string, data []byte) error) *receiptSession {
	if writer == nil {
		writer = func(path string, data []byte) error {
			return os.WriteFile(path, data, 0600)
		}
	}
	return &receiptSession{
		invocDir: invocDir,
		receiptMap: map[string]any{
			"nonce":          nonce,
			"container_name": containerName,
			"cid_file":       cidFile,
			"builder_image":  image,
			"state":          stateUncreated.String(),
			"created_at":     time.Now().UTC().Format(time.RFC3339),
		},
		savedState: stateUncreated,
		writer:     writer,
	}
}

func (s *receiptSession) save(st containerState, extra map[string]any) error {
	s.savedState = st
	s.receiptMap["state"] = st.String()
	for k, v := range extra {
		s.receiptMap[k] = v
	}
	data, err := json.MarshalIndent(s.receiptMap, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal receipt: %w", err)
	}
	receiptFile := filepath.Join(s.invocDir, "receipt.json")
	if err := s.writer(receiptFile, append(data, '\n')); err != nil {
		return fmt.Errorf("failed to write receipt to %s: %w", receiptFile, err)
	}
	return nil
}

func resolveCandidateWitness(createStdout []byte, createErr error, cidFile string, invocDir string, nonce string) (candidateCreationWitness, error) {
	stdoutCID := strings.TrimSpace(string(createStdout))
	if stdoutCID != "" && !isValidCID(stdoutCID) {
		stdoutCID = ""
	}

	var cidFileCID string
	f, err := os.Open(cidFile)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			return candidateCreationWitness{}, fmt.Errorf("failed to open cidfile %s: %w", cidFile, err)
		}
	} else {
		defer f.Close()
		fi, err := f.Stat()
		if err != nil {
			return candidateCreationWitness{}, fmt.Errorf("failed to stat cidfile: %w", err)
		}
		if !fi.Mode().IsRegular() {
			return candidateCreationWitness{}, errors.New("cidfile is not a regular file")
		}
		lr := io.LimitReader(f, maxCidFileSize+1)
		cidBytes, readErr := io.ReadAll(lr)
		if readErr != nil {
			return candidateCreationWitness{}, fmt.Errorf("failed to read cidfile: %w", readErr)
		}
		if len(cidBytes) > maxCidFileSize {
			return candidateCreationWitness{}, fmt.Errorf("cidfile size %d exceeds limit of %d bytes", len(cidBytes), maxCidFileSize)
		}
		cand := strings.TrimSpace(string(cidBytes))
		if !isValidCID(cand) {
			return candidateCreationWitness{}, fmt.Errorf("invalid CID content in cidfile: %q", cand)
		}
		cidFileCID = cand
	}

	if createErr != nil {
		unownedCID := stdoutCID
		if unownedCID == "" {
			unownedCID = cidFileCID
		}
		if unownedCID != "" {
			return candidateCreationWitness{
				cid:      unownedCID,
				invocDir: invocDir,
				nonce:    nonce,
				cidFile:  cidFile,
			}, fmt.Errorf("create returned error with stdout/cidfile witness: %w", createErr)
		}
		return candidateCreationWitness{}, fmt.Errorf("create returned error without witness: %w", createErr)
	}

	if stdoutCID == "" {
		return candidateCreationWitness{}, errors.New("missing valid 64-hex container ID in create stdout")
	}
	if cidFileCID == "" {
		return candidateCreationWitness{}, fmt.Errorf("missing valid cidfile witness at %s after create reported success", cidFile)
	}
	if stdoutCID != cidFileCID {
		return candidateCreationWitness{}, fmt.Errorf("conflicting container witness: stdout=%s cidfile=%s", stdoutCID, cidFileCID)
	}

	return candidateCreationWitness{
		cid:      stdoutCID,
		invocDir: invocDir,
		nonce:    nonce,
		cidFile:  cidFile,
	}, nil
}

func contextOrDeadlineErr(ctx context.Context, extraDeadlines ...time.Time) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if dl, ok := ctx.Deadline(); ok && !time.Now().Before(dl) {
		return context.DeadlineExceeded
	}
	for _, d := range extraDeadlines {
		if !d.IsZero() && !time.Now().Before(d) {
			return context.DeadlineExceeded
		}
	}
	return nil
}

func boundedStepContext(parent context.Context, maxStep time.Duration) (context.Context, context.CancelFunc, error) {
	if err := contextOrDeadlineErr(parent); err != nil {
		return nil, nil, err
	}
	timeout := maxStep
	if deadline, ok := parent.Deadline(); ok {
		rem := time.Until(deadline)
		if rem <= 0 {
			return nil, nil, context.DeadlineExceeded
		}
		if rem < timeout {
			timeout = rem
		}
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	return ctx, cancel, nil
}

func boundedRetirementContext(fixedOverallEnd time.Time) (context.Context, context.CancelFunc, error) {
	if fixedOverallEnd.IsZero() {
		return nil, nil, errors.New("fixed overall deadline is required for retirement")
	}
	now := time.Now()
	retEnd := now.Add(retirementReserveSecs * time.Second)
	if retEnd.After(fixedOverallEnd) {
		retEnd = fixedOverallEnd
	}
	if !retEnd.After(now) {
		return nil, nil, errors.New("retirement reserve deadline exhausted")
	}
	c, cancel := context.WithDeadline(context.Background(), retEnd)
	return c, cancel, nil
}

func executeHardenedBuild(ctx context.Context, runner runnerFunc, cfg buildConfig) error {
	if err := contextOrDeadlineErr(ctx); err != nil {
		return fmt.Errorf("INCOMPLETE(parent context canceled before start): %w", err)
	}

	fixedOverallEnd := cfg.deadline
	if fixedOverallEnd.IsZero() {
		return errors.New("INCOMPLETE(stage deadline is required); no eligible preparation")
	}
	activeDeadline := fixedOverallEnd.Add(-retirementReserveSecs * time.Second)
	if !cfg.activeDeadline.IsZero() && cfg.activeDeadline.Before(activeDeadline) {
		activeDeadline = cfg.activeDeadline
	}
	cfg.activeDeadline = activeDeadline

	if err := contextOrDeadlineErr(ctx, activeDeadline, fixedOverallEnd); err != nil {
		return fmt.Errorf("INCOMPLETE(active stage deadline budget exhausted: %w); no eligible preparation", err)
	}

	remActive := activeDeadline.Sub(cfg.now())
	minActiveBudget := time.Duration(activeInternalReserveSecs+minPayloadSeconds) * time.Second
	if remActive < minActiveBudget {
		return fmt.Errorf("INCOMPLETE(active stage deadline budget exhausted: %v remaining, need >= %v); no eligible preparation", remActive, minActiveBudget)
	}

	if !cfg.endpoint.approved {
		return errors.New("INCOMPLETE(stable local endpoint authority unestablished); no eligible preparation")
	}
	if !cfg.watchdog.qualified {
		return errors.New("INCOMPLETE(independent builder deadline unqualified); no eligible preparation")
	}

	expectedPayload, err := effectiveBuildPayload(cfg)
	if err != nil {
		return err
	}

	nonceGen := cfg.nonceFunc
	if nonceGen == nil {
		nonceGen = generateNonce
	}
	nonce, err := nonceGen()
	if err != nil {
		return fmt.Errorf("INCOMPLETE(failed to generate nonce: %w)", err)
	}

	invocDir := filepath.Join(cfg.providerDir, ".mw-rebuild-"+nonce)
	spec, err := constructContainerSpec(cfg, nonce, invocDir)
	if err != nil {
		return err
	}
	if err := validateSpecPreconditions(spec, expectedPayload); err != nil {
		return fmt.Errorf("INCOMPLETE(invalid container spec: %w)", err)
	}

	if err := os.Mkdir(invocDir, 0700); err != nil {
		return fmt.Errorf("INCOMPLETE(failed to exclusively create invocation directory %s: %w)", invocDir, err)
	}

	if _, err := os.Stat(spec.cidFile); err == nil {
		return fmt.Errorf("INCOMPLETE(preexisting cidfile found at %s)", spec.cidFile)
	} else if !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("INCOMPLETE(failed to stat cidfile path %s: %w)", spec.cidFile, err)
	}

	receipt := newReceiptSession(invocDir, nonce, spec.name, spec.cidFile, spec.imageRef, cfg.receiptWriter)

	if err := receipt.save(stateUncreated, nil); err != nil {
		return fmt.Errorf("INCOMPLETE(failed to persist initial uncreated receipt: %w)", err)
	}

	newRetirementContext := func() (context.Context, context.CancelFunc, error) {
		return boundedRetirementContext(fixedOverallEnd)
	}

	createArgs := []string{
		"--host", cfg.endpoint.hostSocket,
		"create",
		"--pull=never",
		"--name", spec.name,
		"--label", invocationLabelKey + "=" + nonce,
		"--cidfile", spec.cidFile,
		"--restart=" + spec.restartPolicy,
		"--network=" + spec.networkMode,
		"--user", spec.user,
		"--mount", fmt.Sprintf("type=%s,src=%s,dst=%s", spec.mounts[0].Type, spec.mounts[0].Source, spec.mounts[0].Destination),
		"--workdir", "/provider",
		"--entrypoint", spec.entrypoint[0],
		spec.imageRef,
		spec.cmd[0], spec.cmd[1], spec.cmd[2], spec.cmd[3], spec.cmd[4],
	}

	if err := contextOrDeadlineErr(ctx); err != nil {
		saveErr := receipt.save(stateIncomplete, map[string]any{"canceled_before_create": err.Error()})
		return errors.Join(fmt.Errorf("INCOMPLETE(context canceled before create): %w", err), saveErr)
	}
	if err := contextOrDeadlineErr(context.Background(), activeDeadline, fixedOverallEnd); err != nil {
		saveErr := receipt.save(stateIncomplete, map[string]any{"expired_before_create": true})
		return errors.Join(errors.New("INCOMPLETE(active deadline expired before create)"), saveErr)
	}

	createTimeout := createStepTimeoutSecs * time.Second
	if rem := time.Until(activeDeadline); rem < createTimeout {
		createTimeout = rem
	}
	createCtx, createCancel := context.WithTimeout(ctx, createTimeout)
	stdout, stderr, exitCode, createErr := runner(createCtx, cfg.endpoint.cliPath, createArgs...)
	createCancel()

	cand, candErr := resolveCandidateWitness(stdout, createErr, spec.cidFile, invocDir, nonce)
	if candErr != nil || exitCode != 0 {
		saveErr := receipt.save(stateIncomplete, map[string]any{
			"create_error": fmt.Sprintf("%v: %s", createErr, string(stderr)),
			"cand_error":   fmt.Sprintf("%v", candErr),
		})
		return errors.Join(fmt.Errorf("INCOMPLETE(create outcome unknown; no cleanup authority: create_err=%v cand_err=%v: %s)", createErr, candErr, string(stderr)), saveErr)
	}
	receipt.receiptMap["candidate_cid"] = cand.cid

	if err := contextOrDeadlineErr(ctx); err != nil {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{"canceled_before_inspect": err.Error()})
		return errors.Join(fmt.Errorf("INCOMPLETE(context canceled before inspect): %w", err), saveErr)
	}
	if err := contextOrDeadlineErr(context.Background(), activeDeadline, fixedOverallEnd); err != nil {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{"expired_before_inspect": true})
		return errors.Join(errors.New("INCOMPLETE(active deadline expired before inspect)"), saveErr)
	}

	inspectTimeout := defaultStepTimeoutSecs * time.Second
	if rem := time.Until(activeDeadline); rem < inspectTimeout {
		inspectTimeout = rem
	}
	inspCtx, inspCancel := context.WithTimeout(ctx, inspectTimeout)
	inspOut, inspErrOut, inspCode, inspErr := runner(inspCtx, cfg.endpoint.cliPath, "--host", cfg.endpoint.hostSocket, "inspect", cand.cid)
	inspCancel()

	if inspErr != nil || inspCode != 0 {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{"inspect_error": string(inspErrOut)})
		return errors.Join(fmt.Errorf("INCOMPLETE(failed to inspect created container; no execution authority: %w: %s)", inspErr, string(inspErrOut)), saveErr)
	}

	var inspectList []inspectData
	if err := json.Unmarshal(inspOut, &inspectList); err != nil || len(inspectList) != 1 {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{"inspect_unmarshal_error": "invalid inspect json"})
		return errors.Join(fmt.Errorf("INCOMPLETE(malformed inspect json; no execution authority: %w)", err), saveErr)
	}

	token, verifyErr := verifyInspectInitial(&inspectList[0], cand, spec, cfg.endpoint)
	if verifyErr != nil {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{"verification_error": verifyErr.Error()})
		return errors.Join(fmt.Errorf("INCOMPLETE(created container metadata drift; no execution authority: %w)", verifyErr), saveErr)
	}

	receipt.receiptMap["cid"] = token.cid
	if err := receipt.save(stateCreatedUnverified, map[string]any{"verified_token": true}); err != nil {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, true)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		return fmt.Errorf("INCOMPLETE(failed to persist verified token receipt before start: %w; cleanup: %v)", err, cleanupErr)
	}

	if err := contextOrDeadlineErr(ctx); err != nil {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, true)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateIncomplete, map[string]any{"canceled_before_start": err.Error(), "cleanup_error": fmt.Sprintf("%v", cleanupErr)})
		return errors.Join(fmt.Errorf("INCOMPLETE(context canceled before start: %w; cleanup: %v)", err, cleanupErr), saveErr)
	}

	remActive = activeDeadline.Sub(cfg.now())
	terminalInspectReserve := time.Duration(terminalInspectReserveSecs) * time.Second
	startTimeout := time.Duration(spec.payloadBudget+killAfterSecs+startClientTailSecs) * time.Second
	if remActive < startTimeout+terminalInspectReserve {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, true)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateIncomplete, map[string]any{
			"insufficient_budget_for_start": remActive.String(),
			"required_start_plus_inspect":   (startTimeout + terminalInspectReserve).String(),
			"cleanup_error":                 fmt.Sprintf("%v", cleanupErr),
		})
		return errors.Join(fmt.Errorf("INCOMPLETE(insufficient budget for start (%v) + terminal inspect (%v): %v remaining; cleanup: %v)", startTimeout, terminalInspectReserve, remActive, cleanupErr), saveErr)
	}

	startCtx, startCancel := context.WithTimeout(ctx, startTimeout)
	startOut, startErrOut, startCode, startErr := runner(startCtx, cfg.endpoint.cliPath, "--host", cfg.endpoint.hostSocket, "start", "--attach", token.cid)
	startCtxErr := startCtx.Err()
	startCancel()

	if startErr != nil || startCode != 0 {
		// Daemon/OCI runtime errors (125), non-exit transport failures (<0), exit 0 with error (e.g. capture overflow), or context expiry must force-retire immediately.
		canVerifyTerminal := startCode > 0 && startCode != 125 && startCtxErr == nil && contextOrDeadlineErr(ctx, activeDeadline, fixedOverallEnd) == nil
		if !canVerifyTerminal {
			retCtx, retCancel, retErr := newRetirementContext()
			var cleanupErr error
			if retErr == nil {
				cleanupErr = retireOwnedToken(retCtx, runner, token, true)
				retCancel()
			} else {
				cleanupErr = retErr
			}
			saveErr := receipt.save(stateStartUncertain, map[string]any{
				"start_error":     fmt.Sprintf("%v: %s", startErr, string(startErrOut)),
				"start_exit_code": startCode,
				"start_stdout":    string(startOut),
				"start_stderr":    string(startErrOut),
				"cleanup_error":   fmt.Sprintf("%v", cleanupErr),
			})
			return errors.Join(fmt.Errorf("INCOMPLETE(start uncertain or failed: %w: %s; cleanup: %v)", startErr, string(startErrOut), cleanupErr), saveErr)
		}
	}

	if err := contextOrDeadlineErr(ctx); err != nil {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, true)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateIncomplete, map[string]any{"canceled_before_terminal_inspect": err.Error(), "cleanup_error": fmt.Sprintf("%v", cleanupErr)})
		return errors.Join(fmt.Errorf("INCOMPLETE(active context canceled before terminal inspect: %w; cleanup: %v)", err, cleanupErr), saveErr)
	}
	if err := contextOrDeadlineErr(context.Background(), activeDeadline, fixedOverallEnd); err != nil {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, true)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateIncomplete, map[string]any{"expired_before_terminal_inspect": true, "cleanup_error": fmt.Sprintf("%v", cleanupErr)})
		return errors.Join(fmt.Errorf("INCOMPLETE(active deadline expired before terminal inspect; cleanup: %v)", cleanupErr), saveErr)
	}

	termTimeout := defaultStepTimeoutSecs * time.Second
	if rem := time.Until(activeDeadline); rem < termTimeout {
		termTimeout = rem
	}
	termCtx, termCancel := context.WithTimeout(ctx, termTimeout)
	termOut, termErrOut, termCode, termErr := runner(termCtx, cfg.endpoint.cliPath, "--host", cfg.endpoint.hostSocket, "inspect", token.cid)
	termCancel()

	if termErr != nil || termCode != 0 {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, true)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateIncomplete, map[string]any{
			"terminal_inspect_error": string(termErrOut),
			"start_stdout":           string(startOut),
			"start_stderr":           string(startErrOut),
			"cleanup_error":          fmt.Sprintf("%v", cleanupErr),
		})
		return errors.Join(fmt.Errorf("INCOMPLETE(failed to inspect terminal container state: %w: %s; cleanup: %v)", termErr, string(termErrOut), cleanupErr), saveErr)
	}

	var termList []inspectData
	if err := json.Unmarshal(termOut, &termList); err != nil || len(termList) != 1 {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, true)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateIncomplete, map[string]any{"terminal_inspect_unmarshal": "invalid json", "cleanup_error": fmt.Sprintf("%v", cleanupErr)})
		return errors.Join(fmt.Errorf("INCOMPLETE(malformed terminal inspect json: %w; cleanup: %v)", err, cleanupErr), saveErr)
	}

	termExitCode, termVerifyErr := verifyTerminalInspect(&termList[0], token, spec)
	if termVerifyErr != nil {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, true)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateIncomplete, map[string]any{"terminal_verification_error": termVerifyErr.Error(), "cleanup_error": fmt.Sprintf("%v", cleanupErr)})
		return errors.Join(fmt.Errorf("INCOMPLETE(terminal container verification failed: %w; cleanup: %v)", termVerifyErr, cleanupErr), saveErr)
	}

	if termExitCode == 0 && (startErr != nil || startCode != 0) {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, true)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateStartUncertain, map[string]any{
			"start_error":     fmt.Sprintf("%v: %s", startErr, string(startErrOut)),
			"start_exit_code": startCode,
			"exit_code":       termExitCode,
			"cleanup_error":   fmt.Sprintf("%v", cleanupErr),
		})
		return errors.Join(fmt.Errorf("INCOMPLETE(start returned error %d/%v despite terminal exit 0; cleanup: %v)", startCode, startErr, cleanupErr), saveErr)
	}

	if termExitCode != 0 {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, false)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateExited, map[string]any{
			"exit_code":     termExitCode,
			"start_stdout":  string(startOut),
			"start_stderr":  string(startErrOut),
			"cleanup_error": fmt.Sprintf("%v", cleanupErr),
		})
		return errors.Join(fmt.Errorf("native build failed with exit code %d: %s; cleanup: %v", termExitCode, string(startErrOut), cleanupErr), saveErr)
	}

	if err := contextOrDeadlineErr(ctx, activeDeadline, fixedOverallEnd); err != nil {
		retCtx, retCancel, retErr := newRetirementContext()
		var cleanupErr error
		if retErr == nil {
			cleanupErr = retireOwnedToken(retCtx, runner, token, false)
			retCancel()
		} else {
			cleanupErr = retErr
		}
		saveErr := receipt.save(stateIncomplete, map[string]any{"expired_before_retirement": err.Error(), "cleanup_error": fmt.Sprintf("%v", cleanupErr)})
		return errors.Join(fmt.Errorf("INCOMPLETE(deadline expired before retirement publication: %w; cleanup: %v)", err, cleanupErr), saveErr)
	}

	retCtx, retCancel, retErr := newRetirementContext()
	if retErr != nil {
		saveErr := receipt.save(stateIncomplete, map[string]any{"retirement_ctx_error": retErr.Error()})
		return errors.Join(fmt.Errorf("INCOMPLETE(retirement reserve exhausted: %w)", retErr), saveErr)
	}
	defer retCancel()

	if err := retireOwnedToken(retCtx, runner, token, false); err != nil {
		saveErr := receipt.save(stateIncomplete, map[string]any{"retirement_error": err.Error()})
		return errors.Join(err, saveErr)
	}

	if err := receipt.save(stateRetired, map[string]any{"exit_code": 0, "retired": true}); err != nil {
		return fmt.Errorf("INCOMPLETE(failed to persist final retirement receipt: %w)", err)
	}

	return nil
}

type endpointProbeDeps struct {
	lookPath   func(file string) (string, error)
	statSocket func(path string) (os.FileInfo, error)
}

func qualifyLocalEndpoint(ctx context.Context, runner runnerFunc, cliName, hostSocket string, deps endpointProbeDeps) (endpointAuthority, error) {
	if cliName == "" {
		cliName = "docker"
	}
	if !strings.HasPrefix(hostSocket, "unix://") {
		return endpointAuthority{}, fmt.Errorf("INCOMPLETE(unsupported docker host socket %q: must use unix://)", hostSocket)
	}
	socketPath := strings.TrimPrefix(hostSocket, "unix://")
	if socketPath == "" {
		return endpointAuthority{}, errors.New("INCOMPLETE(empty docker unix socket path)")
	}
	lookPath := deps.lookPath
	if lookPath == nil {
		lookPath = exec.LookPath
	}
	cliPath, err := lookPath(cliName)
	if err != nil {
		return endpointAuthority{}, fmt.Errorf("INCOMPLETE(docker CLI unavailable: %w)", err)
	}
	statFn := deps.statSocket
	if statFn == nil {
		statFn = os.Stat
	}
	fi, err := statFn(socketPath)
	if err != nil {
		return endpointAuthority{}, fmt.Errorf("INCOMPLETE(docker socket %s unavailable: %w)", socketPath, err)
	}
	if fi.Mode()&os.ModeSocket == 0 {
		return endpointAuthority{}, fmt.Errorf("INCOMPLETE(docker endpoint %s is not a unix socket)", socketPath)
	}
	stepCtx, cancel, err := boundedStepContext(ctx, defaultStepTimeoutSecs*time.Second)
	if err != nil {
		return endpointAuthority{}, fmt.Errorf("INCOMPLETE(endpoint probe budget exhausted: %w)", err)
	}
	defer cancel()

	out, errOut, code, runErr := runner(stepCtx, cliPath, "--host", hostSocket, "info", "--format", "{{.OSType}}|{{.Architecture}}")
	if err := contextOrDeadlineErr(ctx); err != nil {
		return endpointAuthority{}, fmt.Errorf("INCOMPLETE(endpoint probe deadline expired: %w)", err)
	}
	if runErr != nil || code != 0 {
		return endpointAuthority{}, fmt.Errorf("INCOMPLETE(stable local endpoint authority unestablished: exit=%d err=%v stderr=%s)", code, runErr, strings.TrimSpace(string(errOut)))
	}
	parts := strings.Split(strings.TrimSpace(string(out)), "|")
	if len(parts) != 2 || parts[0] != "linux" || parts[1] == "" {
		return endpointAuthority{}, fmt.Errorf("INCOMPLETE(unexpected docker daemon platform %q)", strings.TrimSpace(string(out)))
	}
	return endpointAuthority{
		cliPath:    cliPath,
		hostSocket: hostSocket,
		approved:   true,
	}, nil
}

type imageInspectData struct {
	ID           string   `json:"Id"`
	RepoDigests  []string `json:"RepoDigests"`
	Architecture string   `json:"Architecture"`
	Os           string   `json:"Os"`
}

func qualifyPinnedImage(ctx context.Context, runner runnerFunc, endpoint endpointAuthority, imageRef string) (string, error) {
	if !endpoint.approved {
		return "", errors.New("INCOMPLETE(cannot inspect image without approved endpoint)")
	}
	atIdx := strings.Index(imageRef, "@")
	if atIdx <= 0 {
		return "", fmt.Errorf("INCOMPLETE(builder image reference %q is not digest-pinned)", imageRef)
	}
	expectedDigest := imageRef[atIdx+1:]
	if !strings.HasPrefix(expectedDigest, "sha256:") || len(expectedDigest) != 71 || !isValidCID(expectedDigest[7:]) {
		return "", fmt.Errorf("INCOMPLETE(invalid pinned image digest %q)", expectedDigest)
	}

	stepCtx, cancel, err := boundedStepContext(ctx, defaultStepTimeoutSecs*time.Second)
	if err != nil {
		return "", fmt.Errorf("INCOMPLETE(image inspect budget exhausted: %w)", err)
	}
	defer cancel()

	out, errOut, code, runErr := runner(stepCtx, endpoint.cliPath, "--host", endpoint.hostSocket, "image", "inspect", imageRef)
	if err := contextOrDeadlineErr(ctx); err != nil {
		return "", fmt.Errorf("INCOMPLETE(image inspect deadline expired: %w)", err)
	}
	if runErr != nil || code != 0 {
		return "", fmt.Errorf("INCOMPLETE(pinned builder image unavailable: exit=%d err=%v stderr=%s)", code, runErr, strings.TrimSpace(string(errOut)))
	}

	var imgs []imageInspectData
	if err := json.Unmarshal(out, &imgs); err != nil || len(imgs) != 1 {
		return "", fmt.Errorf("INCOMPLETE(malformed image inspect json: %v)", err)
	}
	img := imgs[0]
	if img.ID != expectedDigest {
		return "", fmt.Errorf("INCOMPLETE(builder image ID mismatch: expected %s, got %q)", expectedDigest, img.ID)
	}
	digestMatched := false
	for _, rd := range img.RepoDigests {
		if rd == imageRef {
			digestMatched = true
			break
		}
	}
	if !digestMatched {
		return "", fmt.Errorf("INCOMPLETE(builder image RepoDigests %v missing pinned ref %s)", img.RepoDigests, imageRef)
	}
	if img.Os != "linux" || img.Architecture != "amd64" {
		return "", fmt.Errorf("INCOMPLETE(builder image platform mismatch: os=%q arch=%q)", img.Os, img.Architecture)
	}
	return img.ID, nil
}

func qualifyWatchdog(ctx context.Context, runner runnerFunc, endpoint endpointAuthority, imageRef, imageID string, fixedOverallEnd time.Time, providerDir string, nonceGen func() (string, error), receiptWriter func(path string, data []byte) error) (watchdogAuthority, error) {
	if err := contextOrDeadlineErr(ctx, fixedOverallEnd); err != nil {
		return watchdogAuthority{}, fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: context expired: %w)", err)
	}
	if !endpoint.approved {
		return watchdogAuthority{}, errors.New("INCOMPLETE(independent builder deadline unqualified: endpoint not approved)")
	}
	if imageID == "" || !strings.HasPrefix(imageID, "sha256:") || len(imageID) != 71 || !isValidCID(imageID[7:]) {
		return watchdogAuthority{}, fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: invalid image ID %q)", imageID)
	}
	if providerDir == "" {
		return watchdogAuthority{}, errors.New("INCOMPLETE(watchdog probe receipt directory requires non-empty providerDir)")
	}
	if fi, err := os.Stat(providerDir); err != nil || !fi.IsDir() {
		return watchdogAuthority{}, fmt.Errorf("INCOMPLETE(watchdog probe provider directory invalid %s: %v)", providerDir, err)
	}
	if nonceGen == nil {
		nonceGen = generateNonce
	}
	nonce, err := nonceGen()
	if err != nil {
		return watchdogAuthority{}, fmt.Errorf("INCOMPLETE(watchdog probe nonce failed: %w)", err)
	}
	probeDir := filepath.Join(providerDir, ".mw-watchdog-"+nonce)
	if err := os.Mkdir(probeDir, 0700); err != nil {
		return watchdogAuthority{}, fmt.Errorf("INCOMPLETE(watchdog probe dir creation failed %s: %w)", probeDir, err)
	}

	spec := containerSpec{
		name:          "mw-profiler-watchdog-" + nonce,
		nonce:         nonce,
		cidFile:       filepath.Join(probeDir, "cid"),
		imageRef:      imageRef,
		imageID:       imageID,
		entrypoint:    []string{containerEntrypoint},
		cmd:           []string{"--kill-after=1s", "1s", "/bin/sh", "-c", "trap '' TERM; sleep 30"},
		networkMode:   "none",
		pidMode:       "",
		restartPolicy: "no",
		user:          fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid()),
	}

	receipt := newReceiptSession(probeDir, nonce, spec.name, spec.cidFile, spec.imageRef, receiptWriter)
	receipt.receiptMap["kind"] = "watchdog_probe"
	receipt.receiptMap["spec"] = map[string]any{
		"name":           spec.name,
		"image_ref":      spec.imageRef,
		"image_id":       spec.imageID,
		"entrypoint":     spec.entrypoint,
		"cmd":            spec.cmd,
		"network_mode":   spec.networkMode,
		"pid_mode":       spec.pidMode,
		"restart_policy": spec.restartPolicy,
		"user":           spec.user,
		"mounts":         []any{},
	}
	if err := receipt.save(stateUncreated, nil); err != nil {
		return watchdogAuthority{}, fmt.Errorf("INCOMPLETE(failed to persist initial watchdog probe receipt: %w)", err)
	}

	if err := contextOrDeadlineErr(ctx, fixedOverallEnd); err != nil {
		saveErr := receipt.save(stateIncomplete, map[string]any{"expired_before_create": err.Error()})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(watchdog probe budget exhausted before create: %w)", err), saveErr)
	}

	createCtx, createCancel, err := boundedStepContext(ctx, createStepTimeoutSecs*time.Second)
	if err != nil {
		saveErr := receipt.save(stateIncomplete, map[string]any{"create_budget_exhausted": err.Error()})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(watchdog probe create budget exhausted: %w)", err), saveErr)
	}
	createOut, createErrOut, createCode, createErr := runner(
		createCtx,
		endpoint.cliPath,
		"--host", endpoint.hostSocket,
		"create",
		"--pull=never",
		"--name", spec.name,
		"--label", invocationLabelKey+"="+nonce,
		"--cidfile", spec.cidFile,
		"--restart="+spec.restartPolicy,
		"--network="+spec.networkMode,
		"--user", spec.user,
		"--entrypoint", spec.entrypoint[0],
		spec.imageRef,
		spec.cmd[0], spec.cmd[1], spec.cmd[2], spec.cmd[3], spec.cmd[4],
	)
	createCancel()

	cand, candErr := resolveCandidateWitness(createOut, createErr, spec.cidFile, probeDir, nonce)
	if candErr != nil || createCode != 0 {
		extra := map[string]any{
			"create_exit_code": createCode,
			"create_error":     fmt.Sprintf("%v: %s", createErr, string(createErrOut)),
			"create_stdout":    string(createOut),
			"cand_error":       fmt.Sprintf("%v", candErr),
		}
		if cand.cid != "" {
			extra["candidate_cid"] = cand.cid
		}
		saveErr := receipt.save(stateIncomplete, extra)
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog probe create failed (no cleanup authority): create_err=%v cand_err=%v: %s)", createErr, candErr, string(createErrOut)), saveErr)
	}
	receipt.receiptMap["candidate_cid"] = cand.cid

	if err := contextOrDeadlineErr(ctx, fixedOverallEnd); err != nil {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{"expired_before_initial_inspect": err.Error()})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog budget exhausted before inspect: %w)", err), saveErr)
	}

	inspCtx, inspCancel, err := boundedStepContext(ctx, defaultStepTimeoutSecs*time.Second)
	if err != nil {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{"inspect_budget_exhausted": err.Error()})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog inspect budget exhausted: %w)", err), saveErr)
	}
	inspOut, inspErrOut, inspCode, inspErr := runner(inspCtx, endpoint.cliPath, "--host", endpoint.hostSocket, "inspect", cand.cid)
	inspCancel()
	if inspErr != nil || inspCode != 0 {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{
			"inspect_exit_code": inspCode,
			"inspect_error":     fmt.Sprintf("%v: %s", inspErr, string(inspErrOut)),
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog inspect failed: %v: %s)", inspErr, string(inspErrOut)), saveErr)
	}
	var inspList []inspectData
	if err := json.Unmarshal(inspOut, &inspList); err != nil || len(inspList) != 1 {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{
			"inspect_unmarshal_error": fmt.Sprintf("%v", err),
			"initial_inspect_raw":     string(inspOut),
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: malformed watchdog inspect json: %v)", err), saveErr)
	}
	token, verifyErr := verifyInspectInitial(&inspList[0], cand, spec, endpoint)
	if verifyErr != nil {
		saveErr := receipt.save(stateCreatedUnverified, map[string]any{
			"verification_error": verifyErr.Error(),
			"initial_inspect":    inspList[0],
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog metadata drift: %w)", verifyErr), saveErr)
	}

	retireProbe := func(force bool) error {
		retCtx, retCancel, retErr := boundedRetirementContext(fixedOverallEnd)
		if retErr != nil {
			return retErr
		}
		defer retCancel()
		return retireOwnedToken(retCtx, runner, token, force)
	}

	receipt.receiptMap["cid"] = token.cid
	if err := receipt.save(stateCreatedUnverified, map[string]any{
		"verified_token":  true,
		"initial_inspect": inspList[0],
	}); err != nil {
		cleanErr := retireProbe(true)
		return watchdogAuthority{}, fmt.Errorf("INCOMPLETE(failed to persist verified watchdog token receipt: %w; cleanup: %v)", err, cleanErr)
	}

	if err := contextOrDeadlineErr(ctx, fixedOverallEnd); err != nil {
		cleanErr := retireProbe(true)
		saveErr := receipt.save(stateIncomplete, map[string]any{
			"canceled_before_start": err.Error(),
			"cleanup_error":         fmt.Sprintf("%v", cleanErr),
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: context expired before start: %w; cleanup: %v)", err, cleanErr), saveErr)
	}

	// Start detached (no --attach) so container exit is proven independent of any attached client.
	startCtx, startCancel, err := boundedStepContext(ctx, defaultStepTimeoutSecs*time.Second)
	if err != nil {
		cleanErr := retireProbe(true)
		saveErr := receipt.save(stateIncomplete, map[string]any{
			"start_budget_exhausted": err.Error(),
			"cleanup_error":          fmt.Sprintf("%v", cleanErr),
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog start budget exhausted: %w; cleanup: %v)", err, cleanErr), saveErr)
	}
	startOut, startErrOut, startCode, startErr := runner(startCtx, endpoint.cliPath, "--host", endpoint.hostSocket, "start", token.cid)
	startCtxErr := startCtx.Err()
	startCancel()

	if startErr != nil || startCode != 0 || startCtxErr != nil || contextOrDeadlineErr(ctx, fixedOverallEnd) != nil {
		cleanErr := retireProbe(true)
		saveErr := receipt.save(stateStartUncertain, map[string]any{
			"start_error":     fmt.Sprintf("%v: %s", startErr, string(startErrOut)),
			"start_exit_code": startCode,
			"start_stdout":    string(startOut),
			"start_stderr":    string(startErrOut),
			"cleanup_error":   fmt.Sprintf("%v", cleanErr),
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog probe start uncertain (%d/%v): %s; cleanup: %v)", startCode, startErr, string(startErrOut), cleanErr), saveErr)
	}
	receipt.receiptMap["detached_start"] = true

	observeEnd := time.Now().Add(watchdogObserveTimeoutSecs * time.Second)
	if dl, ok := ctx.Deadline(); ok && dl.Before(observeEnd) {
		observeEnd = dl
	}
	if fixedOverallEnd.Before(observeEnd) {
		observeEnd = fixedOverallEnd
	}

	var termList []inspectData
	var exitCode int
	for {
		if err := contextOrDeadlineErr(ctx, fixedOverallEnd); err != nil {
			cleanErr := retireProbe(true)
			saveErr := receipt.save(stateIncomplete, map[string]any{
				"expired_during_observation": err.Error(),
				"cleanup_error":              fmt.Sprintf("%v", cleanErr),
			})
			return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: context expired observing watchdog exit: %w; cleanup: %v)", err, cleanErr), saveErr)
		}

		termCtx, termCancel, err := boundedStepContext(ctx, defaultStepTimeoutSecs*time.Second)
		if err != nil {
			cleanErr := retireProbe(true)
			saveErr := receipt.save(stateIncomplete, map[string]any{
				"terminal_inspect_budget_exhausted": err.Error(),
				"cleanup_error":                     fmt.Sprintf("%v", cleanErr),
			})
			return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog terminal inspect budget exhausted: %w; cleanup: %v)", err, cleanErr), saveErr)
		}
		termOut, termErrOut, termCode, termErr := runner(termCtx, endpoint.cliPath, "--host", endpoint.hostSocket, "inspect", token.cid)
		termCancel()

		if termErr != nil || termCode != 0 {
			cleanErr := retireProbe(true)
			saveErr := receipt.save(stateIncomplete, map[string]any{
				"terminal_inspect_exit_code": termCode,
				"terminal_inspect_error":     fmt.Sprintf("%v: %s", termErr, string(termErrOut)),
				"cleanup_error":              fmt.Sprintf("%v", cleanErr),
			})
			return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog terminal inspect failed: %v: %s; cleanup: %v)", termErr, string(termErrOut), cleanErr), saveErr)
		}
		termList = nil
		if err := json.Unmarshal(termOut, &termList); err != nil || len(termList) != 1 {
			cleanErr := retireProbe(true)
			saveErr := receipt.save(stateIncomplete, map[string]any{
				"terminal_inspect_unmarshal": fmt.Sprintf("%v", err),
				"terminal_inspect_raw":       string(termOut),
				"cleanup_error":              fmt.Sprintf("%v", cleanErr),
			})
			return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: malformed watchdog terminal inspect: %v; cleanup: %v)", err, cleanErr), saveErr)
		}

		var termVerifyErr error
		exitCode, termVerifyErr = verifyTerminalInspect(&termList[0], token, spec)
		if termVerifyErr == nil {
			break
		}

		st := termList[0].State
		stillInProgress := st.Status != nil && (*st.Status == "running" || *st.Status == "created") &&
			(st.Running == nil || *st.Running || *st.Status == "created") &&
			(st.Paused == nil || !*st.Paused) &&
			(st.Restarting == nil || !*st.Restarting) &&
			(st.Dead == nil || !*st.Dead) &&
			(st.Error == nil || *st.Error == "")
		if !stillInProgress {
			cleanErr := retireProbe(true)
			saveErr := receipt.save(stateIncomplete, map[string]any{
				"terminal_verification_error": termVerifyErr.Error(),
				"terminal_inspect":            termList[0],
				"cleanup_error":               fmt.Sprintf("%v", cleanErr),
			})
			return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog terminal verification failed: %w; cleanup: %v)", termVerifyErr, cleanErr), saveErr)
		}

		if !time.Now().Before(observeEnd) {
			cleanErr := retireProbe(true)
			saveErr := receipt.save(stateIncomplete, map[string]any{
				"terminal_verification_error": "timed out waiting for autonomous PID-1 exit",
				"terminal_inspect":            termList[0],
				"cleanup_error":               fmt.Sprintf("%v", cleanErr),
			})
			return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: watchdog probe did not exit autonomously before observation deadline; cleanup: %v)", cleanErr), saveErr)
		}

		sleepDur := watchdogPollInterval
		if remObs := time.Until(observeEnd); remObs < sleepDur {
			sleepDur = remObs
		}
		select {
		case <-time.After(sleepDur):
		case <-ctx.Done():
		}
	}

	if err := contextOrDeadlineErr(ctx, fixedOverallEnd); err != nil {
		cleanErr := retireProbe(true)
		saveErr := receipt.save(stateIncomplete, map[string]any{
			"expired_after_observation": err.Error(),
			"terminal_inspect":          termList[0],
			"cleanup_error":             fmt.Sprintf("%v", cleanErr),
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: context expired after observation: %w; cleanup: %v)", err, cleanErr), saveErr)
	}

	if exitCode != 124 && exitCode != 137 {
		cleanErr := retireProbe(false)
		saveErr := receipt.save(stateExited, map[string]any{
			"exit_code":        exitCode,
			"terminal_inspect": termList[0],
			"cleanup_error":    fmt.Sprintf("%v", cleanErr),
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: PID-1 timeout probe exited %d, expected 124 or 137; cleanup: %v)", exitCode, cleanErr), saveErr)
	}

	if err := retireProbe(false); err != nil {
		saveErr := receipt.save(stateIncomplete, map[string]any{
			"exit_code":        exitCode,
			"terminal_inspect": termList[0],
			"cleanup_error":    err.Error(),
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(watchdog probe container retirement failed: %w)", err), saveErr)
	}

	if err := contextOrDeadlineErr(ctx, fixedOverallEnd); err != nil {
		saveErr := receipt.save(stateIncomplete, map[string]any{
			"exit_code":              exitCode,
			"terminal_inspect":       termList[0],
			"expired_before_publish": err.Error(),
			"cleanup_error":          "<nil>",
		})
		return watchdogAuthority{}, errors.Join(fmt.Errorf("INCOMPLETE(independent builder deadline unqualified: context expired before qualification return: %w)", err), saveErr)
	}

	if err := receipt.save(stateRetired, map[string]any{
		"exit_code":        exitCode,
		"retired":          true,
		"terminal_inspect": termList[0],
		"cleanup_error":    "<nil>",
	}); err != nil {
		return watchdogAuthority{}, fmt.Errorf("INCOMPLETE(failed to persist final watchdog probe receipt: %w)", err)
	}

	return watchdogAuthority{
		entrypointPath: containerEntrypoint,
		qualified:      true,
	}, nil
}

func parseCLIArgs(args []string) (string, time.Time, error) {
	fs := flag.NewFlagSet("rebuild-profiler-ebpf", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	var deadlineFlag int64
	fs.Int64Var(&deadlineFlag, "deadline", 0, "stage deadline in unix epoch seconds")
	if err := fs.Parse(args); err != nil {
		return "", time.Time{}, fmt.Errorf("INCOMPLETE(invalid arguments: %w); no eligible preparation", err)
	}
	pos := fs.Args()
	if len(pos) != 1 {
		return "", time.Time{}, errors.New("Usage: rebuild-profiler-ebpf --deadline=<unix_sec> <provider_dir>")
	}
	if deadlineFlag <= 0 {
		return "", time.Time{}, errors.New("INCOMPLETE(positive --deadline in unix seconds is required); no eligible preparation")
	}
	deadline := time.Unix(deadlineFlag, 0)
	if time.Until(deadline) <= 0 {
		return "", time.Time{}, fmt.Errorf("INCOMPLETE(stage deadline %d already expired); no eligible preparation", deadlineFlag)
	}
	providerDir, err := filepath.Abs(pos[0])
	if err != nil {
		return "", time.Time{}, fmt.Errorf("INCOMPLETE(failed to resolve provider directory: %w)", err)
	}
	fi, err := os.Stat(providerDir)
	if err != nil || !fi.IsDir() {
		return "", time.Time{}, fmt.Errorf("INCOMPLETE(invalid provider directory %s: %v); no eligible preparation", providerDir, err)
	}
	return providerDir, deadline, nil
}

func runProductionBuild(parentCtx context.Context, runner runnerFunc, cliArgs []string, deps endpointProbeDeps) error {
	if err := contextOrDeadlineErr(parentCtx); err != nil {
		return fmt.Errorf("INCOMPLETE(parent context expired before start): %w", err)
	}
	providerDir, deadline, err := parseCLIArgs(cliArgs)
	if err != nil {
		return err
	}
	if err := contextOrDeadlineErr(parentCtx, deadline); err != nil {
		return fmt.Errorf("INCOMPLETE(stage deadline already expired): %w", err)
	}

	activeDeadline := deadline.Add(-retirementReserveSecs * time.Second)
	remActive := time.Until(activeDeadline)
	minActiveBudget := time.Duration(activeInternalReserveSecs+minPayloadSeconds) * time.Second
	if remActive < minActiveBudget {
		return fmt.Errorf("INCOMPLETE(active stage deadline budget exhausted: %v remaining, need >= %v); no eligible preparation", remActive, minActiveBudget)
	}

	activeCtx, activeCancel := context.WithDeadline(parentCtx, activeDeadline)
	defer activeCancel()

	endpoint, err := qualifyLocalEndpoint(activeCtx, runner, "docker", defaultDockerHostSocket, deps)
	if err != nil {
		return err
	}

	imageID, err := qualifyPinnedImage(activeCtx, runner, endpoint, pinnedBuilderDigest)
	if err != nil {
		return err
	}

	watchdog, err := qualifyWatchdog(activeCtx, runner, endpoint, pinnedBuilderDigest, imageID, deadline, providerDir, generateNonce, nil)
	if err != nil {
		return err
	}

	cfg := buildConfig{
		providerDir:    providerDir,
		builderImage:   pinnedBuilderDigest,
		builderImageID: imageID,
		deadline:       deadline,
		activeDeadline: activeDeadline,
		endpoint:       endpoint,
		watchdog:       watchdog,
		isProduction:   true,
	}
	return executeHardenedBuild(activeCtx, runner, cfg)
}

func classifyRunnerOutcome(stdoutOver, stderrOver bool, runErr error) error {
	if stdoutOver || stderrOver {
		overflowErr := fmt.Errorf("subprocess output capture exceeded %d bytes (stdout_overflow=%t, stderr_overflow=%t)", maxCaptureBytes, stdoutOver, stderrOver)
		if runErr != nil {
			return errors.Join(runErr, overflowErr)
		}
		return overflowErr
	}
	return runErr
}

func defaultExecRunner(cmdCtx context.Context, name string, cmdArgs ...string) ([]byte, []byte, int, error) {
	cmd := exec.CommandContext(cmdCtx, name, cmdArgs...)
	cmd.WaitDelay = 2 * time.Second
	stdoutCapture := newBoundedCaptureWriter(maxCaptureBytes)
	stderrCapture := newBoundedCaptureWriter(maxCaptureBytes)
	cmd.Stdout = stdoutCapture
	cmd.Stderr = stderrCapture
	err := cmd.Run()
	exitCode := 0
	if err != nil {
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) {
			exitCode = exitErr.ExitCode()
		} else {
			exitCode = -1
		}
	}
	err = classifyRunnerOutcome(stdoutCapture.overflow, stderrCapture.overflow, err)
	return stdoutCapture.Bytes(), stderrCapture.Bytes(), exitCode, err
}

func main() {
	if err := runProductionBuild(context.Background(), defaultExecRunner, os.Args[1:], endpointProbeDeps{}); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}
}
