package accounting

import "sort"

type Inventory map[string]bool

func Missing(i Inventory) []string {
	var missing []string
	required := map[string]bool{}
	for _, lane := range []string{"selected_events", "definitions", "enrichment", "wire", "retention"} {
		required[lane] = true
		if !i[lane] {
			missing = append(missing, lane)
		}
	}
	for lane := range i {
		if !required[lane] {
			missing = append(missing, "unrecognized:"+lane)
		}
	}
	sort.Strings(missing)
	return missing
}
