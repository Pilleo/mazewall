package candidate

import (
	"fmt"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"path/filepath"
)

type observedOracle struct {
	Method string
	TID    uint32
}

func observedRoutes(pairs []wire.Pair, oracle map[string]observedOracle) ([]ObservedRoute, int, error) {
	routes := []ObservedRoute{}
	useful := 0
	for _, pair := range pairs {
		token := filepath.Base(pair.Event.Filename)
		expected, marked := oracle[token]
		if !marked {
			continue
		}
		if pair.Event.Filename != "/fixture/gates/"+token || pair.Event.TID != expected.TID {
			return routes, useful, fmt.Errorf("marked path/exact worker TID mismatch: %s tid=%d want=%d", token, pair.Event.TID, expected.TID)
		}
		route := ObservedRoute{Token: token}
		if pair.Evidence.Attribution == "event_correlated" {
			frames := make([]Frame, len(pair.Evidence.Frames))
			for i, frame := range pair.Evidence.Frames {
				frames[i].FunctionName = frame.FunctionName
				owner, method, ok := ParseJavaMethod(frame.FunctionName)
				if ok && owner == "ObservedWorkload" && (method == "routeA" || method == "routeB") {
					if method != expected.Method {
						return routes, useful, fmt.Errorf("wrong worker stack: token=%s got=%s want=%s", token, method, expected.Method)
					}
				}
			}
			matched, err := routeMatches(frames, []string{"ObservedWorkload.leaf", "ObservedWorkload." + expected.Method})
			if err == nil && matched {
				route.Method = expected.Method
				useful++
			}
		}
		routes = append(routes, route)
	}
	return routes, useful, nil
}

type ObservedRoute struct {
	Token, Method string
}

func VerifyObservedRoutes(got []ObservedRoute, want map[string]string) error {
	if len(want) == 0 || len(got) != len(want) {
		return fmt.Errorf("missing marked tokens: got %d want %d", len(got), len(want))
	}
	seen := make(map[string]bool, len(got))
	for _, route := range got {
		method, exists := want[route.Token]
		if !exists || route.Token == "" || seen[route.Token] || (method != "routeA" && method != "routeB") {
			return fmt.Errorf("duplicate/foreign/invalid token %q", route.Token)
		}
		seen[route.Token] = true
		// Empty method is explicit unavailable, never useful evidence.
		if route.Method != "" && route.Method != method {
			return fmt.Errorf("wrong route for %s: got %s want %s", route.Token, route.Method, method)
		}
	}
	return nil
}

func verifyObservedMarked(got []ObservedRoute, want map[string]string, raw, terminal int) error {
	if raw != 400 || terminal != 400 || len(want) != 400 {
		return fmt.Errorf("marked denominators raw=%d terminal=%d oracle=%d; require 400 each", raw, terminal, len(want))
	}
	if err := VerifyObservedRoutes(got, want); err != nil {
		return err
	}
	useful := 0
	for _, route := range got {
		if route.Method != "" {
			useful++
		}
	}
	if useful < 360 {
		return fmt.Errorf("useful marked stacks %d/400 below 360/400", useful)
	}
	return nil
}
