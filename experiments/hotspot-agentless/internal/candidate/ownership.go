package candidate

import (
	"context"
	"errors"
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

const candidateLabel = "io.mazewall.qual.session"

type candidateDocker interface {
	Output(time.Duration, ...string) (string, error)
	Start(*os.File, *os.File, ...string) (*exec.Cmd, error)
}

type containerReceipt struct {
	ID      string `json:"id,omitempty"`
	Name    string `json:"name"`
	Session string `json:"session"`
}

type processOwner interface {
	wait(time.Duration) error
	kill() error
	pid() int
	joined() bool
}

type ownedCommand struct {
	command *exec.Cmd
	done    chan struct{}
	failure error
}

func (p *ownedCommand) wait(timeout time.Duration) error {
	select {
	case <-p.done:
		return p.failure
	default:
	}
	if timeout <= 0 {
		return context.DeadlineExceeded
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-p.done:
		return p.failure
	case <-timer.C:
		return context.DeadlineExceeded
	}
}
func (p *ownedCommand) kill() error { return p.command.Process.Kill() }
func (p *ownedCommand) pid() int    { return p.command.Process.Pid }
func (p *ownedCommand) joined() bool {
	select {
	case <-p.done:
		return p.command.ProcessState != nil
	default:
		return false
	}
}

type candidateOwners struct {
	docker    candidateDocker
	requests  []*containerReceipt
	processes []processOwner
}

func (o *candidateOwners) create(role string, args ...string) (string, error) {
	nonce, err := driver.Token(12)
	if err != nil {
		return "", err
	}
	request := &containerReceipt{Name: "mw-candidate-" + role + "-" + nonce, Session: nonce}
	o.requests = append(o.requests, request)
	argv := append([]string{"create", "--name", request.Name, "--label", candidateLabel + "=" + nonce}, args...)
	text, err := o.docker.Output(20*time.Second, argv...)
	id := strings.TrimSpace(text)
	if driver.ValidContainerID(id) {
		request.ID = id
	}
	if err != nil {
		return "", err
	}
	if request.ID == "" {
		return "", fmt.Errorf("missing/invalid create receipt for %s", request.Name)
	}
	return request.ID, nil
}

func (o *candidateOwners) start(stdout, stderr *os.File, id string) (processOwner, error) {
	command, err := o.docker.Start(stdout, stderr, "start", "--attach", id)
	if err != nil {
		return nil, err
	}
	p := &ownedCommand{command: command, done: make(chan struct{})}
	o.processes = append(o.processes, p)
	go func() { p.failure = command.Wait(); close(p.done) }()
	return p, nil
}

type cleanupReport struct {
	ResourcesRetired    bool               `json:"resources_retired"`
	Containers          []containerReceipt `json:"containers"`
	Wrappers            []int              `json:"wrappers"`
	PendingContainers   []string           `json:"pending_containers"`
	PendingAcquisitions []containerReceipt `json:"pending_acquisitions"`
	PendingWrappers     []int              `json:"pending_wrappers"`
	Failures            []string           `json:"failures"`
}

type candidateCleanupError struct {
	report cleanupReport
	owners *candidateOwners
	cause  error
}

func (e *candidateCleanupError) Error() string {
	return fmt.Sprintf("candidate cleanup incomplete or failed: %v", e.cause)
}
func (e *candidateCleanupError) Unwrap() error { return e.cause }

func (o *candidateOwners) retire() (cleanupReport, error) {
	deadline := time.Now().Add(20 * time.Second)
	report := cleanupReport{PendingContainers: []string{}, PendingAcquisitions: []containerReceipt{}, PendingWrappers: []int{}, Failures: []string{}}
	var failure error
	record := func(err error) {
		if err != nil {
			failure = errors.Join(failure, err)
			report.Failures = append(report.Failures, fmt.Sprintf("%T: %v", err, err))
		}
	}
	for i := len(o.requests) - 1; i >= 0; i-- {
		r := o.requests[i]
		if r.ID == "" {
			var text string
			var err error
			if left := time.Until(deadline); left > 0 {
				text, err = o.docker.Output(min(5*time.Second, left), "ps", "-a", "--no-trunc", "--filter", "name=^/"+r.Name+"$", "--filter", "label="+candidateLabel+"="+r.Session, "--format", "{{.ID}}")
			} else {
				err = context.DeadlineExceeded
			}
			values := strings.Fields(text)
			if err == nil && len(values) == 1 && driver.ValidContainerID(values[0]) {
				r.ID = values[0]
			} else {
				report.PendingAcquisitions = append(report.PendingAcquisitions, *r)
				record(errors.Join(err, fmt.Errorf("create outcome remains unknown for %s", r.Name)))
				continue
			}
		}
		pending, err := driver.RetireOwned(o.docker, deadline, r.ID)
		report.PendingContainers = append(report.PendingContainers, pending...)
		record(err)
	}
	for _, p := range o.processes {
		err := p.wait(min(5*time.Second, time.Until(deadline)))
		record(err)
		if !p.joined() {
			record(p.kill())
			err = p.wait(min(5*time.Second, time.Until(deadline)))
			record(err)
			if !p.joined() {
				report.PendingWrappers = append(report.PendingWrappers, p.pid())
				record(fmt.Errorf("owned wrapper %d not joined", p.pid()))
			}
		}
	}
	report.ResourcesRetired = len(report.PendingContainers) == 0 && len(report.PendingAcquisitions) == 0 && len(report.PendingWrappers) == 0
	for _, r := range o.requests {
		report.Containers = append(report.Containers, *r)
	}
	for _, p := range o.processes {
		report.Wrappers = append(report.Wrappers, p.pid())
	}
	if failure != nil {
		return report, &candidateCleanupError{report: report, owners: o, cause: failure}
	}
	return report, nil
}

func (o *candidateOwners) finish(root string, result map[string]any, primary error, logs ...*os.File) error {
	report, cleanupErr := o.retire()
	failure := errors.Join(primary, cleanupErr)
	for _, log := range logs {
		failure = errors.Join(failure, log.Close())
	}
	failure = errors.Join(failure, driver.WriteJSON(filepath.Join(root, "cleanup.json"), report))
	if failure != nil {
		return failure
	}
	if result != nil {
		result["resources_retired"] = true
		return driver.WriteJSON(filepath.Join(root, "result.json"), result)
	}
	return nil
}
