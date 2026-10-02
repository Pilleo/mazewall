SHELL := /bin/bash
GO ?= go
DOCKER ?= docker
# Override explicitly when Docker's configured context is unavailable.
DOCKER_HOST_ARG ?=
GO_IMAGE := $(shell python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["go"])')
GADGET_BUILDER := $(shell python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["gadget_builder"])')
JDK_IMAGE := $(shell python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["jdk"])')

.PHONY: deps unit upstream-test quality-fuzz kernel-cache-test build gadget demo container-build preflight preflight-test graph verify
.PHONY: benchmark-fixture benchmark-report benchmark loss-gadget event-loss-control
loss-gadget:
	bash scripts/build-loss-gadget.sh

event-loss-control: build benchmark-fixture benchmark-report loss-gadget
	bash scripts/run-benchmark.sh --operations 200 --warmup 200 --repeats 1 --modes full --pause-ns 1000000 --gadget-ref build/loss-gadget-image.ref --expect-missing 1 --expect-missing-sequence 42
benchmark-fixture:
	mkdir -p build/benchmark-classes
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --user $$(id -u):$$(id -g) \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--mount type=bind,src="$$(pwd)/build/benchmark-classes",dst=/out \
		$(JDK_IMAGE) javac -g -d /out /work/fixtures/java/BenchmarkFixture.java

benchmark-report: deps
	$(GO) build -modfile build/experiment.go.mod -trimpath -o build/mw-benchmark-report ./cmd/mw-benchmark-report

benchmark: build gadget benchmark-fixture benchmark-report
	bash scripts/run-benchmark.sh
deps:
	python3 scripts/prepare-gadget.py

unit: deps
	$(GO) test -race -modfile build/experiment.go.mod ./...
	python3 scripts/test-run-benchmark.py

upstream-test: deps
	$(GO) test -race -modfile build/experiment.go.mod github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer/otel github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer github.com/inspektor-gadget/inspektor-gadget/pkg/datasource
	$(GO) test -race -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/tracer -run 'TestTraceReaderHealthSurvivesMetricDrainAndIsTracerOwned|TestHotSpotEpoch|TestDefinition' -count=1
	$(GO) test -race -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/processmanager -run TestHotSpotEpoch -count=1
	$(GO) test -race -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/libpf go.opentelemetry.io/ebpf-profiler/interpreter/hotspot

quality-fuzz: deps
	$(GO) test -c -modfile build/experiment.go.mod -o build/hotspot-quality.test go.opentelemetry.io/ebpf-profiler/interpreter/hotspot
	build/hotspot-quality.test -test.run '^$$' -test.fuzz FuzzHotSpotScopePC -test.fuzztime 10s -test.parallel 4 -test.fuzzcachedir "$$(pwd)/build/fuzz"

kernel-cache-test: deps
	$(GO) test -c -modfile build/experiment.go.mod -tags mw_kernel \
		-o build/otel-kernel.test github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer/otel
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --privileged \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--entrypoint /work/build/otel-kernel.test $(GO_IMAGE) \
		-test.run TestKernelLRUEvictionPreservesTwoCyclePruning -test.v

build: deps
	patch=$$(python3 -c 'import json; print(json.load(open("build/patch-manifest.json"))["patch_sha256"])'); \
	$(GO) build -modfile build/experiment.go.mod -trimpath \
		-ldflags "-X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$patch" \
		-o build/mw-hotspot-demo ./cmd/mw-hotspot-demo

.PHONY: recovery-test-build recovery recovery-epoch
recovery-epoch: recovery-test-build gadget
	MW_COLLECTOR_BINARY=build/mw-hotspot-recovery MW_CAPTURE_MODFILE=build/recovery-test.go.mod MW_JAVA_XINT=true MW_JAVA_SERIAL_GC=true \
		MW_CAPTURE_GATE=TestLostFirstDefinitionRecoversSubsequentOccurrences bash scripts/run-demo.sh --hotspot-identity=safepoint-epoch
.PHONY: corpus-fixture inline-corpus corpus-matrix oracle
.PHONY: unloading-fixture identity-test-build unloading-investigation unloading-epoch identity-kernel-test
unloading-epoch: build gadget unloading-fixture
	MW_UNLOAD_STRESS=true MW_REQUIRE_IDENTITY_DIAGNOSTICS=false MW_CAPTURE_GATE=TestDisposableLoaderEpochAttribution bash scripts/run-demo.sh --hotspot-identity=safepoint-epoch

