package wire

import (
	"fmt"
	"strconv"
	"strings"
)

// ParseStartTicks reads raw field 22, preserving process-birth identity in ticks.
func ParseStartTicks(stat string) (uint64, error) {
	end := strings.LastIndexByte(stat, ')')
	if end < 0 {
		return 0, fmt.Errorf("proc stat lacks closing comm delimiter")
	}
	fields := strings.Fields(stat[end+1:])
	// Field 3 starts this suffix, hence field 22 has index 19.
	if len(fields) <= 19 {
		return 0, fmt.Errorf("proc stat lacks starttime")
	}
	value, err := strconv.ParseUint(fields[19], 10, 64)
	if err != nil || value == 0 {
		return 0, fmt.Errorf("invalid proc stat starttime %q", fields[19])
	}
	return value, nil
}
