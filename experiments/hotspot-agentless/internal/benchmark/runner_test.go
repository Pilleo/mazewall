package benchmark

import "testing"

func TestBenchmarkLossControlRequiresExactMissingToken(t *testing.T) {
	missing := 42
	options := benchmarkOptions{ExpectMissing: 1, ExpectMissingSequence: &missing}
	report := RunReport{Unobserved: 1, MissingSequences: []int{43}, CorrectSegments: 10}
	if err := validateReport(options, "full", report); err == nil {
		t.Fatal("wrong missing token admitted")
	}
	report.MissingSequences = []int{42}
	if err := validateReport(options, "full", report); err != nil {
		t.Fatal(err)
	}
	report.CorrectSegments = 0
	if err := validateReport(options, "full", report); err == nil {
		t.Fatal("full mode without useful enrichment admitted")
	}
}

func TestBenchmarkConfigurationKeepsAcquisitionBounds(t *testing.T) {
	for _, args := range [][]string{{"--operations", "0"}, {"--repeats", "11"}, {"--operations", "100000", "--pause-ns", "10000000"}, {"--post-attach-warmup", "1", "--diversity", "8"}, {"--drain-full", "--modes", "events"}, {"--drain-events", "--modes", "full"}, {"--expect-missing-sequence", "42"}} {
		if _, err := parseBenchmarkOptions(args); err == nil {
			t.Fatalf("invalid bounded benchmark options admitted: %v", args)
		}
	}
	if _, err := parseBenchmarkOptions([]string{"--operations", "200", "--warmup", "200", "--repeats", "1", "--expect-missing", "1", "--expect-missing-sequence", "42"}); err != nil {
		t.Fatal(err)
	}
}
