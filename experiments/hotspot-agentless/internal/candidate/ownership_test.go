package candidate

import (
	"context"
	"errors"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const receiptID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

type faultDocker struct {
	driver.Docker
	output func(time.Duration, ...string) (string, error)
}

func (d faultDocker) Output(timeout time.Duration, args ...string) (string, error) {
	return d.output(timeout, args...)
}

type faultProcess struct {
	waits                                []error
	killError                            error
	waitAttempted, reaped, killAttempted bool
}

func (p *faultProcess) wait(time.Duration) error {
	p.waitAttempted = true
	if len(p.waits) == 0 {
		p.reaped = true
		return nil
	}
	err := p.waits[0]
	p.waits = p.waits[1:]
	p.reaped = err == nil
	return err
}
func (p *faultProcess) kill() error  { p.killAttempted = true; return p.killError }
func (p *faultProcess) pid() int     { return 123 }
func (p *faultProcess) joined() bool { return p.reaped }

func TestCleanupRetainsRemoteFailurePrimaryAndEveryWrapper(t *testing.T) {
	for _, mode := range []string{"still present", "query denied", "query timeout", "remove denied", "timeout", "removed despite error"} {
		t.Run(mode, func(t *testing.T) {
			cause := os.ErrPermission
			if mode == "timeout" || mode == "query timeout" {
				cause = context.DeadlineExceeded
			}
			present := true
			docker := faultDocker{output: func(_ time.Duration, args ...string) (string, error) {
				switch args[0] {
				case "ps":
					if mode == "query denied" || mode == "query timeout" {
						return "", cause
					}
					if present {
						return receiptID + "\n", nil
					}
					return "", nil
				case "rm":
					if mode == "removed despite error" {
						present = false
					}
					if mode == "remove denied" || mode == "timeout" || mode == "removed despite error" {
						return "", cause
					}
					return "", nil
				default:
					t.Fatalf("unexpected command %v", args)
					return "", nil
				}
			}}
			first, second := &faultProcess{}, &faultProcess{}
			owners := candidateOwners{docker: docker, requests: []*containerReceipt{{ID: receiptID}}, processes: []processOwner{first, second}}
			primary := errors.New("original capture failure")
			root := t.TempDir()
			err := owners.finish(root, map[string]any{"success": true}, primary)
			var cleanup *candidateCleanupError
			if !errors.Is(err, primary) || !errors.As(err, &cleanup) || cleanup.owners != &owners || !first.waitAttempted || !second.waitAttempted {
				t.Fatalf("failure/owner/join lost: %v", err)
			}
			if mode != "still present" && !errors.Is(err, cause) {
				t.Fatalf("native cause lost: %v", err)
			}
			wantPending := 1
			if mode == "removed despite error" {
				wantPending = 0
			}
			if len(cleanup.report.PendingContainers) != wantPending || len(cleanup.report.Containers) != 1 || cleanup.report.Containers[0].ID != receiptID {
				t.Fatalf("wrong pending proof: %+v", cleanup.report)
			}
			if _, err := os.Stat(filepath.Join(root, "result.json")); !os.IsNotExist(err) {
				t.Fatal("published success before retirement")
			}
			if _, err := os.Stat(filepath.Join(root, "cleanup.json")); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestCleanupRetainsUnjoinedHandleAndContinuesJoining(t *testing.T) {
	first := &faultProcess{waits: []error{context.DeadlineExceeded, context.DeadlineExceeded}, killError: os.ErrPermission}
	second := &faultProcess{}
	owners := candidateOwners{processes: []processOwner{first, second}}
	report, err := owners.retire()
	var cleanup *candidateCleanupError
	if report.ResourcesRetired || len(report.PendingWrappers) != 1 || !first.killAttempted || !second.waitAttempted || !errors.Is(err, os.ErrPermission) || !errors.As(err, &cleanup) || cleanup.owners.processes[0] != first {
		t.Fatalf("lost pending actual handle: %+v %v", report, err)
	}
}

func TestCleanupWaitFailureRetainsActualWrapperHandle(t *testing.T) {
	process := &faultProcess{waits: []error{os.ErrPermission, os.ErrPermission}, killError: os.ErrPermission}
	owners := candidateOwners{processes: []processOwner{process}}
	report, err := owners.retire()
	if report.ResourcesRetired || len(report.PendingWrappers) != 1 || !errors.Is(err, os.ErrPermission) {
		t.Fatalf("failed wait treated as join proof: %+v %v", report, err)
	}
}

func TestAmbiguousAcquisitionRetainsRequestAndRecoversLateReceipt(t *testing.T) {
	late, present := false, true
	var requestName, label string
	docker := faultDocker{output: func(_ time.Duration, args ...string) (string, error) {
		switch args[0] {
		case "create":
			if args[1] != "--name" || args[3] != "--label" {
				t.Fatalf("unowned create: %v", args)
			}
			requestName, label = args[2], args[4]
			return "", context.DeadlineExceeded
		case "ps":
			joined := strings.Join(args, " ")
			if strings.Contains(joined, "name=") && (!strings.Contains(joined, "name=^/"+requestName+"$") || !strings.Contains(joined, "label="+label)) {
				t.Fatalf("unqualified recovery %v", args)
			}
			if late && present {
				return receiptID + "\n", nil
			}
			return "", nil
		case "rm":
			if args[2] != receiptID {
				t.Fatal("removed invented identity")
			}
			present = false
			return "", nil
		default:
			t.Fatalf("unexpected command %v", args)
			return "", nil
		}
	}}
	owners := candidateOwners{docker: docker}
	if _, err := owners.create("target", "--pull=never", "image"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
	report, err := owners.retire()
	if err == nil || report.ResourcesRetired || len(report.PendingAcquisitions) != 1 || report.PendingAcquisitions[0].Name != requestName {
		t.Fatalf("late create falsely absent: %+v %v", report, err)
	}
	late = true
	report, err = owners.retire()
	if err != nil || !report.ResourcesRetired || present || owners.requests[0].ID != receiptID {
		t.Fatalf("late receipt not recovered/retired: %+v %v", report, err)
	}
}

func TestCleanRetirementPrecedesResultPublication(t *testing.T) {
	present := true
	docker := faultDocker{output: func(_ time.Duration, args ...string) (string, error) {
		switch args[0] {
		case "ps":
			if present {
				return receiptID + "\n", nil
			}
			return "", nil
		case "rm":
			present = false
			return "", nil
		default:
			t.Fatalf("unexpected command %v", args)
			return "", nil
		}
	}}
	owners := candidateOwners{docker: docker, requests: []*containerReceipt{{ID: receiptID}}}
	root := t.TempDir()
	if err := owners.finish(root, map[string]any{"production_qualified": false}, nil); err != nil {
		t.Fatal(err)
	}
	var result struct {
		ResourcesRetired bool `json:"resources_retired"`
	}
	if err := driver.ReadJSON(filepath.Join(root, "result.json"), &result); err != nil || !result.ResourcesRetired || present {
		t.Fatalf("unretired publication: %+v %v", result, err)
	}
}
