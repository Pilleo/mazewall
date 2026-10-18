package wire

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"strings"
	"unicode/utf8"
)

func unmarshalExtended(data []byte, dst any, fields string) (extensions, error) {
	var raw extensions
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}
	if raw == nil {
		return nil, fmt.Errorf("expected JSON object")
	}
	for _, field := range strings.Split(fields, ",") {
		for key := range raw {
			if key != field && strings.EqualFold(key, field) {
				return nil, fmt.Errorf("noncanonical field %q", key)
			}
		}
		delete(raw, field)
	}
	if err := json.Unmarshal(data, dst); err != nil {
		return nil, err
	}
	if len(raw) == 0 {
		raw = nil
	}
	return raw, nil
}

func marshalExtended(value any, extra extensions) ([]byte, error) {
	b, err := json.Marshal(value)
	if err != nil || len(extra) == 0 {
		return b, err
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(b, &fields); err != nil {
		return nil, err
	}
	keys := make([]string, 0, len(extra))
	for key := range extra {
		if _, exists := fields[key]; exists {
			return nil, fmt.Errorf("extension shadows field %q", key)
		}
		keys = append(keys, key)
	}
	sort.Strings(keys)
	b = b[:len(b)-1]
	for _, key := range keys {
		if !json.Valid(extra[key]) {
			return nil, fmt.Errorf("invalid extension %q", key)
		}
		k, _ := json.Marshal(key)
		if len(b) > 1 {
			b = append(b, ',')
		}
		b = append(b, k...)
		b = append(b, ':')
		b = append(b, extra[key]...)
	}
	return append(b, '}'), nil
}

// Scan tokens before typed decoding: encoding/json alone accepts duplicate keys
// and replaces invalid UTF8. Neither is an admissible identity boundary.
func validateJSON(line []byte) error {
	if !utf8.Valid(line) {
		return fmt.Errorf("%w: invalid UTF8", ErrJSON)
	}
	d := json.NewDecoder(bytes.NewReader(line))
	d.UseNumber()
	token, err := d.Token()
	if err != nil || token != json.Delim('{') {
		return fmt.Errorf("%w: expected object", ErrJSON)
	}
	if err := scanContainer(d, '{', 1); err != nil {
		return err
	}
	if _, err := d.Token(); err != io.EOF {
		return fmt.Errorf("%w: trailing data", ErrJSON)
	}
	return nil
}

func requireFields(raw json.RawMessage, names string) (map[string]json.RawMessage, error) {
	var object map[string]json.RawMessage
	if err := json.Unmarshal(raw, &object); err != nil || object == nil {
		return nil, fmt.Errorf("%w: expected typed object", ErrJSON)
	}
	for _, name := range strings.Split(names, ",") {
		if value, ok := object[name]; !ok || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return nil, fmt.Errorf("%w: missing/null %s", ErrJSON, name)
		}
	}
	return object, nil
}

func requiredRecordFields(line []byte, r Record) error {
	row, err := requireFields(line, "schema_version,kind,session_id")
	if err != nil {
		return err
	}
	if r.Event != nil {
		_, err = requireFields(row["event"], "session_id,event_id,cpu,sequence,host_pid,host_tid,process_start_ticks,cgroup_id,capture_boot_ns,clock_domain,event_kind,correlation_id,dirfd,flags,mode,filename,filename_bytes,register_read,argument_read,path_truncated")
		if err != nil {
			return err
		}
	}
	if r.Lifecycle != nil {
		l, e := requireFields(row["lifecycle"], "pid,clock_domain,boot_ns,runtime_profile,provider")
		if e != nil {
			return e
		}
		if _, e = requireFields(l["provider"], "name,gadget_revision,profiler_revision,patch_sha256"); e != nil {
			return e
		}
	}
	if r.Summary != nil {
		s, e := requireFields(row["summary"], "enrichment,collection_health,terminal_accounting_complete")
		if e != nil {
			return e
		}
		if _, e = requireFields(s["enrichment"], "received,resolved,unavailable,skipped,max_pending,max_pending_payload_bytes,recovery_attempts,recovery_invalidations,recovery_errors"); e != nil {
			return e
		}
		if r.Summary.EventFinalization != nil {
			if _, e = requireFields(s["event_finalization_observation"], "scope,phase,stop_cause,producers_quiesced,buffer_drained,receiver_joined,final_counter_read,background_loss_sampler_started,resources_retired,loss_complete"); e != nil {
				return e
			}
		}
		if r.Summary.SelectedAccounting != nil {
			if _, e = requireFields(s["selected_event_accounting"], "schema_version,scope,session_id,host_pid,process_start_ticks,map_id,possible_cpus,attempted,submitted,reserve_failed,received,terminals,complete"); e != nil {
				return e
			}
		}
	}
	return nil
}

func scanContainer(d *json.Decoder, kind json.Delim, depth int) error {
	if depth > 64 {
		return fmt.Errorf("%w: JSON nesting exceeds 64", ErrLimit)
	}
	keys := map[string]bool{}
	for d.More() {
		if kind == '{' {
			t, err := d.Token()
			if err != nil {
				return fmt.Errorf("%w: %v", ErrJSON, err)
			}
			key, ok := t.(string)
			if !ok || keys[key] {
				return fmt.Errorf("%w: duplicate object key %q", ErrJSON, key)
			}
			keys[key] = true
		}
		t, err := d.Token()
		if err != nil {
			return fmt.Errorf("%w: %v", ErrJSON, err)
		}
		if open, ok := t.(json.Delim); ok {
			if open != '{' && open != '[' {
				return fmt.Errorf("%w: unexpected delimiter", ErrJSON)
			}
			if err := scanContainer(d, open, depth+1); err != nil {
				return err
			}
		}
	}
	close, err := d.Token()
	if err != nil || (kind == '{' && close != json.Delim('}')) || (kind == '[' && close != json.Delim(']')) {
		return fmt.Errorf("%w: unclosed container", ErrJSON)
	}
	return nil
}
