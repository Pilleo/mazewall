package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"
)

const (
	testCID            = "1111111111111111111111111111111111111111111111111111111111111111"
	testBuilderDigest  = pinnedBuilderDigest
	testBuilderImageID = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
)

type stepDeadlineRecord struct {
	op  string
	rem time.Duration
}

type fakeDockerState struct {
	mu                sync.Mutex
	calls             []string
	stepDeadlines     map[string]time.Duration
	stepDeadlineLog   []stepDeadlineRecord
	name              string
	nonce             string
	cidFile           string
	imageRef          string
	imageID           string
	entrypoint        []string
	cmd               []string
	user              string
	networkMode       string
	pidMode           string
	restart           string
	mounts            []inspectMount
	status            string
	running           bool
	paused            bool
	restarting        bool
	dead              bool
	exitCode          *int
	stateErr          string
	inspectCalls      int
	inspectMutate     func(data *inspectData)
	termInspectMutate func(data *inspectData)
	termInspectRaw    string
	lsFail            bool
	rmFail            bool
	startFail         bool
	startHang         bool
	startExitCode     int
	startStderr       string
	startHook         func()
	removed           bool
}

func newFakeDockerState() *fakeDockerState {
	zero := 0
	return &fakeDockerState{
		stepDeadlines: make(map[string]time.Duration),
		imageRef:      testBuilderDigest,
		imageID:       testBuilderImageID,
		status:        "created",
		networkMode:   "none",
		restart:       "no",
		exitCode:      &zero,
	}
}

func (s *fakeDockerState) callCount(op string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	count := 0
	for _, c := range s.calls {
		if c == op {
			count++
		}
	}
	return count
}

func (s *fakeDockerState) makeRunner() runnerFunc {
	return func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
		s.mu.Lock()
		defer s.mu.Unlock()

		op := ""
		for _, a := range args {
			if a == "create" || a == "inspect" || a == "start" || a == "stop" || a == "rm" || a == "container" {
				op = a
				break
			}
		}
		if op == "container" {
			op = "container ls"
		}
		s.calls = append(s.calls, op)
		if deadline, ok := ctx.Deadline(); ok {
			rem := time.Until(deadline)
			s.stepDeadlines[op] = rem
			s.stepDeadlineLog = append(s.stepDeadlineLog, stepDeadlineRecord{op: op, rem: rem})
		}

		getArgVal := func(prefix string) string {
			for i := 0; i < len(args); i++ {
				if args[i] == prefix && i+1 < len(args) {
					return args[i+1]
				}
				if strings.HasPrefix(args[i], prefix+"=") {
					return strings.TrimPrefix(args[i], prefix+"=")
				}
			}
			return ""
		}

		switch op {
		case "create":
			s.name = getArgVal("--name")
			s.cidFile = getArgVal("--cidfile")
			s.user = getArgVal("--user")
			s.networkMode = getArgVal("--network")
			s.restart = getArgVal("--restart")

			ep := getArgVal("--entrypoint")
			if ep != "" {
				s.entrypoint = []string{ep}
			}

			for i := 0; i < len(args); i++ {
				val := ""
				if args[i] == "--mount" && i+1 < len(args) {
					val = args[i+1]
				} else if strings.HasPrefix(args[i], "--mount=") {
					val = strings.TrimPrefix(args[i], "--mount=")
				}
				if val != "" {
					m := inspectMount{Type: "bind", RW: true}
					for _, param := range strings.Split(val, ",") {
						kv := strings.SplitN(param, "=", 2)
						if len(kv) == 2 {
							switch kv[0] {
							case "type":
								m.Type = kv[1]
							case "src":
								m.Source = kv[1]
							case "dst":
								m.Destination = kv[1]
							case "ro":
								if kv[1] == "true" {
									m.RW = false
								}
							}
						}
					}
					s.mounts = append(s.mounts, m)
				}

				lbl := ""
				if args[i] == "--label" && i+1 < len(args) {
					lbl = args[i+1]
				} else if strings.HasPrefix(args[i], "--label=") {
					lbl = strings.TrimPrefix(args[i], "--label=")
				}
				if lbl != "" {
					parts := strings.SplitN(lbl, "=", 2)
					if len(parts) == 2 && parts[0] == invocationLabelKey {
						s.nonce = parts[1]
					}
				}
			}

			for i := 0; i < len(args); i++ {
				if args[i] == pinnedBuilderDigest {
					s.imageRef = args[i]
					s.cmd = args[i+1:]
					break
				}
			}
			if s.cidFile != "" {
				_ = os.WriteFile(s.cidFile, []byte(testCID), 0600)
			}
			return []byte(testCID + "\n"), nil, 0, nil

		case "inspect":
			s.inspectCalls++
			if s.inspectCalls > 1 && s.termInspectRaw != "" {
				return []byte(s.termInspectRaw), nil, 0, nil
			}

			statusVal := "created"
			exitVal := 0
			if s.inspectCalls > 1 {
				statusVal = "exited"
				if s.exitCode != nil {
					exitVal = *s.exitCode
				}
			}
			runningVal := false
			pausedVal := false
			restartingVal := false
			deadVal := false
			errVal := s.stateErr

			data := []inspectData{
				{
					ID:    testCID,
					Name:  "/" + s.name,
					Image: s.imageID,
					State: inspectState{
						Status:     &statusVal,
						Running:    &runningVal,
						Paused:     &pausedVal,
						Restarting: &restartingVal,
						Dead:       &deadVal,
						ExitCode:   &exitVal,
						Error:      &errVal,
					},
					HostConfig: inspectHostConfig{
						NetworkMode: s.networkMode,
						PidMode:     s.pidMode,
						RestartPolicy: struct {
							Name string `json:"Name"`
						}{Name: s.restart},
					},
					Config: inspectConfig{
						Image: s.imageRef,
						User:  s.user,
						Labels: map[string]string{
							invocationLabelKey: s.nonce,
						},
						Entrypoint: s.entrypoint,
						Cmd:        s.cmd,
					},
					Mounts: s.mounts,
				},
			}

			if s.inspectCalls == 1 && s.inspectMutate != nil {
				s.inspectMutate(&data[0])
			} else if s.inspectCalls > 1 && s.termInspectMutate != nil {
				s.termInspectMutate(&data[0])
			}

			bytesOut, err := json.Marshal(data)
			return bytesOut, nil, 0, err

		case "start":
			if s.startHook != nil {
				s.startHook()
			}
			if s.startHang {
				return nil, []byte("context deadline exceeded during attach"), -1, context.DeadlineExceeded
			}
			if s.startFail {
				return nil, []byte("OCI runtime create failed"), 125, errors.New("exit status 125")
			}
			if s.startExitCode != 0 {
				return nil, []byte(s.startStderr), s.startExitCode, fmt.Errorf("exit status %d", s.startExitCode)
			}
			return []byte("native build completed successfully\n"), nil, 0, nil

		case "stop":
			return nil, nil, 0, nil

		case "rm":
			if s.rmFail {
				return nil, []byte("device or resource busy"), 1, errors.New("exit status 1")
			}
			s.removed = true
			return []byte(testCID + "\n"), nil, 0, nil

		case "container ls":
			if s.lsFail {
				return nil, []byte("daemon transport error"), 125, errors.New("exit status 125")
			}
			if s.removed {
				return []byte(""), nil, 0, nil
			}
			return []byte(testCID + "\n"), nil, 0, nil

		default:
			return nil, nil, 0, nil
		}
	}
}

func testConfig(t *testing.T) (buildConfig, string) {
	tmpDir := t.TempDir()
	providerDir := filepath.Join(tmpDir, "provider")
	if err := os.MkdirAll(providerDir, 0700); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	cfg := buildConfig{
		rootDir:        tmpDir,
		providerDir:    providerDir,
		builderImage:   testBuilderDigest,
		builderImageID: testBuilderImageID,
		deadline:       now.Add(100 * time.Second),
		activeDeadline: now.Add(80 * time.Second),
		endpoint: endpointAuthority{
			cliPath:    "docker",
			hostSocket: "unix:///var/run/docker.sock",
			approved:   true,
		},
		watchdog: watchdogAuthority{
			entrypointPath: containerEntrypoint,
			qualified:      true,
		},
		isProduction: false,
	}
	return cfg, tmpDir
}

func TestNormalLifecycleSuccessOnlyAfterRetirement(t *testing.T) {
	cfg, _ := testConfig(t)
	fake := newFakeDockerState()

	err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
	if err != nil {
		t.Fatalf("expected clean build, got %v", err)
	}

	expectedCalls := []string{"create", "inspect", "start", "inspect", "rm", "container ls"}
	if !reflect.DeepEqual(fake.calls, expectedCalls) {
		t.Fatalf("unexpected call sequence: got %v, expected %v", fake.calls, expectedCalls)
	}

	if dl, ok := fake.stepDeadlines["rm"]; !ok || dl <= 0 || dl > 5*time.Second+50*time.Millisecond {
		t.Fatalf("expected rm deadline bounded by 5s ceiling, got: %v", dl)
	}
	if dl, ok := fake.stepDeadlines["container ls"]; !ok || dl <= 0 || dl > 5*time.Second+50*time.Millisecond {
		t.Fatalf("expected container ls deadline bounded by 5s ceiling, got: %v", dl)
	}

	matches, _ := filepath.Glob(filepath.Join(cfg.providerDir, ".mw-rebuild-*", "receipt.json"))
	if len(matches) != 1 {
		t.Fatalf("expected 1 receipt file, found %d", len(matches))
	}
	data, _ := os.ReadFile(matches[0])
	var receipt map[string]any
	if err := json.Unmarshal(data, &receipt); err != nil {
		t.Fatalf("malformed receipt: %v", err)
	}
	if receipt["state"] != "retired" {
		t.Fatalf("expected state retired, got %v", receipt["state"])
	}
	if receipt["cid"] != testCID {
		t.Fatalf("expected cid %s, got %v", testCID, receipt["cid"])
	}
}

func TestCreateFailureWithoutWitnessRefusesCleanup(t *testing.T) {
	cfg, _ := testConfig(t)
	var issued []string

	runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
		for _, a := range args {
			if a == "create" || a == "rm" || a == "stop" {
				issued = append(issued, a)
			}
		}
		return []byte(testCID + "\n"), []byte("pull access denied"), 125, errors.New("exit status 125")
	}

	err := executeHardenedBuild(context.Background(), runner, cfg)
	if err == nil {
		t.Fatal("expected create failure, got nil")
	}
	if !strings.Contains(err.Error(), "no cleanup authority") {
		t.Fatalf("expected error indicating no cleanup authority, got: %v", err)
	}

	if len(issued) != 1 || issued[0] != "create" {
		t.Fatalf("expected only create call, got: %v", issued)
	}
}

