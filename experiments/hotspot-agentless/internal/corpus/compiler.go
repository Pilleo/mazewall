package corpus

import (
	"encoding/xml"
	"fmt"
	"io"
	"strconv"
	"strings"
)

type InlineProof struct {
	CompileID string `json:"compile_id"`
	Root      string `json:"root"`
	Callee    string `json:"callee"`
	Compiler  string `json:"compiler"`
}

func branchOf(method string) string {
	p := strings.Fields(method)
	if len(p) < 2 || p[0] != "InlineCorpusFixture" {
		return ""
	}
	switch p[1] {
	case "pathA":
		return "A"
	case "pathB":
		return "B"
	}
	return ""
}
func attributes(e xml.StartElement) map[string]string {
	m := map[string]string{}
	for _, a := range e.Attr {
		m[a.Name.Local] = a.Value
	}
	return m
}

// ReadCompiler requires successful inline parse and an active C2 nmethod with
// the same compile ID. Source nesting, flags, and C1 compilation are not proof.
func ReadCompiler(r io.Reader, expectedPID uint32) (map[string]InlineProof, error) {
	return readCompiler(r, expectedPID, true)
}

func readCompiler(r io.Reader, expectedPID uint32, requireInline bool) (map[string]InlineProof, error) {
	d := xml.NewDecoder(io.LimitReader(r, 32*1024*1024))
	nmethods := map[string]InlineProof{}
	invalid := map[string]bool{}
	inlined := map[string]bool{}
	successful := map[string]bool{}
	taskBranches := map[string]string{}
	var taskID, taskBranch, lastCall string
	classes := map[string]string{}
	methods := map[string][2]string{}
	matchedProcess := false
	for {
		tok, err := d.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("compiler XML: %w", err)
		}
		switch e := tok.(type) {
		case xml.StartElement:
			a := attributes(e)
			switch e.Name.Local {
			case "hotspot_log":
				pid, parseErr := strconv.ParseUint(a["process"], 10, 32)
				if parseErr != nil || expectedPID == 0 || uint32(pid) != expectedPID || matchedProcess {
					return nil, fmt.Errorf("compiler log belongs to another or unknown JVM")
				}
				matchedProcess = true
			case "nmethod":
				if b := branchOf(a["method"]); b != "" && a["compiler"] == "c2" {
					nmethods[a["compile_id"]] = InlineProof{CompileID: a["compile_id"], Root: "path" + b, Callee: "sharedLeaf", Compiler: "c2"}
				}
			case "make_not_entrant":
				invalid[a["compile_id"]] = true
			case "task":
				taskID = a["compile_id"]
				taskBranch = branchOf(a["method"])
				taskBranches[taskID] = taskBranch
				lastCall = ""
				classes = map[string]string{}
				methods = map[string][2]string{}
			case "klass":
				if taskID != "" {
					classes[a["id"]] = a["name"]
				}
			case "method":
				if taskID != "" {
					methods[a["id"]] = [2]string{a["holder"], a["name"]}
				}
			case "call":
				lastCall = a["method"]
			case "inline_success":
				m := methods[lastCall]
				if taskBranch != "" && classes[m[0]] == "InlineCorpusFixture" && m[1] == "sharedLeaf" {
					inlined[taskID] = true
				}
				lastCall = ""
			case "inline_fail":
				lastCall = ""
			case "task_done":
				if taskBranch != "" && a["success"] == "1" {
					successful[taskID] = true
				}
			}
		case xml.EndElement:
			if e.Name.Local == "task" {
				taskID = ""
				taskBranch = ""
				lastCall = ""
			}
		}
	}
	proof := map[string]InlineProof{}
	for id, p := range nmethods {
		if inlined[id] && successful[id] && !invalid[id] && p.Root == "path"+taskBranches[id] {
			proof[strings.TrimPrefix(p.Root, "path")] = p
		}
	}
	if !matchedProcess || (requireInline && len(proof) != 2) {
		return nil, fmt.Errorf("successful active C2 sharedLeaf inline proof required for both paths, got %d", len(proof))
	}
	return proof, nil
}
