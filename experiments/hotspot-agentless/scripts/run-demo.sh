#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
docker_cmd=("${DOCKER:-docker}")
if [[ -n "${COLLECTOR_DOCKER_HOST:-}" ]]; then
    docker_cmd+=(--host "$COLLECTOR_DOCKER_HOST")
fi
go_image=$(python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["go"])')
jdk_image=$(python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["jdk"])')
binary="${MW_COLLECTOR_BINARY:-build/mw-hotspot-demo}"
gate="${MW_CAPTURE_GATE:-TestStructuredCaptureHasOneTerminalPerEventAndTypedJavaFrames}"
modfile="${MW_CAPTURE_MODFILE:-build/experiment.go.mod}"
if [[ ! -f "$modfile" ]]; then
    printf 'Prepared capture module file missing: %s; run make deps first\n' "$modfile" >&2
    exit 1
fi
java_flags=()
if [[ "${MW_JAVA_XINT:-false}" == true ]]; then java_flags+=(-Xint); fi
corpus_run="${MW_CORPUS_RUN:-}"
java_main=(/work/fixtures/java/InlineOpenFixture.java /fixture /fixture/gate)
if [[ -n "$corpus_run" ]]; then
    if [[ ! "$corpus_run" =~ ^[a-f0-9]{16}$ || "${MW_JAVA_XINT:-false}" == true ]]; then
        printf 'Controlled-C2 corpus requires a 16-hex run ID and no -Xint\n' >&2
        exit 1
    fi
    java_flags+=(-XX:-TieredCompilation -Xbatch -XX:CompileThreshold=100
        -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=/fixture/compiler.xml
        -XX:CompileCommand=dontinline,InlineCorpusFixture::pathA
        -XX:CompileCommand=dontinline,InlineCorpusFixture::pathB
        -XX:CompileCommand=inline,InlineCorpusFixture::sharedLeaf)
    java_main=(-cp /work/build/inline-corpus-classes InlineCorpusFixture /fixture /fixture/gate "$corpus_run" 1000)
    gate=TestDeterministicInlineCorpus
fi
image=$(cat build/gadget-image.ref)
store="${MW_GADGET_STORE:-$HOME/.ig/oci-store}"
mkdir -p build
artifact=$(mktemp -d "$root/build/capture.XXXXXX")
target=''
collector=''
cleanup() {
    for owned in "$collector" "$target"; do
        if [[ -n "$owned" ]]; then
            "${docker_cmd[@]}" rm -f "$owned" >/dev/null
        fi
    done
}
trap cleanup EXIT
target=$("${docker_cmd[@]}" run -d --user "$UID:$(id -g)" --pid=host \
    --mount "type=bind,src=$root,dst=/work,readonly" \
    --mount "type=bind,src=$artifact,dst=/fixture" \
    "$jdk_image" java "${java_flags[@]}" "${java_main[@]}")
pid=$("${docker_cmd[@]}" inspect --format '{{.State.Pid}}' "$target")
target_ready=false
for ((attempt=0; attempt<300; attempt++)); do
    if "${docker_cmd[@]}" logs "$target" 2>&1 | rg -q '^ready [0-9]+$'; then
        target_ready=true
        break
    fi
    sleep 0.1
done
if [[ "$target_ready" != true ]]; then
    "${docker_cmd[@]}" logs "$target" >&2
    exit 1
fi
collector=$("${docker_cmd[@]}" create --privileged --pid=host \
    --mount type=bind,src=/sys,dst=/sys,readonly \
    --mount "type=bind,src=$root,dst=/work,readonly" \
    --mount "type=bind,src=$store,dst=/store,readonly" \
    --entrypoint "/work/$binary" "$go_image" \
    --capture --pid "$pid" --image "$image" --store /store --duration 60s "$@")
"${docker_cmd[@]}" start --attach "$collector" > "$artifact/events.jsonl" 2> "$artifact/collector.log" &
attach_pid=$!
attached=false
for ((attempt=0; attempt<300; attempt++)); do
    if rg -q '"collector_attached"' "$artifact/events.jsonl"; then
        attached=true
        break
    fi
    if ! kill -0 "$attach_pid" 2>/dev/null; then break; fi
    sleep 0.1
done
if [[ "$attached" != true ]]; then
    cat "$artifact/collector.log" >&2
    printf 'Collector failed to attach; artifacts: %s\n' "$artifact" >&2
    exit 1
fi
touch "$artifact/gate"
wait "$attach_pid"
exit_code=$("${docker_cmd[@]}" inspect --format '{{.State.ExitCode}}' "$collector")
if [[ "$exit_code" != 0 ]]; then
    cat "$artifact/collector.log" >&2
    printf 'Collector failed with exit %s; artifacts: %s\n' "$exit_code" "$artifact" >&2
    exit 1
fi
if [[ -n "$corpus_run" ]]; then
    target_exit=$(timeout 15s "${docker_cmd[@]}" wait "$target")
    "${docker_cmd[@]}" logs "$target" > "$artifact/target.log" 2>&1
    if [[ "$target_exit" != 0 ]]; then
        cat "$artifact/target.log" >&2
        exit 1
    fi
    python3 - "$artifact" "$corpus_run" "$pid" "$jdk_image" "${java_flags[@]}" <<'PY'
import hashlib, json, os, pathlib, platform, sys
artifact=pathlib.Path(sys.argv[1])
source_files=["fixtures/java/InlineCorpusFixture.java","internal/corpus/compiler.go","internal/corpus/report.go","tests/integration/corpus_test.go","scripts/run-demo.sh"]
data={"run":sys.argv[2],"host_pid":int(sys.argv[3]),"jdk_image":sys.argv[4],"jvm_flags":sys.argv[5:],"kernel_release":platform.release(),"per_path":1000,"workers":1,"warmup_per_path":3000,"event_semantics":"openat_handler_entry","collector_agent_injected":False,"class_sha256":hashlib.sha256(pathlib.Path("build/inline-corpus-classes/InlineCorpusFixture.class").read_bytes()).hexdigest(),"source_sha256":{p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for p in source_files},"gadget_image":pathlib.Path("build/gadget-image.ref").read_text().strip(),"dependencies":json.loads(pathlib.Path("dependencies.lock.json").read_text()),"patch_manifest":json.loads(pathlib.Path("build/patch-manifest.json").read_text())}
data["target_agent_injected"]=data.pop("collector_agent_injected")
data["target_uid"]=os.getuid()
(artifact/"corpus-environment.json").write_text(json.dumps(data,indent=2)+"\n")
PY
fi
printf 'Capture artifacts: %s\n' "$artifact"
MW_CAPTURE_JSONL="$artifact/events.jsonl" MW_CAPTURE_LOG="$artifact/collector.log" MW_TARGET_PID="$pid" go test -modfile "$modfile" -tags integration ./tests/integration -run "$gate" -count=1