func TestConflictingStdoutAndCidFileFails(t *testing.T) {
	cfg, _ := testConfig(t)
	const cidB = "3333333333333333333333333333333333333333333333333333333333333333"

	runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
		for i, a := range args {
			if a == "--cidfile" && i+1 < len(args) {
				_ = os.WriteFile(args[i+1], []byte(cidB), 0600)
			}
		}
		return []byte(testCID + "\n"), nil, 0, nil
	}

	err := executeHardenedBuild(context.Background(), runner, cfg)
	if err == nil {
		t.Fatal("expected failure on conflicting witness, got nil")
	}
	if !strings.Contains(err.Error(), "conflicting container witness") {
		t.Fatalf("unexpected error message: %v", err)
	}
}

func TestInspectDriftPerFieldMutations(t *testing.T) {
	cfg, _ := testConfig(t)

	t.Run("status_not_created", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.inspectMutate = func(data *inspectData) {
			st := "running"
			data.State.Status = &st
		}
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "initial state status mismatch") {
			t.Fatalf("expected initial state status mismatch, got: %v", err)
		}
		if fake.callCount("start") != 0 {
			t.Fatal("start unexpectedly called after status drift")
		}
		if fake.callCount("rm") != 0 {
			t.Fatal("unowned rm called after initial inspect failure")
		}
	})

	t.Run("pid_mode_host", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.inspectMutate = func(data *inspectData) {
			data.HostConfig.PidMode = "host"
		}
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "PidMode mismatch") {
			t.Fatalf("expected PidMode mismatch, got: %v", err)
		}
		if fake.callCount("start") != 0 {
			t.Fatal("start unexpectedly called after PID mode drift")
		}
	})

	t.Run("network_bridge", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.inspectMutate = func(data *inspectData) {
			data.HostConfig.NetworkMode = "bridge"
		}
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "NetworkMode mismatch") {
			t.Fatalf("expected NetworkMode mismatch, got: %v", err)
		}
		if fake.callCount("start") != 0 {
			t.Fatal("start unexpectedly called after network mode drift")
		}
	})

	t.Run("state_error_present", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.inspectMutate = func(data *inspectData) {
			errStr := "failed to initialize"
			data.State.Error = &errStr
		}
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "container state reports error") {
			t.Fatalf("expected container state reports error, got: %v", err)
		}
		if fake.callCount("start") != 0 {
			t.Fatal("start unexpectedly called after state error")
		}
	})

	t.Run("image_id_mismatch", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.inspectMutate = func(data *inspectData) {
			data.Image = "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
		}
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "Image ID mismatch") {
			t.Fatalf("expected Image ID mismatch, got: %v", err)
		}
		if fake.callCount("start") != 0 {
			t.Fatal("start unexpectedly called after image ID mismatch")
		}
	})

	t.Run("config_image_mismatch", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.inspectMutate = func(data *inspectData) {
			data.Config.Image = "wrong-image:latest"
		}
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "Config.Image mismatch") {
			t.Fatalf("expected Config.Image mismatch, got: %v", err)
		}
		if fake.callCount("start") != 0 {
			t.Fatal("start unexpectedly called after Config.Image mismatch")
		}
	})

	t.Run("missing_config_image_id", func(t *testing.T) {
		fake := newFakeDockerState()
		cfgBad := cfg
		cfgBad.builderImageID = ""
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfgBad)
		if err == nil || !strings.Contains(err.Error(), "builder actual image ID unqualified") {
			t.Fatalf("expected unqualified image ID error, got: %v", err)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("expected 0 runner calls on missing config image ID, got: %v", fake.calls)
		}
	})

	t.Run("nonhex_config_image_id", func(t *testing.T) {
		fake := newFakeDockerState()
		cfgBad := cfg
		cfgBad.builderImageID = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789zzzzzz"
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfgBad)
		if err == nil || !strings.Contains(err.Error(), "builder actual image ID unqualified") {
			t.Fatalf("expected unqualified image ID error on non-hex, got: %v", err)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("expected 0 runner calls on non-hex config image ID, got: %v", fake.calls)
		}
	})

	t.Run("missing_initial_observed_image_id", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.inspectMutate = func(data *inspectData) {
			data.Image = ""
		}
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "Image ID mismatch") {
			t.Fatalf("expected Image ID mismatch on empty initial Image, got: %v", err)
		}
		if fake.callCount("start") != 0 {
			t.Fatal("start unexpectedly called after empty initial image ID")
		}
		if fake.callCount("rm") != 0 {
			t.Fatal("unowned rm called after initial inspect failure")
		}
	})
}

func TestTerminalInspectMalformedOrConflicting(t *testing.T) {
	cfg, _ := testConfig(t)

	t.Run("empty_inspect_object", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.termInspectRaw = "[{}]"
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "terminal container verification failed") {
			t.Fatalf("expected terminal verification failure on empty json object, got: %v", err)
		}
		if fake.callCount("rm") != 1 {
			t.Fatalf("expected 1 rm call to retire owned container on terminal inspect failure, got %d", fake.callCount("rm"))
		}
	})

	t.Run("nonzero_exit_code", func(t *testing.T) {
		fake := newFakeDockerState()
		two := 2
		fake.exitCode = &two
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "native build failed with exit code 2") {
			t.Fatalf("expected exit code 2 error, got: %v", err)
		}
		if fake.callCount("rm") != 1 {
			t.Fatalf("expected 1 rm call to retire owned container on exit 2, got %d", fake.callCount("rm"))
		}
	})

	t.Run("terminal_image_id_drift", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.termInspectMutate = func(data *inspectData) {
			data.Image = "sha256:9999999999999999999999999999999999999999999999999999999999999999"
		}
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "terminal inspect Image ID mismatch") {
			t.Fatalf("expected terminal inspect Image ID mismatch, got: %v", err)
		}
		if fake.callCount("rm") != 1 {
			t.Fatalf("expected owned token retirement on terminal image ID drift, got %d rm calls", fake.callCount("rm"))
		}
	})

	t.Run("missing_terminal_observed_image_id", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.termInspectMutate = func(data *inspectData) {
			data.Image = ""
		}
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "terminal inspect Image ID mismatch") {
			t.Fatalf("expected terminal inspect Image ID mismatch on empty Image, got: %v", err)
		}
		if fake.callCount("rm") != 1 {
			t.Fatalf("expected owned token retirement on empty terminal image ID, got %d rm calls", fake.callCount("rm"))
		}
	})
}

func TestAbsenceCheckRejectsTransportErrorAndNonEmpty(t *testing.T) {
	cfg, _ := testConfig(t)

	t.Run("ls_transport_error", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.lsFail = true
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "container absence uncertified") {
			t.Fatalf("expected absence uncertified error, got: %v", err)
		}
	})

	t.Run("rm_sticky_error", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.rmFail = true
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "rm failed") {
			t.Fatalf("expected sticky rm error, got: %v", err)
		}
	})
}

func TestDeadlineAndCancellationBoundary(t *testing.T) {
	cfg, _ := testConfig(t)
	fake := newFakeDockerState()

	cfgExpired := cfg
	cfgExpired.activeDeadline = time.Now().Add(-10 * time.Second)
	err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfgExpired)
	if err == nil || !strings.Contains(err.Error(), "active stage deadline budget exhausted") {
		t.Fatalf("expected active stage deadline budget exhausted, got: %v", err)
	}
	if len(fake.calls) != 0 {
		t.Fatalf("expected 0 runner calls on expired deadline, got: %v", fake.calls)
	}

	ctxCanceled, cancel := context.WithCancel(context.Background())
	cancel()
	err = executeHardenedBuild(ctxCanceled, fake.makeRunner(), cfg)
	if err == nil || !strings.Contains(err.Error(), "parent context canceled before start") {
		t.Fatalf("expected context canceled before start, got: %v", err)
	}

	t.Run("canceled_before_start_triggers_owned_retirement", func(t *testing.T) {
		fakeStartCancel := newFakeDockerState()
		ctxDyn, cancelDyn := context.WithCancel(context.Background())
		fakeStartCancel.inspectMutate = func(data *inspectData) {
			cancelDyn()
		}
		err := executeHardenedBuild(ctxDyn, fakeStartCancel.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "context canceled before start") {
			t.Fatalf("expected context canceled before start, got: %v", err)
		}
		if fakeStartCancel.callCount("start") != 0 {
			t.Fatal("start was called after context cancellation")
		}
		if fakeStartCancel.callCount("rm") != 1 {
			t.Fatalf("expected 1 rm call to retire owned container, got %d", fakeStartCancel.callCount("rm"))
		}
	})

	t.Run("canceled_before_terminal_inspect_refuses_callback", func(t *testing.T) {
		fakeTermCancel := newFakeDockerState()
		ctxDyn, cancelDyn := context.WithCancel(context.Background())
		fakeTermCancel.startHook = func() {
			cancelDyn()
		}
		err := executeHardenedBuild(ctxDyn, fakeTermCancel.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "canceled before terminal inspect") {
			t.Fatalf("expected canceled before terminal inspect, got: %v", err)
		}
		if fakeTermCancel.callCount("inspect") != 1 {
			t.Fatalf("expected terminal inspect callback to be refused (1 initial inspect only), got %d", fakeTermCancel.callCount("inspect"))
		}
		if fakeTermCancel.callCount("rm") != 1 {
			t.Fatalf("expected owned token retirement on cancel, got %d rm calls", fakeTermCancel.callCount("rm"))
		}
	})
}

func TestBlockedStartAndAttachTimeout(t *testing.T) {
	cfg, _ := testConfig(t)

	t.Run("blocked_start_triggers_bounded_retirement", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.startFail = true
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "start uncertain or failed") {
			t.Fatalf("expected start uncertain or failed, got: %v", err)
		}
		if fake.callCount("rm") != 1 {
			t.Fatalf("expected 1 rm call on start failure, got %d", fake.callCount("rm"))
		}
	})

	t.Run("attach_timeout_triggers_bounded_stop_and_retirement", func(t *testing.T) {
		fake := newFakeDockerState()
		fake.startHang = true
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "start uncertain or failed") {
			t.Fatalf("expected start uncertain or failed on attach timeout, got: %v", err)
		}
		if fake.callCount("stop") != 1 {
			t.Fatalf("expected 1 stop call on attach timeout, got %d", fake.callCount("stop"))
		}
		if fake.callCount("rm") != 1 {
			t.Fatalf("expected 1 rm call on attach timeout, got %d", fake.callCount("rm"))
		}
	})
}

