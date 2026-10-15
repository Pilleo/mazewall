package candidate

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"os"
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
	scanner.Buffer(make([]byte, 4096), 4<<20)
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(bytes.TrimSpace(line)) == 0 {
			continue
		}
		if !utf8.Valid(line) {
			return nil, fmt.Errorf("invalid UTF8 in %s", path)
		}
		var row T
		if err := json.Unmarshal(line, &row); err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
		rows = append(rows, row)
	}
	return rows, scanner.Err()
}

func VerifyFiles(virtual bool, oraclePath, capturePath string) (any, error) {
	rows, err := readLines[Record](capturePath)
	if err != nil {
		return nil, err
	}
	if virtual {
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
			if _, duplicate := oracle[entry.Token]; duplicate {
				return nil, fmt.Errorf("duplicate oracle token")
			}
			oracle[entry.Token] = entry.VirtualOracle
		}
		verdict, err := VerifyVirtual(oracle, rows)
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
