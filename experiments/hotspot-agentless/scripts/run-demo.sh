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
if [[ "${MW_JAVA_SERIAL_GC:-false}" == true ]]; then java_flags+=(-XX:+UseSerialGC); fi
corpus_run="${MW_CORPUS_RUN:-}"
workers="${MW_CORPUS_WORKERS:-1}"
corpus_mode="${MW_CORPUS_MODE:-c2}"
java_main=(/work/fixtures/java/InlineOpenFixture.java /fixture /fixture/gate)
unload_stress="${MW_UNLOAD_STRESS:-false}"
if [[ "$unload_stress" == true ]]; then
    if [[ -n "$corpus_run" ]]; then printf 'Unloading and ordinary corpus are separate runs\n' >&2; exit 1; fi
    java_flags=(-Xint -Xmx64m -XX:+UseSerialGC -XX:MetaspaceSize=8m -XX:MaxMetaspaceSize=32m
        -Xlog:class+unload=info:file=/fixture/unloading.log)
    java_main=(-cp /work/build/unload-classes UnloadDriver /fixture /fixture/gate /work/build/unload-classes/UnloadG00000.class)
    gate="${MW_CAPTURE_GATE:-TestDisposableLoaderAttributionInvestigation}"
fi
if [[ -n "$corpus_run" ]]; then
    if [[ ! "$corpus_run" =~ ^[a-f0-9]{16}$ || "${MW_JAVA_XINT:-false}" == true ]]; then
        printf 'Controlled-C2 corpus requires a 16-hex run ID and no -Xint\n' >&2
        exit 1
    fi
    if [[ "$workers" != 1 && "$workers" != 4 && "$workers" != 16 ]]; then
        printf 'Corpus workers must be 1, 4 or 16\n' >&2
        exit 1
    fi
    java_flags+=(-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=/fixture/compiler.xml)
    case "$corpus_mode" in
        c2) java_flags+=(-XX:-TieredCompilation -Xbatch -XX:CompileThreshold=100
            -XX:CompileCommand=dontinline,InlineCorpusFixture::pathA
            -XX:CompileCommand=dontinline,InlineCorpusFixture::pathB
            -XX:CompileCommand=inline,InlineCorpusFixture::sharedLeaf) ;;
        tiered) ;;
        interpreter) java_flags+=(-Xint) ;;
        *) printf 'Unsupported corpus runtime mode: %s\n' "$corpus_mode" >&2; exit 1 ;;
    esac
    java_main=(-cp /work/build/inline-corpus-classes InlineCorpusFixture /fixture /fixture/gate "$corpus_run" 1000 "$workers")
    gate=TestDeterministicInlineCorpus
fi
drain_full=false
for option in "$@"; do if [[ "$option" == --drain-full ]]; then drain_full=true; fi; done
if [[ "$drain_full" == true ]]; then
    if [[ -n "$corpus_run" ]]; then printf 'Full drain hold gate supports strict inline/unloading fixtures only\n' >&2; exit 1; fi
    java_main+=(/fixture/finish)
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
if [[ "$drain_full" == true ]]; then
    completed=false
    for ((attempt=0; attempt<400; attempt++)); do
        if "${docker_cmd[@]}" logs "$target" 2>&1 | rg -q '^completed '; then completed=true; break; fi
        if ! kill -0 "$attach_pid" 2>/dev/null; then break; fi
        sleep 0.1
    done
    if [[ "$completed" != true ]]; then printf 'Strict fixture did not complete before drain; artifacts: %s\n' "$artifact" >&2; exit 1; fi
    "${docker_cmd[@]}" kill --signal SIGINT "$collector" >/dev/null
fi
wait "$attach_pid"
exit_code=$("${docker_cmd[@]}" inspect --format '{{.State.ExitCode}}' "$collector")
if [[ "$exit_code" != 0 ]]; then
    cat "$artifact/collector.log" >&2
    printf 'Collector failed with exit %s; artifacts: %s\n' "$exit_code" "$artifact" >&2
    exit 1
fi
if [[ "$drain_full" == true ]]; then touch "$artifact/finish"; fi
if [[ -n "$corpus_run" || "$unload_stress" == true ]]; then
    target_exit=$(timeout 15s "${docker_cmd[@]}" wait "$target")
    "${docker_cmd[@]}" logs "$target" > "$artifact/target.log" 2>&1
    if [[ "$target_exit" != 0 ]]; then
        cat "$artifact/target.log" >&2
        exit 1
    fi
fi
if [[ -n "$corpus_run" ]]; then
    python3 - "$artifact" "$corpus_run" "$pid" "$jdk_image" "$workers" "${java_flags[@]}" <<'PY'
import hashlib, json, os, pathlib, platform, sys
artifact=pathlib.Path(sys.argv[1])
source_files=["fixtures/java/InlineCorpusFixture.java","internal/corpus/compiler.go","internal/corpus/report.go","tests/integration/corpus_test.go","scripts/run-demo.sh"]
data={"run":sys.argv[2],"host_pid":int(sys.argv[3]),"jdk_image":sys.argv[4],"jvm_flags":sys.argv[5:],"kernel_release":platform.release(),"per_path":1000,"workers":1,"warmup_per_path":3000,"event_semantics":"openat_handler_entry","collector_agent_injected":False,"class_sha256":hashlib.sha256(pathlib.Path("build/inline-corpus-classes/InlineCorpusFixture.class").read_bytes()).hexdigest(),"source_sha256":{p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for p in source_files},"gadget_image":pathlib.Path("build/gadget-image.ref").read_text().strip(),"dependencies":json.loads(pathlib.Path("dependencies.lock.json").read_text()),"patch_manifest":json.loads(pathlib.Path("build/patch-manifest.json").read_text())}
data["target_agent_injected"]=data.pop("collector_agent_injected")
data["workers"]=int(sys.argv[5])
data["runtime_mode"]=os.environ.get("MW_CORPUS_MODE","c2")
data["jvm_flags"]=sys.argv[6:]
data["target_uid"]=os.getuid()
(artifact/"corpus-environment.json").write_text(json.dumps(data,indent=2)+"\n")
PY
fi
if [[ "$unload_stress" == true ]]; then
    python3 - "$artifact" "$pid" "$jdk_image" "${java_flags[@]}" <<'PY'
import hashlib, json, pathlib, platform, sys
a=pathlib.Path(sys.argv[1])
files=["fixtures/java/UnloadDriver.java","fixtures/java/UnloadG00000.java","internal/unloading/report.go","tests/integration/unloading_test.go","scripts/run-demo.sh"]
data={"host_pid":int(sys.argv[2]),"jdk_image":sys.argv[3],"jvm_flags":sys.argv[4:],"kernel_release":platform.release(),"target_agent_injected":False,"gadget_image":pathlib.Path("build/gadget-image.ref").read_text().strip(),"patch_manifest":json.loads(pathlib.Path("build/patch-manifest.json").read_text()),"source_sha256":{p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for p in files},"template_class_sha256":hashlib.sha256(pathlib.Path("build/unload-classes/UnloadG00000.class").read_bytes()).hexdigest()}
(a/"unloading-environment.json").write_text(json.dumps(data,indent=2)+"\n")
PY
fi
printf 'Capture artifacts: %s\n' "$artifact"
MW_CORPUS_MODE="$corpus_mode" MW_CORPUS_WORKERS="$workers" MW_CAPTURE_JSONL="$artifact/events.jsonl" MW_CAPTURE_LOG="$artifact/collector.log" MW_TARGET_PID="$pid" go test -modfile "$modfile" -tags integration ./tests/integration -run "$gate" -count=1