func TestBoundedCaptureWriterAndCidFileLimit(t *testing.T) {
	w := newBoundedCaptureWriter(100)
	_, _ = w.Write(make([]byte, 80))
	if w.overflow {
		t.Fatal("unexpected overflow at 80/100")
	}
	_, _ = w.Write(make([]byte, 40))
	if !w.overflow {
		t.Fatal("expected overflow at 120/100")
	}
	if len(w.Bytes()) != 100 {
		t.Fatalf("expected buffer length 100, got %d", len(w.Bytes()))
	}

	cfg, tmpDir := testConfig(t)
	fake := newFakeDockerState()
	runnerOversize := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
		stdout, stderr, code, err := fake.makeRunner()(ctx, name, args...)
		for i, a := range args {
			if a == "--cidfile" && i+1 < len(args) {
				_ = os.WriteFile(args[i+1], []byte(strings.Repeat("a", 100)+"\n"), 0600)
			}
		}
		return stdout, stderr, code, err
	}

	_ = tmpDir
	err := executeHardenedBuild(context.Background(), runnerOversize, cfg)
	if err == nil || !strings.Contains(err.Error(), "exceeds limit") {
		t.Fatalf("expected cidfile size exceeds limit error, got: %v", err)
	}
}

func TestReceiptCollisionAndWriteFailures(t *testing.T) {
	cfg, tmpDir := testConfig(t)

	t.Run("exclusive_directory_collision", func(t *testing.T) {
		const fixedNonce = "1234567890abcdef1234567890abcdef"
		cfgColl := cfg
		cfgColl.nonceFunc = func() (string, error) {
			return fixedNonce, nil
		}
		collDir := filepath.Join(cfg.providerDir, ".mw-rebuild-"+fixedNonce)
		if err := os.Mkdir(collDir, 0700); err != nil {
			t.Fatal(err)
		}
		fake := newFakeDockerState()
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfgColl)
		if err == nil || !strings.Contains(err.Error(), "failed to exclusively create invocation directory") {
			t.Fatalf("expected collision error, got: %v", err)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("expected 0 runner calls on directory collision, got: %v", fake.calls)
		}
	})

	t.Run("persist_before_create_failure", func(t *testing.T) {
		cfgFail := cfg
		cfgFail.receiptWriter = func(path string, data []byte) error {
			return errors.New("simulated disk full")
		}
		fake := newFakeDockerState()
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfgFail)
		if err == nil || !strings.Contains(err.Error(), "failed to persist initial uncreated receipt") {
			t.Fatalf("expected initial uncreated receipt failure, got: %v", err)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("expected 0 runner calls when initial persist fails, got: %v", fake.calls)
		}
	})

	t.Run("persist_before_start_failure_retains_cleanup_obligation", func(t *testing.T) {
		cfgFail := cfg
		writeCount := 0
		cfgFail.receiptWriter = func(path string, data []byte) error {
			writeCount++
			if writeCount == 2 {
				return errors.New("simulated disk full at token verification")
			}
			return os.WriteFile(path, data, 0600)
		}
		fake := newFakeDockerState()
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfgFail)
		if err == nil || !strings.Contains(err.Error(), "failed to persist verified token receipt before start") {
			t.Fatalf("expected verified token persist failure, got: %v", err)
		}
		if fake.callCount("start") != 0 {
			t.Fatalf("start called despite token persist failure: %v", fake.calls)
		}
		if fake.callCount("rm") != 1 {
			t.Fatalf("expected 1 rm call to retire owned container on persist failure, got %d", fake.callCount("rm"))
		}
	})

	_ = tmpDir
}

func TestNativeBuildPayloadFailFastPrefix(t *testing.T) {
	if !strings.HasPrefix(nativeBuildPayload, "set -euo pipefail\n") {
		t.Fatalf("nativeBuildPayload must begin with fail-fast 'set -euo pipefail'")
	}
	if !strings.Contains(nativeBuildPayload, "make -s -o errors.h TARGET_ARCH=amd64") {
		t.Fatal("nativeBuildPayload missing amd64 make command")
	}
	if !strings.Contains(nativeBuildPayload, "make -s -o errors.h TARGET_ARCH=arm64") {
		t.Fatal("nativeBuildPayload missing arm64 make command")
	}
}

func TestInspectInitialSingleFieldOmissions(t *testing.T) {
	cfg, _ := testConfig(t)
	cases := []struct {
		name        string
		mutate      func(data *inspectData)
		expectedErr string
	}{
		{
			name: "omit_status",
			mutate: func(data *inspectData) {
				data.State.Status = nil
			},
			expectedErr: "initial state status mismatch: expected created, got nil",
		},
		{
			name: "omit_running",
			mutate: func(data *inspectData) {
				data.State.Running = nil
			},
			expectedErr: "container is unexpectedly running at initial inspection",
		},
		{
			name: "omit_paused",
			mutate: func(data *inspectData) {
				data.State.Paused = nil
			},
			expectedErr: "initial inspect missing or adverse Paused field",
		},
		{
			name: "omit_restarting",
			mutate: func(data *inspectData) {
				data.State.Restarting = nil
			},
			expectedErr: "initial inspect missing or adverse Restarting field",
		},
		{
			name: "omit_dead",
			mutate: func(data *inspectData) {
				data.State.Dead = nil
			},
			expectedErr: "initial inspect missing or adverse Dead field",
		},
		{
			name: "omit_error",
			mutate: func(data *inspectData) {
				data.State.Error = nil
			},
			expectedErr: "container state reports error: <nil>",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fake := newFakeDockerState()
			fake.inspectMutate = tc.mutate
			err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
			if err == nil || !strings.Contains(err.Error(), tc.expectedErr) {
				t.Fatalf("expected error containing %q, got: %v", tc.expectedErr, err)
			}
			if fake.callCount("start") != 0 {
				t.Fatalf("start called after initial inspect failure in %s", tc.name)
			}
			if fake.callCount("rm") != 0 {
				t.Fatalf("unowned rm called after initial inspect failure in %s", tc.name)
			}
		})
	}
}

func TestTerminalInspectSingleFieldOmissions(t *testing.T) {
	cfg, _ := testConfig(t)
	cases := []struct {
		name        string
		mutate      func(data *inspectData)
		expectedErr string
	}{
		{
			name: "omit_status",
			mutate: func(data *inspectData) {
				data.State.Status = nil
			},
			expectedErr: "terminal status mismatch: expected exited, got nil",
		},
		{
			name: "omit_running",
			mutate: func(data *inspectData) {
				data.State.Running = nil
			},
			expectedErr: "container is still running at terminal inspection",
		},
		{
			name: "omit_paused",
			mutate: func(data *inspectData) {
				data.State.Paused = nil
			},
			expectedErr: "terminal inspect missing or adverse Paused field",
		},
		{
			name: "omit_restarting",
			mutate: func(data *inspectData) {
				data.State.Restarting = nil
			},
			expectedErr: "terminal inspect missing or adverse Restarting field",
		},
		{
			name: "omit_dead",
			mutate: func(data *inspectData) {
				data.State.Dead = nil
			},
			expectedErr: "terminal inspect missing or adverse Dead field",
		},
		{
			name: "omit_exit_code",
			mutate: func(data *inspectData) {
				data.State.ExitCode = nil
			},
			expectedErr: "terminal inspect missing exit code",
		},
		{
			name: "omit_error",
			mutate: func(data *inspectData) {
				data.State.Error = nil
			},
			expectedErr: "terminal state error: <nil>",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fake := newFakeDockerState()
			fake.termInspectMutate = tc.mutate
			err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
			if err == nil || !strings.Contains(err.Error(), tc.expectedErr) {
				t.Fatalf("expected error containing %q, got: %v", tc.expectedErr, err)
			}
			if fake.callCount("rm") != 1 {
				t.Fatalf("expected 1 rm call to retire owned container on terminal failure in %s, got: %d", tc.name, fake.callCount("rm"))
			}
		})
	}
}

func TestRunnerOutcomeClassification(t *testing.T) {
	cases := []struct {
		name        string
		stdoutOver  bool
		stderrOver  bool
		cmdErr      error
		expectErr   bool
		errContains string
	}{
		{
			name:       "success_no_overflow",
			stdoutOver: false,
			stderrOver: false,
			cmdErr:     nil,
			expectErr:  false,
		},
		{
			name:        "failure_no_overflow",
			stdoutOver:  false,
			stderrOver:  false,
			cmdErr:      errors.New("exit status 1"),
			expectErr:   true,
			errContains: "exit status 1",
		},
		{
			name:        "stdout_overflow_on_success",
			stdoutOver:  true,
			stderrOver:  false,
			cmdErr:      nil,
			expectErr:   true,
			errContains: "stdout_overflow=true",
		},
		{
			name:        "stderr_overflow_on_success",
			stdoutOver:  false,
			stderrOver:  true,
			cmdErr:      nil,
			expectErr:   true,
			errContains: "stderr_overflow=true",
		},
		{
			name:        "both_overflow_on_success",
			stdoutOver:  true,
			stderrOver:  true,
			cmdErr:      nil,
			expectErr:   true,
			errContains: "stdout_overflow=true, stderr_overflow=true",
		},
		{
			name:        "stdout_overflow_joins_cmd_error",
			stdoutOver:  true,
			stderrOver:  false,
			cmdErr:      errors.New("process killed"),
			expectErr:   true,
			errContains: "process killed",
		},
		{
			name:        "stderr_overflow_joins_cmd_error",
			stdoutOver:  false,
			stderrOver:  true,
			cmdErr:      errors.New("process killed"),
			expectErr:   true,
			errContains: "stderr_overflow=true",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := classifyRunnerOutcome(tc.stdoutOver, tc.stderrOver, tc.cmdErr)
			if !tc.expectErr {
				if err != nil {
					t.Fatalf("expected nil error, got: %v", err)
				}
				return
			}
			if err == nil {
				t.Fatal("expected error, got nil")
			}
			if !strings.Contains(err.Error(), tc.errContains) {
				t.Fatalf("expected error containing %q, got: %v", tc.errContains, err)
			}
		})
	}
}

