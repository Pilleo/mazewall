package corpus

import (
	"strings"
	"testing"
)

func compilerFixture() string {
	return strings.Replace(rawCompilerFixture(), "<hotspot_log>", `<hotspot_log process="123">`, 1)
}

func rawCompilerFixture() string {
	return `<hotspot_log><nmethod compile_id="1" compiler="c2" method="InlineCorpusFixture pathA (Ljava/lang/String;)V"/><nmethod compile_id="2" compiler="c2" method="InlineCorpusFixture pathB (Ljava/lang/String;)V"/><task compile_id="1" method="InlineCorpusFixture pathA (Ljava/lang/String;)V"><klass id="10" name="InlineCorpusFixture"/><method id="11" holder="10" name="sharedLeaf"/><call method="11"/><inline_success reason="force inline by CompileCommand"/><task_done success="1"/></task><task compile_id="2" method="InlineCorpusFixture pathB (Ljava/lang/String;)V"><klass id="10" name="InlineCorpusFixture"/><method id="11" holder="10" name="sharedLeaf"/><call method="11"/><inline_success reason="force inline by CompileCommand"/><task_done success="1"/></task></hotspot_log>`
}

func TestCompilerRequiresSuccessfulC2InlineEvidenceForBothPaths(t *testing.T) {
	proof, err := ReadCompiler(strings.NewReader(compilerFixture()), 123)
	if err != nil || len(proof) != 2 || proof["A"].CompileID != "1" || proof["B"].CompileID != "2" {
		t.Fatalf("missing compilation proof: %+v %v", proof, err)
	}
	for _, bad := range []string{
		strings.Replace(compilerFixture(), `<task compile_id="1" method="InlineCorpusFixture pathA`, `<task compile_id="1" method="InlineCorpusFixture pathB`, 1),
		strings.Replace(compilerFixture(), `process="123"`, `process="999"`, 1),
		strings.ReplaceAll(compilerFixture(), "inline_success", "inline_fail"),
		strings.ReplaceAll(compilerFixture(), `compiler="c2"`, `compiler="c1"`),
		strings.ReplaceAll(compilerFixture(), `success="1"`, `success="0"`),
		strings.ReplaceAll(compilerFixture(), `name="sharedLeaf"`, `name="unrelated"`),
		strings.ReplaceAll(compilerFixture(), `name="InlineCorpusFixture"`, `name="OtherClass"`),
		strings.Replace(compilerFixture(), "</hotspot_log>", `<make_not_entrant compile_id="1"/></hotspot_log>`, 1),
		compilerFixture()[:80],
	} {
		if _, err := ReadCompiler(strings.NewReader(bad), 123); err == nil {
			t.Fatal("unproved/deoptimized inline chain accepted")
		}
	}
}
