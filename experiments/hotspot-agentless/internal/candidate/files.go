package candidate

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"os"
	"path/filepath"
	"unicode/utf8"
)

type oracleRejection struct{ error }

func readLines[T any](path string) ([]T, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	rows := []T{}
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), maxCaptureLine+2)
	scanner.Split(captureLine)
	for scanner.Scan() {
		line := scanner.Bytes()
		if !utf8.Valid(line) {
			return nil, fmt.Errorf("invalid UTF8 in %s", path)
		}
		var row T
		if err := json.Unmarshal(line, &row); err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
		rows = append(rows, row)
		if len(rows) > maxCaptureRows {
			return nil, fmt.Errorf("capture row limit")
		}
	}
	return rows, scanner.Err()
}

func captureLine(data []byte, atEOF bool) (int, []byte, error) {
	if end := bytes.IndexByte(data, '\n'); end >= 0 {
		if end > maxCaptureLine {
			return 0, nil, fmt.Errorf("capture line limit")
		}
		if len(bytes.TrimSpace(data[:end])) == 0 {
			return 0, nil, fmt.Errorf("empty capture row")
		}
		return end + 1, data[:end], nil
	}
	if len(data) > maxCaptureLine {
		return 0, nil, fmt.Errorf("capture line limit")
	}
	if atEOF && len(data) > 0 {
		return 0, nil, fmt.Errorf("truncated capture row")
	}
	return 0, nil, nil
}

func VerifyFiles(virtual bool, oraclePath, capturePath string) (any, error) {
	rows, err := readLines[Record](capturePath)
	if err != nil {
		return nil, err
	}
	if virtual {
		var target TargetIdentity
		if err := driver.ReadJSON(filepath.Join(filepath.Dir(oraclePath), "identity.json"), &target); err != nil {
			return nil, fmt.Errorf("independent virtual identity: %w", err)
		}
		type oracleRow struct {
			Token string
			VirtualOracle
		}
		entries, err := readLines[oracleRow](oraclePath)
		if err != nil {
			return nil, err
		}
		oracle := map[string]VirtualOracle{}
		for _, entry := range entries {
			if entry.Token == "" || entry.VirtualOracle.LogicalID == 0 {
				return nil, fmt.Errorf("invalid oracle identity")
			}
			if _, duplicate := oracle[entry.Token]; duplicate {
				return nil, fmt.Errorf("duplicate oracle token")
			}
			oracle[entry.Token] = entry.VirtualOracle
		}
		verdict, err := VerifyVirtual(oracle, rows, target)
		if err != nil {
			return nil, &oracleRejection{err}
		}
		return verdict, nil
	}
	var manifest RuntimeManifest
	if err := driver.ReadJSON(oraclePath, &manifest); err != nil {
		return nil, err
	}
	verdict, err := VerifyRuntime(manifest, rows)
	if err != nil {
		return nil, &oracleRejection{err}
	}
	return verdict, nil
}