func TestRetirementReserveWindowExhaustion(t *testing.T) {
	cfg, _ := testConfig(t)
	token := ownershipToken{
		cid:           testCID,
		nonce:         "1234567890abcdef1234567890abcdef",
		containerName: "mw-profiler-ebpf-1234567890abcdef1234567890abcdef",
		endpoint:      cfg.endpoint,
	}

	fake := newFakeDockerState()
	expiredCtx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-1*time.Second))
	defer cancel()

	err := retireOwnedToken(expiredCtx, fake.makeRunner(), token, true)
	if err == nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected context.DeadlineExceeded on expired retireOwnedToken, got: %v", err)
	}
	if len(fake.calls) != 0 {
		t.Fatalf("expected 0 runner callbacks after retireOwnedToken, got: %v", fake.calls)
	}

	err = verifyContainerAbsence(expiredCtx, fake.makeRunner(), token.endpoint, token.cid)
	if err == nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected context.DeadlineExceeded on expired verifyContainerAbsence, got: %v", err)
	}
	if len(fake.calls) != 0 {
		t.Fatalf("expected 0 runner callbacks after verifyContainerAbsence, got: %v", fake.calls)
	}
}

type fakeSocketFileInfo struct {
	name string
	mode os.FileMode
}

func (f fakeSocketFileInfo) Name() string       { return f.name }
func (f fakeSocketFileInfo) Size() int64        { return 0 }
func (f fakeSocketFileInfo) Mode() os.FileMode  { return f.mode }
func (f fakeSocketFileInfo) ModTime() time.Time { return time.Time{} }
func (f fakeSocketFileInfo) IsDir() bool        { return false }
func (f fakeSocketFileInfo) Sys() any           { return nil }

func validProbeDeps() endpointProbeDeps {
	return endpointProbeDeps{
		lookPath: func(file string) (string, error) {
			return "/usr/bin/" + file, nil
		},
		statSocket: func(path string) (os.FileInfo, error) {
			return fakeSocketFileInfo{name: filepath.Base(path), mode: os.ModeSocket | 0660}, nil
		},
	}
}

func TestCLIArgValidationAndNoDeadlineRenewal(t *testing.T) {
	providerDir := t.TempDir()
	future := fmt.Sprintf("%d", time.Now().Add(120*time.Second).Unix())
	past := fmt.Sprintf("%d", time.Now().Add(-10*time.Second).Unix())

	cases := []struct {
		name string
		args []string
	}{
		{name: "no_args", args: nil},
		{name: "missing_deadline", args: []string{providerDir}},
		{name: "zero_deadline", args: []string{"--deadline=0", providerDir}},
		{name: "negative_deadline", args: []string{"--deadline=-5", providerDir}},
		{name: "expired_deadline", args: []string{"--deadline=" + past, providerDir}},
		{name: "missing_provider_dir", args: []string{"--deadline=" + future}},
		{name: "extra_positional_arg", args: []string{"--deadline=" + future, providerDir, "extra"}},
		{name: "nonexistent_provider_dir", args: []string{"--deadline=" + future, filepath.Join(providerDir, "does-not-exist")}},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			called := 0
			runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
				called++
				return nil, nil, 0, nil
			}
			err := runProductionBuild(context.Background(), runner, tc.args, validProbeDeps())
			if err == nil {
				t.Fatalf("expected error for %s, got nil", tc.name)
			}
			if called != 0 {
				t.Fatalf("expected 0 runner calls for %s, got %d", tc.name, called)
			}
		})
	}

	t.Run("executeHardenedBuild_requires_nonzero_deadline", func(t *testing.T) {
		cfg, _ := testConfig(t)
		cfg.deadline = time.Time{}
		fake := newFakeDockerState()
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "stage deadline is required") {
			t.Fatalf("expected stage deadline required error, got: %v", err)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("expected 0 runner calls, got: %v", fake.calls)
		}
	})
}

func TestQualifyLocalEndpointRejections(t *testing.T) {
	ctx := context.Background()

	t.Run("missing_docker_binary", func(t *testing.T) {
		called := 0
		deps := validProbeDeps()
		deps.lookPath = func(string) (string, error) { return "", errors.New("not found") }
		_, err := qualifyLocalEndpoint(ctx, func(context.Context, string, ...string) ([]byte, []byte, int, error) {
			called++
			return nil, nil, 0, nil
		}, "docker", defaultDockerHostSocket, deps)
		if err == nil || !strings.Contains(err.Error(), "docker CLI unavailable") {
			t.Fatalf("expected docker CLI unavailable error, got: %v", err)
		}
		if called != 0 {
			t.Fatalf("expected 0 runner calls, got %d", called)
		}
	})

	t.Run("missing_unix_socket", func(t *testing.T) {
		called := 0
		deps := validProbeDeps()
		deps.statSocket = func(string) (os.FileInfo, error) { return nil, os.ErrNotExist }
		_, err := qualifyLocalEndpoint(ctx, func(context.Context, string, ...string) ([]byte, []byte, int, error) {
			called++
			return nil, nil, 0, nil
		}, "docker", defaultDockerHostSocket, deps)
		if err == nil || !strings.Contains(err.Error(), "unavailable") {
			t.Fatalf("expected socket unavailable error, got: %v", err)
		}
		if called != 0 {
			t.Fatalf("expected 0 runner calls, got %d", called)
		}
	})

	t.Run("non_socket_file", func(t *testing.T) {
		called := 0
		deps := validProbeDeps()
		deps.statSocket = func(string) (os.FileInfo, error) {
			return fakeSocketFileInfo{name: "docker.sock", mode: 0600}, nil
		}
		_, err := qualifyLocalEndpoint(ctx, func(context.Context, string, ...string) ([]byte, []byte, int, error) {
			called++
			return nil, nil, 0, nil
		}, "docker", defaultDockerHostSocket, deps)
		if err == nil || !strings.Contains(err.Error(), "not a unix socket") {
			t.Fatalf("expected not a unix socket error, got: %v", err)
		}
		if called != 0 {
			t.Fatalf("expected 0 runner calls, got %d", called)
		}
	})

	t.Run("daemon_unreachable", func(t *testing.T) {
		_, err := qualifyLocalEndpoint(ctx, func(_ context.Context, _ string, args ...string) ([]byte, []byte, int, error) {
			return nil, []byte("Cannot connect to the Docker daemon"), 1, errors.New("exit status 1")
		}, "docker", defaultDockerHostSocket, validProbeDeps())
		if err == nil || !strings.Contains(err.Error(), "stable local endpoint authority unestablished") {
			t.Fatalf("expected endpoint unestablished error, got: %v", err)
		}
	})

	t.Run("non_linux_platform_rejected", func(t *testing.T) {
		_, err := qualifyLocalEndpoint(ctx, func(_ context.Context, _ string, _ ...string) ([]byte, []byte, int, error) {
			return []byte("windows|amd64\n"), nil, 0, nil
		}, "docker", defaultDockerHostSocket, validProbeDeps())
		if err == nil || !strings.Contains(err.Error(), "unexpected docker daemon platform") {
			t.Fatalf("expected platform error, got: %v", err)
		}
	})

	t.Run("valid_endpoint_uses_explicit_host", func(t *testing.T) {
		var gotArgs []string
		ep, err := qualifyLocalEndpoint(ctx, func(_ context.Context, _ string, args ...string) ([]byte, []byte, int, error) {
			gotArgs = args
			return []byte("linux|x86_64\n"), nil, 0, nil
		}, "docker", defaultDockerHostSocket, validProbeDeps())
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !ep.approved || ep.hostSocket != defaultDockerHostSocket {
			t.Fatalf("unexpected endpoint authority: %+v", ep)
		}
		if len(gotArgs) < 2 || gotArgs[0] != "--host" || gotArgs[1] != defaultDockerHostSocket {
			t.Fatalf("expected explicit --host %s, got args %v", defaultDockerHostSocket, gotArgs)
		}
	})
}