identity-kernel-test: deps
	$(GO) test -c -modfile build/experiment.go.mod -tags mw_kernel -o build/identity-kernel.test ./internal/identity
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --privileged --pid=host \
		--mount type=bind,src=/sys,dst=/sys,readonly \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--entrypoint /work/build/identity-kernel.test $(GO_IMAGE) -test.run TestExecBarrier -test.v
unloading-fixture:
	mkdir -p build/unload-classes
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --user $$(id -u):$$(id -g) \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--mount type=bind,src="$$(pwd)/build/unload-classes",dst=/out \
		$(JDK_IMAGE) javac -g -d /out /work/fixtures/java/UnloadDriver.java /work/fixtures/java/UnloadG00000.java

identity-test-build:
	python3 scripts/prepare-gadget.py --identity-observations
	patch=$$(python3 -c 'import json;print(json.load(open("build/identity-test-manifest.json"))["patch_sha256"])'); \
	$(GO) build -modfile build/identity-test.go.mod -trimpath \
		-ldflags "-X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$patch" \
		-o build/mw-hotspot-identity ./cmd/mw-hotspot-demo

unloading-investigation: identity-test-build gadget unloading-fixture
	MW_COLLECTOR_BINARY=build/mw-hotspot-identity MW_CAPTURE_MODFILE=build/identity-test.go.mod \
		MW_UNLOAD_STRESS=true bash scripts/run-demo.sh

corpus-fixture:
	mkdir -p build/inline-corpus-classes
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --user $$(id -u):$$(id -g) \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--mount type=bind,src="$$(pwd)/build/inline-corpus-classes",dst=/out \
		$(JDK_IMAGE) javac -g -d /out /work/fixtures/java/InlineCorpusFixture.java

inline-corpus: build gadget corpus-fixture
	MW_CORPUS_RUN="$$(python3 -c 'import secrets; print(secrets.token_hex(8))')" bash scripts/run-demo.sh

corpus-matrix: build gadget corpus-fixture
	bash scripts/run-corpus-matrix.sh

oracle: build gadget corpus-fixture
	bash scripts/run-oracle.sh

recovery-test-build:
	python3 scripts/prepare-gadget.py --drop-first-definition
	patch=$$(python3 -c 'import json; print(json.load(open("build/recovery-test-manifest.json"))["patch_sha256"])'); \
	$(GO) build -modfile build/recovery-test.go.mod -trimpath \
		-ldflags "-X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$patch" \
		-o build/mw-hotspot-recovery ./cmd/mw-hotspot-demo

recovery: recovery-test-build gadget
	MW_COLLECTOR_BINARY=build/mw-hotspot-recovery MW_CAPTURE_MODFILE=build/recovery-test.go.mod MW_JAVA_XINT=true \
		MW_CAPTURE_GATE=TestLostFirstDefinitionRecoversSubsequentOccurrences bash scripts/run-demo.sh

gadget:
	mkdir -p build/gadget
	$(GO) run ./cmd/mw-gadget-build --builder-image $(GADGET_BUILDER) \
		--builder-image-pull always --output "$$(pwd)/build/gadget" \
		--tag mw-hotspot-openat:v1 gadgets/openat-stack
	python3 -c 'import json,pathlib; index=json.loads((pathlib.Path.home()/".ig/oci-store/index.json").read_text()); m=next(m for m in index["manifests"] if m.get("annotations",{}).get("org.opencontainers.image.ref.name", "").endswith("/mw-hotspot-openat:v1")); pathlib.Path("build/gadget-image.ref").write_text("ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"+m["digest"]+"\n")'

demo: build gadget
	bash scripts/run-demo.sh

graph: deps
	mkdir -p build
	$(GO) list -m -json all > build/modules-base.json
	$(GO) list -modfile build/experiment.go.mod -m -json all > build/modules.json
	$(GO) mod verify

container-build: deps
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --user $$(id -u):$$(id -g) \
		-e GOCACHE=/tmp/go-build -e GOPATH=/tmp/go -e HOME=/tmp \
		-e GADGET_PATCH_ID="$$(python3 -c 'import json; print(json.load(open("build/patch-manifest.json"))["patch_sha256"])')" \
		-v "$$(pwd):/work" -w /work $(GO_IMAGE) \
		sh -c 'go build -modfile build/experiment.go.mod -trimpath -ldflags "-X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$GADGET_PATCH_ID" -o build/mw-hotspot-demo ./cmd/mw-hotspot-demo'

preflight: build
	bash scripts/preflight.sh --pid "$(PID)"

preflight-test: build
	bash scripts/test-preflight.sh

verify: unit upstream-test build graph