func TestQualifyPinnedImageRejections(t *testing.T) {
	ctx := context.Background()
	ep := endpointAuthority{cliPath: "docker", hostSocket: defaultDockerHostSocket, approved: true}
	pinnedDigest := strings.SplitN(pinnedBuilderDigest, "@", 2)[1]

	t.Run("wrong_image_id", func(t *testing.T) {
		_, err := qualifyPinnedImage(ctx, func(context.Context, string, ...string) ([]byte, []byte, int, error) {
			b, _ := json.Marshal([]imageInspectData{{
				ID:           "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
				RepoDigests:  []string{pinnedBuilderDigest},
				Architecture: "amd64",
				Os:           "linux",
			}})
			return b, nil, 0, nil
		}, ep, pinnedBuilderDigest)
		if err == nil || !strings.Contains(err.Error(), "builder image ID mismatch") {
			t.Fatalf("expected image ID mismatch error, got: %v", err)
		}
	})

	t.Run("wrong_repo_digest", func(t *testing.T) {
		_, err := qualifyPinnedImage(ctx, func(context.Context, string, ...string) ([]byte, []byte, int, error) {
			b, _ := json.Marshal([]imageInspectData{{
				ID:           pinnedDigest,
				RepoDigests:  []string{"ghcr.io/inspektor-gadget/gadget-builder@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
				Architecture: "amd64",
				Os:           "linux",
			}})
			return b, nil, 0, nil
		}, ep, pinnedBuilderDigest)
		if err == nil || !strings.Contains(err.Error(), "missing pinned ref") {
			t.Fatalf("expected RepoDigests mismatch error, got: %v", err)
		}
	})

	t.Run("missing_image", func(t *testing.T) {
		_, err := qualifyPinnedImage(ctx, func(context.Context, string, ...string) ([]byte, []byte, int, error) {
			return nil, []byte("No such image"), 1, errors.New("exit status 1")
		}, ep, pinnedBuilderDigest)
		if err == nil || !strings.Contains(err.Error(), "pinned builder image unavailable") {
			t.Fatalf("expected unavailable image error, got: %v", err)
		}
	})

	t.Run("valid_pinned_image", func(t *testing.T) {
		id, err := qualifyPinnedImage(ctx, func(context.Context, string, ...string) ([]byte, []byte, int, error) {
			b, _ := json.Marshal([]imageInspectData{{
				ID:           pinnedDigest,
				RepoDigests:  []string{pinnedBuilderDigest},
				Architecture: "amd64",
				Os:           "linux",
			}})
			return b, nil, 0, nil
		}, ep, pinnedBuilderDigest)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if id != pinnedDigest {
			t.Fatalf("expected %s, got %s", pinnedDigest, id)
		}
	})
}

func TestQualifyWatchdogAndProductionPayloadLock(t *testing.T) {
	ep := endpointAuthority{cliPath: "docker", hostSocket: defaultDockerHostSocket, approved: true}
	overallEnd := time.Now().Add(60 * time.Second)
	activeCtx, cancel := context.WithDeadline(context.Background(), overallEnd.Add(-retirementReserveSecs*time.Second))
	defer cancel()

	t.Run("watchdog_succeeds_detached_on_137_after_poll_and_retains_receipt", func(t *testing.T) {
		providerDir := t.TempDir()
		fake := newFakeDockerState()
		code137 := 137
		fake.exitCode = &code137
		pollCount := 0
		baseRunner := fake.makeRunner()
		polledRunner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
			for _, a := range args {
				if a == "start" {
					for _, sArg := range args {
						if sArg == "--attach" {
							t.Fatalf("qualifyWatchdog start must be detached (no --attach), got args %v", args)
						}
					}
				}
			}
			out, errOut, code, err := baseRunner(ctx, name, args...)
			for _, a := range args {
				if a == "inspect" && err == nil && code == 0 {
					pollCount++
					if pollCount == 2 {
						var list []inspectData
						if json.Unmarshal(out, &list) == nil && len(list) == 1 {
							runningSt := "running"
							runningTrue := true
							list[0].State.Status = &runningSt
							list[0].State.Running = &runningTrue
							list[0].State.ExitCode = nil
							out, _ = json.Marshal(list)
						}
					}
				}
			}
			return out, errOut, code, err
		}

		wd, err := qualifyWatchdog(activeCtx, polledRunner, ep, pinnedBuilderDigest, testBuilderImageID, overallEnd, providerDir, nil, nil)
		if err != nil {
			t.Fatalf("expected watchdog qualification success, got: %v", err)
		}
		if !wd.qualified || wd.entrypointPath != containerEntrypoint {
			t.Fatalf("unexpected watchdog authority: %+v", wd)
		}
		if pollCount < 3 {
			t.Fatalf("expected >= 3 inspect calls (initial + running poll + terminal), got %d", pollCount)
		}
		if len(fake.mounts) != 0 {
			t.Fatalf("expected zero mounts on watchdog probe, got %+v", fake.mounts)
		}
		if len(fake.cmd) != 5 || !strings.Contains(fake.cmd[4], "trap '' TERM") {
			t.Fatalf("expected TERM-ignoring overlong probe payload, got %v", fake.cmd)
		}
		if fake.callCount("rm") != 1 || fake.callCount("container ls") != 1 {
			t.Fatalf("expected owned watchdog container retirement, calls=%v", fake.calls)
		}

		matches, _ := filepath.Glob(filepath.Join(providerDir, ".mw-watchdog-*", "receipt.json"))
		if len(matches) != 1 {
			t.Fatalf("expected retained watchdog probe receipt, found %d", len(matches))
		}
		data, err := os.ReadFile(matches[0])
		if err != nil {
			t.Fatal(err)
		}
		var receipt map[string]any
		if err := json.Unmarshal(data, &receipt); err != nil {
			t.Fatalf("malformed watchdog receipt: %v", err)
		}
		if receipt["state"] != "retired" || receipt["retired"] != true || receipt["exit_code"] != float64(137) || receipt["detached_start"] != true || receipt["verified_token"] != true {
			t.Fatalf("unexpected watchdog receipt fields: %v", receipt)
		}
		if receipt["cid"] != testCID || receipt["initial_inspect"] == nil || receipt["terminal_inspect"] == nil || receipt["spec"] == nil {
			t.Fatalf("watchdog receipt missing cid/spec/inspect evidence: %v", receipt)
		}
	})

	t.Run("watchdog_retains_receipt_on_uncertain_create", func(t *testing.T) {
		providerDir := t.TempDir()
		runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
			return []byte(testCID + "\n"), []byte("daemon create failure"), 125, errors.New("exit status 125")
		}
		_, err := qualifyWatchdog(activeCtx, runner, ep, pinnedBuilderDigest, testBuilderImageID, overallEnd, providerDir, nil, nil)
		if err == nil || !strings.Contains(err.Error(), "watchdog probe create failed") {
			t.Fatalf("expected create failed error, got: %v", err)
		}
		matches, _ := filepath.Glob(filepath.Join(providerDir, ".mw-watchdog-*", "receipt.json"))
		if len(matches) != 1 {
			t.Fatalf("expected retained watchdog receipt after uncertain create, found %d", len(matches))
		}
		data, _ := os.ReadFile(matches[0])
		var receipt map[string]any
		if err := json.Unmarshal(data, &receipt); err != nil {
			t.Fatal(err)
		}
		if receipt["state"] != "incomplete" || receipt["candidate_cid"] != testCID || receipt["create_error"] == nil {
			t.Fatalf("unexpected uncertain create receipt: %v", receipt)
		}
	})

	t.Run("watchdog_retains_receipt_on_uncertain_initial_inspect", func(t *testing.T) {
		providerDir := t.TempDir()
		fake := newFakeDockerState()
		baseRunner := fake.makeRunner()
		runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
			for _, a := range args {
				if a == "inspect" {
					return nil, []byte("inspect transport failure"), 1, errors.New("exit status 1")
				}
			}
			return baseRunner(ctx, name, args...)
		}
		_, err := qualifyWatchdog(activeCtx, runner, ep, pinnedBuilderDigest, testBuilderImageID, overallEnd, providerDir, nil, nil)
		if err == nil || !strings.Contains(err.Error(), "watchdog inspect failed") {
			t.Fatalf("expected inspect failure, got: %v", err)
		}
		if fake.callCount("rm") != 0 {
			t.Fatalf("must not remove unverified container on inspect failure, calls=%v", fake.calls)
		}
		matches, _ := filepath.Glob(filepath.Join(providerDir, ".mw-watchdog-*", "receipt.json"))
		if len(matches) != 1 {
			t.Fatalf("expected retained watchdog receipt after uncertain inspect, found %d", len(matches))
		}
		data, _ := os.ReadFile(matches[0])
		var receipt map[string]any
		if err := json.Unmarshal(data, &receipt); err != nil {
			t.Fatal(err)
		}
		if receipt["state"] != "created_unverified" || receipt["candidate_cid"] != testCID || receipt["inspect_error"] == nil {
			t.Fatalf("unexpected uncertain inspect receipt: %v", receipt)
		}
	})

	t.Run("watchdog_rejects_start_err_even_with_exit_code_0", func(t *testing.T) {
		providerDir := t.TempDir()
		fake := newFakeDockerState()
		code137 := 137
		fake.exitCode = &code137
		baseRunner := fake.makeRunner()
		runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
			out, errOut, code, err := baseRunner(ctx, name, args...)
			for _, a := range args {
				if a == "start" {
					return out, errOut, 0, classifyRunnerOutcome(true, false, nil)
				}
			}
			return out, errOut, code, err
		}
		_, err := qualifyWatchdog(activeCtx, runner, ep, pinnedBuilderDigest, testBuilderImageID, overallEnd, providerDir, nil, nil)
		if err == nil || !strings.Contains(err.Error(), "watchdog probe start uncertain") {
			t.Fatalf("expected start uncertain error on code=0 with capture overflow, got: %v", err)
		}
		if fake.callCount("stop") != 1 || fake.callCount("rm") != 1 || fake.callCount("container ls") != 1 {
			t.Fatalf("expected forced probe retirement on code=0 startErr, calls=%v", fake.calls)
		}
		matches, _ := filepath.Glob(filepath.Join(providerDir, ".mw-watchdog-*", "receipt.json"))
		if len(matches) != 1 {
			t.Fatalf("expected retained watchdog receipt, found %d", len(matches))
		}
		data, _ := os.ReadFile(matches[0])
		var receipt map[string]any
		_ = json.Unmarshal(data, &receipt)
		if receipt["state"] != "start_uncertain" {
			t.Fatalf("expected state start_uncertain, got %v", receipt["state"])
		}
	})

	t.Run("watchdog_rejects_zero_exit_code", func(t *testing.T) {
		providerDir := t.TempDir()
		fake := newFakeDockerState()
		_, err := qualifyWatchdog(activeCtx, fake.makeRunner(), ep, pinnedBuilderDigest, testBuilderImageID, overallEnd, providerDir, nil, nil)
		if err == nil || !strings.Contains(err.Error(), "exited 0, expected 124 or 137") {
			t.Fatalf("expected exit 0 rejection, got: %v", err)
		}
		if fake.callCount("rm") != 1 {
			t.Fatalf("expected watchdog container to still be retired, calls=%v", fake.calls)
		}
		matches, _ := filepath.Glob(filepath.Join(providerDir, ".mw-watchdog-*", "receipt.json"))
		if len(matches) != 1 {
			t.Fatalf("expected retained watchdog receipt on exit 0 rejection, found %d", len(matches))
		}
	})

	t.Run("watchdog_rejects_unconfirmed_retirement", func(t *testing.T) {
		providerDir := t.TempDir()
		fake := newFakeDockerState()
		code124 := 124
		fake.exitCode = &code124
		fake.lsFail = true
		_, err := qualifyWatchdog(activeCtx, fake.makeRunner(), ep, pinnedBuilderDigest, testBuilderImageID, overallEnd, providerDir, nil, nil)
		if err == nil || !strings.Contains(err.Error(), "watchdog probe container retirement failed") {
			t.Fatalf("expected retirement failure error, got: %v", err)
		}
		matches, _ := filepath.Glob(filepath.Join(providerDir, ".mw-watchdog-*", "receipt.json"))
		if len(matches) != 1 {
			t.Fatalf("expected retained watchdog receipt on retirement failure, found %d", len(matches))
		}
	})

	t.Run("production_payload_cannot_be_overridden", func(t *testing.T) {
		cfg, _ := testConfig(t)
		cfg.isProduction = true
		cfg.testOnlyPayloadOverride = "echo overridden"
		fake := newFakeDockerState()
		err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
		if err == nil || !strings.Contains(err.Error(), "production build payload is locked") {
			t.Fatalf("expected locked payload error, got: %v", err)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("expected 0 runner calls, got: %v", fake.calls)
		}
	})
}

type expiredOpenDoneContext struct {
	deadline time.Time
	done     chan struct{}
}

func (c expiredOpenDoneContext) Deadline() (time.Time, bool) { return c.deadline, true }
func (c expiredOpenDoneContext) Done() <-chan struct{}       { return c.done }
func (c expiredOpenDoneContext) Err() error                  { return nil }
func (c expiredOpenDoneContext) Value(any) any               { return nil }

func TestExpiredButOpenDoneAndCaptureOverflowSeams(t *testing.T) {
	expiredCtx := expiredOpenDoneContext{
		deadline: time.Now().Add(-2 * time.Second),
		done:     make(chan struct{}),
	}
	ep := endpointAuthority{cliPath: "docker", hostSocket: defaultDockerHostSocket, approved: true}
	overallEnd := time.Now().Add(60 * time.Second)

	t.Run("endpoint_rejects_expired_open_done_ctx", func(t *testing.T) {
		called := 0
		_, err := qualifyLocalEndpoint(expiredCtx, func(context.Context, string, ...string) ([]byte, []byte, int, error) {
			called++
			return []byte("linux|x86_64\n"), nil, 0, nil
		}, "docker", defaultDockerHostSocket, validProbeDeps())
		if err == nil || !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("expected DeadlineExceeded, got: %v", err)
		}
		if called != 0 {
			t.Fatalf("expected 0 runner calls on expired context, got %d", called)
		}
	})

	t.Run("image_inspect_rejects_expired_open_done_ctx", func(t *testing.T) {
		called := 0
		_, err := qualifyPinnedImage(expiredCtx, func(context.Context, string, ...string) ([]byte, []byte, int, error) {
			called++
			return nil, nil, 0, nil
		}, ep, pinnedBuilderDigest)
		if err == nil || !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("expected DeadlineExceeded, got: %v", err)
		}
		if called != 0 {
			t.Fatalf("expected 0 runner calls on expired context, got %d", called)
		}
	})

	t.Run("watchdog_rejects_expired_open_done_ctx", func(t *testing.T) {
		providerDir := t.TempDir()
		fake := newFakeDockerState()
		code137 := 137
		fake.exitCode = &code137
		_, err := qualifyWatchdog(expiredCtx, fake.makeRunner(), ep, pinnedBuilderDigest, testBuilderImageID, overallEnd, providerDir, nil, nil)
		if err == nil || !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("expected DeadlineExceeded, got: %v", err)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("expected 0 runner calls on expired context, got %v", fake.calls)
		}
	})

	t.Run("executeHardenedBuild_rejects_expired_open_done_ctx", func(t *testing.T) {
		cfg, _ := testConfig(t)
		fake := newFakeDockerState()
		err := executeHardenedBuild(expiredCtx, fake.makeRunner(), cfg)
		if err == nil || !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("expected DeadlineExceeded, got: %v", err)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("expected 0 runner calls on expired context, got %v", fake.calls)
		}
	})

	t.Run("executeHardenedBuild_rejects_start_code0_with_capture_overflow_error", func(t *testing.T) {
		cfg, _ := testConfig(t)
		fake := newFakeDockerState()
		baseRunner := fake.makeRunner()
		runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
			out, errOut, code, err := baseRunner(ctx, name, args...)
			for _, a := range args {
				if a == "start" {
					return out, errOut, 0, classifyRunnerOutcome(true, false, nil)
				}
			}
			return out, errOut, code, err
		}
		err := executeHardenedBuild(context.Background(), runner, cfg)
		if err == nil || !strings.Contains(err.Error(), "start uncertain or failed") {
			t.Fatalf("expected start uncertain error on code=0 capture overflow, got: %v", err)
		}
		if fake.callCount("stop") != 1 || fake.callCount("rm") != 1 || fake.callCount("container ls") != 1 {
			t.Fatalf("expected forced retirement on code=0 startErr, calls=%v", fake.calls)
		}
	})
}

func TestSingleRetirementSubtractionAndActualStepDeadlines(t *testing.T) {
	cfg, _ := testConfig(t)
	now := time.Now()
	cfg.deadline = now.Add(100 * time.Second)
	cfg.activeDeadline = time.Time{} // derive once from cfg.deadline

	spec, err := constructContainerSpec(buildConfig{
		providerDir:    cfg.providerDir,
		builderImage:   cfg.builderImage,
		builderImageID: cfg.builderImageID,
		deadline:       cfg.deadline,
		activeDeadline: cfg.deadline.Add(-retirementReserveSecs * time.Second),
		endpoint:       cfg.endpoint,
		watchdog:       cfg.watchdog,
	}, "1234567890abcdef1234567890abcdef", t.TempDir())
	if err != nil {
		t.Fatalf("constructContainerSpec failed: %v", err)
	}
	// 80s active - 48s internal active reserve (create30+initialInspect5+1+killAfter5+startTail2+terminalInspect5) = 31..32s.
	if spec.payloadBudget < 31 || spec.payloadBudget > 32 {
		t.Fatalf("unexpected payloadBudget (double subtraction check): got %d, expected 31..32", spec.payloadBudget)
	}

	fake := newFakeDockerState()
	if err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg); err != nil {
		t.Fatalf("expected clean build, got: %v", err)
	}

	var inspectDLs []time.Duration
	var startDL, createDL, rmDL, lsDL time.Duration
	for _, rec := range fake.stepDeadlineLog {
		switch rec.op {
		case "create":
			createDL = rec.rem
		case "inspect":
			inspectDLs = append(inspectDLs, rec.rem)
		case "start":
			startDL = rec.rem
		case "rm":
			rmDL = rec.rem
		case "container ls":
			lsDL = rec.rem
		}
	}
	if createDL <= 29*time.Second || createDL > 30*time.Second+50*time.Millisecond {
		t.Fatalf("create deadline out of bounds (expected ~30s): %v", createDL)
	}
	if len(inspectDLs) != 2 || inspectDLs[0] <= 0 || inspectDLs[0] > 5*time.Second+50*time.Millisecond || inspectDLs[1] <= 0 || inspectDLs[1] > 5*time.Second+50*time.Millisecond {
		t.Fatalf("inspect deadlines out of bounds: %v", inspectDLs)
	}
	// start deadline must stay <= active remaining - 5s terminal inspect reserve (<= 75s for 80s active) and >= 38s (unclamped, 31..32s payload + 7s).
	if startDL <= 37*time.Second || startDL > 75*time.Second+50*time.Millisecond {
		t.Fatalf("start deadline violated terminal inspect reserve or clamped: %v", startDL)
	}
	if rmDL <= 0 || rmDL > 5*time.Second+50*time.Millisecond {
		t.Fatalf("rm deadline out of bounds: %v", rmDL)
	}
	if lsDL <= 0 || lsDL > 5*time.Second+50*time.Millisecond {
		t.Fatalf("container ls deadline out of bounds: %v", lsDL)
	}

	t.Run("post_qualification_budget_checked_before_effects", func(t *testing.T) {
		tightCfg, _ := testConfig(t)
		tNow := time.Now()
		tightCfg.deadline = tNow.Add(80 * time.Second)
		tightCfg.activeDeadline = tNow.Add(time.Duration(activeInternalReserveSecs+minPayloadSeconds-1) * time.Second)
		tightFake := newFakeDockerState()
		err := executeHardenedBuild(context.Background(), tightFake.makeRunner(), tightCfg)
		if err == nil || !strings.Contains(err.Error(), "active stage deadline budget exhausted") {
			t.Fatalf("expected active budget exhausted error, got: %v", err)
		}
		if len(tightFake.calls) != 0 {
			t.Fatalf("expected 0 runner calls before effects, got: %v", tightFake.calls)
		}
		matches, _ := filepath.Glob(filepath.Join(tightCfg.providerDir, ".mw-rebuild-*"))
		if len(matches) != 0 {
			t.Fatalf("expected no .mw-rebuild-* directory created on pre-effect budget failure, found %v", matches)
		}
	})

	t.Run("slow_prestart_refuses_underbudgeted_start_and_retires", func(t *testing.T) {
		tightCfg, _ := testConfig(t)
		tNow := time.Now()
		curNow := tNow
		tightCfg.nowFunc = func() time.Time { return curNow }
		// 53.5s active -> payloadBudget=5s -> startTimeout=12s (+ 5s terminal inspect = 17s required before start).
		tightCfg.deadline = tNow.Add(74 * time.Second)
		tightCfg.activeDeadline = tNow.Add(53500 * time.Millisecond)
		slowFake := newFakeDockerState()
		baseRunner := slowFake.makeRunner()
		runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
			for _, a := range args {
				if a == "create" {
					curNow = curNow.Add(37 * time.Second)
					break
				}
			}
			return baseRunner(ctx, name, args...)
		}
		err := executeHardenedBuild(context.Background(), runner, tightCfg)
		if err == nil || !strings.Contains(err.Error(), "insufficient budget for start") {
			t.Fatalf("expected insufficient budget for start error, got: %v", err)
		}
		if slowFake.callCount("start") != 0 {
			t.Fatalf("must not call start when remaining budget cannot cover startTimeout + terminalInspectReserve, calls=%v", slowFake.calls)
		}
		if slowFake.callCount("rm") != 1 || slowFake.callCount("container ls") != 1 {
			t.Fatalf("expected forced retirement of unstarted container, calls=%v", slowFake.calls)
		}
	})

	// Verify boundedRetirementContext never extends beyond fixedOverallEnd.
	shortEnd := time.Now().Add(7 * time.Second)
	retCtx, retCancel, err := boundedRetirementContext(shortEnd)
	if err != nil {
		t.Fatalf("boundedRetirementContext failed: %v", err)
	}
	defer retCancel()
	retDL, ok := retCtx.Deadline()
	if !ok || retDL.After(shortEnd) {
		t.Fatalf("retirement deadline %v exceeded fixedOverallEnd %v", retDL, shortEnd)
	}

	if _, _, err := boundedRetirementContext(time.Now().Add(-1 * time.Second)); err == nil {
		t.Fatal("expected error for expired fixedOverallEnd in boundedRetirementContext")
	}
}

func TestCreateBudgetAllowsSlowCreateUpTo30sInsideFixedOverall(t *testing.T) {
	t.Run("actual_build_slow_create_over_5s_under_30s_succeeds_without_clamp_or_renewal", func(t *testing.T) {
		providerDir := t.TempDir()
		synctest.Test(t, func(t *testing.T) {
			now := time.Now()
			fixedOverallEnd := now.Add(100 * time.Second)
			activeEnd := fixedOverallEnd.Add(-retirementReserveSecs * time.Second)
			cfg := buildConfig{
				providerDir:    providerDir,
				builderImage:   testBuilderDigest,
				builderImageID: testBuilderImageID,
				deadline:       fixedOverallEnd,
				activeDeadline: activeEnd,
				endpoint: endpointAuthority{
					cliPath:    "docker",
					hostSocket: defaultDockerHostSocket,
					approved:   true,
				},
				watchdog: watchdogAuthority{
					entrypointPath: containerEntrypoint,
					qualified:      true,
				},
			}

			fake := newFakeDockerState()
			baseRunner := fake.makeRunner()
			type absoluteStepDeadline struct {
				op        string
				deadline  time.Time
				remAtCall time.Duration
			}
			var recorded []absoluteStepDeadline
			runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
				op := ""
				for _, a := range args {
					if a == "create" || a == "inspect" || a == "start" || a == "stop" || a == "rm" || a == "container" {
						op = a
						break
					}
				}
				if op == "container" {
					op = "container ls"
				}
				dl, ok := ctx.Deadline()
				if !ok {
					t.Fatalf("step %s missing context deadline", op)
				}
				recorded = append(recorded, absoluteStepDeadline{
					op:        op,
					deadline:  dl,
					remAtCall: time.Until(dl),
				})
				if dl.After(fixedOverallEnd) {
					t.Fatalf("step %s deadline %v renewed/exceeded fixedOverallEnd %v", op, dl, fixedOverallEnd)
				}
				if op == "create" {
					// Slow docker create (>5s old limit, <=30s new limit) advances synthetic clock cleanly.
					time.Sleep(18 * time.Second)
					if err := ctx.Err(); err != nil {
						t.Fatalf("create context prematurely expired during 18s slow create: %v", err)
					}
				}
				return baseRunner(ctx, name, args...)
			}

			if err := executeHardenedBuild(context.Background(), runner, cfg); err != nil {
				t.Fatalf("expected clean build across 18s slow create, got: %v", err)
			}

			var inspectCount int
			for _, rec := range recorded {
				switch rec.op {
				case "create":
					if rec.remAtCall != 30*time.Second {
						t.Fatalf("expected create deadline=30s, got %v", rec.remAtCall)
					}
					if rec.deadline.After(activeEnd) {
						t.Fatalf("create deadline %v exceeded activeEnd %v", rec.deadline, activeEnd)
					}
				case "inspect":
					inspectCount++
					if rec.remAtCall != 5*time.Second {
						t.Fatalf("expected 5s inspect limit, got %v", rec.remAtCall)
					}
					if rec.deadline.After(activeEnd) {
						t.Fatalf("inspect deadline %v exceeded activeEnd %v", rec.deadline, activeEnd)
					}
				case "start":
					// 80s active - 48s internal reserve = 32s payloadBudget; startTimeout = 32+5+2 = 39s (unclamped).
					if rec.remAtCall != 39*time.Second {
						t.Fatalf("expected unclamped start deadline 39s, got %v", rec.remAtCall)
					}
					if rec.deadline.After(activeEnd.Add(-5 * time.Second)) {
						t.Fatalf("start deadline %v violated 5s terminal inspect reserve before %v", rec.deadline, activeEnd)
					}
				case "rm", "container ls":
					if rec.remAtCall != 5*time.Second {
						t.Fatalf("expected 5s cleanup step limit on %s, got %v", rec.op, rec.remAtCall)
					}
					if rec.deadline.After(fixedOverallEnd) {
						t.Fatalf("cleanup step %s deadline %v exceeded fixedOverallEnd %v", rec.op, rec.deadline, fixedOverallEnd)
					}
				}
			}
			if inspectCount != 2 {
				t.Fatalf("expected 2 inspect calls, got %d", inspectCount)
			}
		})
	})

	t.Run("watchdog_slow_create_over_5s_under_30s_succeeds_within_5s_noncreate_limits", func(t *testing.T) {
		providerDir := t.TempDir()
		synctest.Test(t, func(t *testing.T) {
			now := time.Now()
			fixedOverallEnd := now.Add(90 * time.Second)
			activeEnd := fixedOverallEnd.Add(-retirementReserveSecs * time.Second)
			activeCtx, cancel := context.WithDeadline(context.Background(), activeEnd)
			defer cancel()

			ep := endpointAuthority{cliPath: "docker", hostSocket: defaultDockerHostSocket, approved: true}
			fake := newFakeDockerState()
			code137 := 137
			fake.exitCode = &code137
			baseRunner := fake.makeRunner()

			runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
				op := ""
				for _, a := range args {
					if a == "create" || a == "inspect" || a == "start" || a == "stop" || a == "rm" || a == "container" {
						op = a
						break
					}
				}
				if op == "container" {
					op = "container ls"
				}
				dl, ok := ctx.Deadline()
				if !ok {
					t.Fatalf("watchdog step %s missing deadline", op)
				}
				if dl.After(fixedOverallEnd) {
					t.Fatalf("watchdog step %s deadline %v exceeded fixedOverallEnd %v", op, dl, fixedOverallEnd)
				}
				rem := time.Until(dl)
				if op == "create" {
					if rem != 30*time.Second {
						t.Fatalf("expected watchdog create deadline=30s, got %v", rem)
					}
					time.Sleep(12 * time.Second)
				} else if rem != 5*time.Second {
					t.Fatalf("expected 5s non-create limit for watchdog step %s, got %v", op, rem)
				}
				return baseRunner(ctx, name, args...)
			}

			wd, err := qualifyWatchdog(activeCtx, runner, ep, pinnedBuilderDigest, testBuilderImageID, fixedOverallEnd, providerDir, nil, nil)
			if err != nil {
				t.Fatalf("expected watchdog qualification across 12s slow create, got: %v", err)
			}
			if !wd.qualified {
				t.Fatal("expected qualified watchdog authority")
			}
			matches, _ := filepath.Glob(filepath.Join(providerDir, ".mw-watchdog-*", "receipt.json"))
			if len(matches) != 1 {
				t.Fatalf("expected retained watchdog receipt, found %d", len(matches))
			}
		})
	})

	t.Run("create_exceeding_30s_times_out_at_30s_empty_cid_no_unowned_cleanup", func(t *testing.T) {
		providerDir := t.TempDir()
		synctest.Test(t, func(t *testing.T) {
			now := time.Now()
			fixedOverallEnd := now.Add(540 * time.Second)
			activeEnd := fixedOverallEnd.Add(-retirementReserveSecs * time.Second)
			cfg := buildConfig{
				providerDir:    providerDir,
				builderImage:   testBuilderDigest,
				builderImageID: testBuilderImageID,
				deadline:       fixedOverallEnd,
				activeDeadline: activeEnd,
				endpoint: endpointAuthority{
					cliPath:    "docker",
					hostSocket: defaultDockerHostSocket,
					approved:   true,
				},
				watchdog: watchdogAuthority{
					entrypointPath: containerEntrypoint,
					qualified:      true,
				},
			}

			var calls []string
			var createElapsed time.Duration
			runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
				op := ""
				for _, a := range args {
					if a == "create" || a == "stop" || a == "rm" || a == "inspect" {
						op = a
						break
					}
				}
				calls = append(calls, op)
				if op == "create" {
					startAt := time.Now()
					<-ctx.Done()
					createElapsed = time.Since(startAt)
					return nil, []byte("context deadline exceeded"), -1, ctx.Err()
				}
				return nil, nil, 0, nil
			}

			err := executeHardenedBuild(context.Background(), runner, cfg)
			if err == nil || !strings.Contains(err.Error(), "create outcome unknown; no cleanup authority") {
				t.Fatalf("expected create timeout with no cleanup authority, got: %v", err)
			}
			if createElapsed != 30*time.Second {
				t.Fatalf("expected create to time out at 30s, got %v", createElapsed)
			}
			if len(calls) != 1 || calls[0] != "create" {
				t.Fatalf("expected only create call (no unowned rm/stop), got %v", calls)
			}
		})
	})

	t.Run("tight_budget_exhaustion_fails_closed_at_prestart_after_worst_case_overhead", func(t *testing.T) {
		providerDir := t.TempDir()
		synctest.Test(t, func(t *testing.T) {
			now := time.Now()
			// 53.5s active -> payloadBudget=5s -> startTimeout=12s (+5s terminal inspect = 17s required).
			// Worst-case 30s create + 5s initial inspect + 2s stall = 37s spent -> 16.5s left (<17s) -> must fail closed and retire.
			fixedOverallEnd := now.Add(74 * time.Second)
			activeEnd := now.Add(53500 * time.Millisecond)
			cfg := buildConfig{
				providerDir:    providerDir,
				builderImage:   testBuilderDigest,
				builderImageID: testBuilderImageID,
				deadline:       fixedOverallEnd,
				activeDeadline: activeEnd,
				endpoint: endpointAuthority{
					cliPath:    "docker",
					hostSocket: defaultDockerHostSocket,
					approved:   true,
				},
				watchdog: watchdogAuthority{
					entrypointPath: containerEntrypoint,
					qualified:      true,
				},
			}

			fake := newFakeDockerState()
			baseRunner := fake.makeRunner()
			runner := func(ctx context.Context, name string, args ...string) ([]byte, []byte, int, error) {
				for _, a := range args {
					if a == "create" {
						time.Sleep(29900 * time.Millisecond)
						break
					}
					if a == "inspect" {
						time.Sleep(4900 * time.Millisecond)
						break
					}
				}
				resOut, resErr, resCode, resRunErr := baseRunner(ctx, name, args...)
				for _, a := range args {
					if a == "inspect" {
						// Simulate 2.2s receipt/scheduling stall after initial inspect returns before start gate.
						time.Sleep(2200 * time.Millisecond)
						break
					}
				}
				return resOut, resErr, resCode, resRunErr
			}

			err := executeHardenedBuild(context.Background(), runner, cfg)
			if err == nil || !strings.Contains(err.Error(), "insufficient budget for start") {
				t.Fatalf("expected fail-closed insufficient budget for start, got: %v", err)
			}
			if fake.callCount("start") != 0 {
				t.Fatalf("must not clamp or call start when remaining budget < 17s, calls=%v", fake.calls)
			}
			if fake.callCount("rm") != 1 || fake.callCount("container ls") != 1 {
				t.Fatalf("expected forced retirement after prestart budget exhaustion, calls=%v", fake.calls)
			}
			if dl, ok := fake.stepDeadlines["rm"]; !ok || dl <= 0 || dl > 5*time.Second {
				t.Fatalf("expected 5s cleanup rm deadline, got %v", dl)
			}
			if dl, ok := fake.stepDeadlines["container ls"]; !ok || dl <= 0 || dl > 5*time.Second {
				t.Fatalf("expected 5s cleanup container ls deadline, got %v", dl)
			}
		})
	})
}

func TestOverlongPayloadTimeoutControlUnit(t *testing.T) {
	cfg, _ := testConfig(t)
	now := time.Now()
	cfg.deadline = now.Add(74 * time.Second)
	cfg.activeDeadline = now.Add(54 * time.Second)
	cfg.isProduction = false
	cfg.testOnlyPayloadOverride = "trap '' TERM; sleep 60"

	fake := newFakeDockerState()
	fake.startExitCode = 137
	code137 := 137
	fake.exitCode = &code137

	err := executeHardenedBuild(context.Background(), fake.makeRunner(), cfg)
	if err == nil || !strings.Contains(err.Error(), "native build failed with exit code 137") {
		t.Fatalf("expected exit code 137 failure, got: %v", err)
	}
	if fake.callCount("rm") != 1 || fake.callCount("container ls") != 1 {
		t.Fatalf("expected owned container retirement after overlong timeout, calls=%v", fake.calls)
	}
	matches, _ := filepath.Glob(filepath.Join(cfg.providerDir, ".mw-rebuild-*", "receipt.json"))
	if len(matches) != 1 {
		t.Fatalf("expected 1 receipt, found %d", len(matches))
	}
	data, _ := os.ReadFile(matches[0])
	var receipt map[string]any
	if err := json.Unmarshal(data, &receipt); err != nil {
		t.Fatalf("malformed receipt: %v", err)
	}
	if receipt["state"] != "exited" || receipt["exit_code"] != float64(137) {
		t.Fatalf("unexpected receipt contents: %v", receipt)
	}
}

func TestPinnedImageWatchdogAndDetachedClientQualification(t *testing.T) {
	if os.Getenv("MW_RUN_DOCKER_BUILDER_QUALIFICATION") != "1" {
		t.Skip("set MW_RUN_DOCKER_BUILDER_QUALIFICATION=1 to run real pinned-image Docker qualification")
	}

	receiptOutDir := os.Getenv("MW_QUALIFICATION_RECEIPT_DIR")
	if receiptOutDir != "" {
		if err := os.MkdirAll(receiptOutDir, 0700); err != nil {
			t.Fatal(err)
		}
	}

	ctx := context.Background()
	ep, err := qualifyLocalEndpoint(ctx, defaultExecRunner, "docker", defaultDockerHostSocket, endpointProbeDeps{})
	if err != nil {
		t.Fatalf("qualifyLocalEndpoint failed: %v", err)
	}
	imageID, err := qualifyPinnedImage(ctx, defaultExecRunner, ep, pinnedBuilderDigest)
	if err != nil {
		t.Fatalf("qualifyPinnedImage failed: %v", err)
	}

	wdProviderDir := t.TempDir()
	wd, err := qualifyWatchdog(ctx, defaultExecRunner, ep, pinnedBuilderDigest, imageID, time.Now().Add(60*time.Second), wdProviderDir, generateNonce, nil)
	if err != nil {
		t.Fatalf("qualifyWatchdog failed: %v", err)
	}
	wdMatches, _ := filepath.Glob(filepath.Join(wdProviderDir, ".mw-watchdog-*", "receipt.json"))
	if len(wdMatches) != 1 {
		t.Fatalf("expected retained watchdog receipt in %s, found %d", wdProviderDir, len(wdMatches))
	}
	if receiptOutDir != "" {
		if wdBytes, readErr := os.ReadFile(wdMatches[0]); readErr == nil {
			_ = os.WriteFile(filepath.Join(receiptOutDir, "watchdog-probe-receipt.json"), wdBytes, 0600)
		}
	}

	t.Run("overlong_payload_exits_via_pid1_timeout", func(t *testing.T) {
		providerDir := t.TempDir()
		now := time.Now()
		deadline := now.Add(75 * time.Second)
		activeDeadline := deadline.Add(-retirementReserveSecs * time.Second)
		cfg := buildConfig{
			providerDir:             providerDir,
			builderImage:            pinnedBuilderDigest,
			builderImageID:          imageID,
			deadline:                deadline,
			activeDeadline:          activeDeadline,
			endpoint:                ep,
			watchdog:                wd,
			isProduction:            false,
			testOnlyPayloadOverride: "trap '' TERM; sleep 60",
		}
		startAt := time.Now()
		err := executeHardenedBuild(context.Background(), defaultExecRunner, cfg)
		elapsed := time.Since(startAt)
		if err == nil || (!strings.Contains(err.Error(), "exit code 137") && !strings.Contains(err.Error(), "exit code 124")) {
			t.Fatalf("expected PID-1 timeout exit 137/124, got: %v (elapsed %v)", err, elapsed)
		}
		if elapsed > 30*time.Second {
			t.Fatalf("overlong payload exceeded bounded test budget: %v", elapsed)
		}

		matches, _ := filepath.Glob(filepath.Join(providerDir, ".mw-rebuild-*", "receipt.json"))
		if len(matches) != 1 {
			t.Fatalf("expected 1 receipt, found %d", len(matches))
		}
		receiptBytes, err := os.ReadFile(matches[0])
		if err != nil {
			t.Fatal(err)
		}
		if receiptOutDir != "" {
			_ = os.WriteFile(filepath.Join(receiptOutDir, "overlong-timeout-receipt.json"), receiptBytes, 0600)
			_ = os.WriteFile(filepath.Join(receiptOutDir, "overlong-timeout-result.txt"), []byte(fmt.Sprintf("elapsed=%v\nerr=%v\n", elapsed, err)), 0600)
		}
		var receipt map[string]any
		if err := json.Unmarshal(receiptBytes, &receipt); err != nil {
			t.Fatal(err)
		}
		cid, _ := receipt["cid"].(string)
		if !isValidCID(cid) {
			t.Fatalf("missing valid cid in receipt: %s", string(receiptBytes))
		}
		if err := verifyContainerAbsence(context.Background(), defaultExecRunner, ep, cid); err != nil {
			t.Fatalf("container %s not retired: %v", cid, err)
		}
	})

	t.Run("killed_client_does_not_keep_container_alive", func(t *testing.T) {
		nonce, err := generateNonce()
		if err != nil {
			t.Fatal(err)
		}
		probeDir := t.TempDir()
		spec := containerSpec{
			name:          "mw-profiler-detached-ctrl-" + nonce,
			nonce:         nonce,
			cidFile:       filepath.Join(probeDir, "cid"),
			imageRef:      pinnedBuilderDigest,
			imageID:       imageID,
			entrypoint:    []string{containerEntrypoint},
			cmd:           []string{"--kill-after=2s", "5s", "/bin/bash", "-c", "trap '' TERM; sleep 60"},
			networkMode:   "none",
			pidMode:       "",
			restartPolicy: "no",
			user:          fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid()),
		}
		createOut, createErrOut, createCode, createErr := defaultExecRunner(
			ctx, ep.cliPath, "--host", ep.hostSocket, "create",
			"--pull=never", "--name", spec.name,
			"--label", invocationLabelKey+"="+nonce,
			"--cidfile", spec.cidFile,
			"--restart="+spec.restartPolicy,
			"--network="+spec.networkMode,
			"--user", spec.user,
			"--entrypoint", spec.entrypoint[0],
			spec.imageRef,
			spec.cmd[0], spec.cmd[1], spec.cmd[2], spec.cmd[3], spec.cmd[4],
		)
		cand, candErr := resolveCandidateWitness(createOut, createErr, spec.cidFile, probeDir, nonce)
		if candErr != nil || createCode != 0 {
			t.Fatalf("create failed: %v %v %s", createErr, candErr, string(createErrOut))
		}
		inspOut, _, inspCode, inspErr := defaultExecRunner(ctx, ep.cliPath, "--host", ep.hostSocket, "inspect", cand.cid)
		if inspErr != nil || inspCode != 0 {
			t.Fatalf("initial inspect failed: %v", inspErr)
		}
		var inspList []inspectData
		if err := json.Unmarshal(inspOut, &inspList); err != nil || len(inspList) != 1 {
			t.Fatalf("malformed inspect: %v", err)
		}
		token, err := verifyInspectInitial(&inspList[0], cand, spec, ep)
		if err != nil {
			t.Fatalf("verifyInspectInitial failed: %v", err)
		}
		t.Cleanup(func() {
			retCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			_ = retireOwnedToken(retCtx, defaultExecRunner, token, true)
		})

		attachCmd := exec.Command(ep.cliPath, "--host", ep.hostSocket, "start", "--attach", token.cid)
		if err := attachCmd.Start(); err != nil {
			t.Fatalf("failed to start attach client: %v", err)
		}
		clientPID := attachCmd.Process.Pid

		// Wait until container is actually running, then SIGKILL the attach client.
		deadlineWait := time.Now().Add(10 * time.Second)
		runningObserved := false
		for time.Now().Before(deadlineWait) {
			out, _, code, err := defaultExecRunner(ctx, ep.cliPath, "--host", ep.hostSocket, "inspect", token.cid)
			if err == nil && code == 0 {
				var list []inspectData
				if json.Unmarshal(out, &list) == nil && len(list) == 1 && list[0].State.Running != nil && *list[0].State.Running {
					runningObserved = true
					break
				}
			}
			time.Sleep(150 * time.Millisecond)
		}
		if !runningObserved {
			_ = attachCmd.Process.Kill()
			_ = attachCmd.Wait()
			t.Fatal("container never entered running state before client kill")
		}

		if err := attachCmd.Process.Kill(); err != nil {
			t.Fatalf("failed to SIGKILL attach client: %v", err)
		}
		_ = attachCmd.Wait()

		// Confirm container exits autonomously without client cooperation via PID-1 timeout.
		exitDeadline := time.Now().Add(15 * time.Second)
		var termOut []byte
		var termExit int
		exited := false
		for time.Now().Before(exitDeadline) {
			out, _, code, err := defaultExecRunner(ctx, ep.cliPath, "--host", ep.hostSocket, "inspect", token.cid)
			if err == nil && code == 0 {
				var list []inspectData
				if json.Unmarshal(out, &list) == nil && len(list) == 1 {
					ec, vErr := verifyTerminalInspect(&list[0], token, spec)
					if vErr == nil {
						termOut = out
						termExit = ec
						exited = true
						break
					}
				}
			}
			time.Sleep(250 * time.Millisecond)
		}
		if !exited {
			t.Fatalf("container %s did not exit autonomously after client SIGKILL (pid %d)", token.cid, clientPID)
		}
		if termExit != 137 && termExit != 124 {
			t.Fatalf("expected autonomous timeout exit 137/124, got %d", termExit)
		}

		retCtx, retCancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer retCancel()
		if err := retireOwnedToken(retCtx, defaultExecRunner, token, false); err != nil {
			t.Fatalf("retireOwnedToken failed: %v", err)
		}
		if receiptOutDir != "" {
			_ = os.WriteFile(filepath.Join(receiptOutDir, "killed-client-terminal-inspect.json"), termOut, 0600)
			summary := fmt.Sprintf("cid=%s\nclient_pid_killed=%d\nterminal_exit_code=%d\nretired=true\n", token.cid, clientPID, termExit)
			_ = os.WriteFile(filepath.Join(receiptOutDir, "killed-client-receipt.txt"), []byte(summary), 0600)
		}
	})
}
