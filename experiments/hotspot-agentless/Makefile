SHELL := /bin/bash
GO ?= go
DOCKER ?= docker
OUTPUT ?= build/mw-hotspot-demo
# Override explicitly when Docker's configured context is unavailable.
DOCKER_HOST_ARG ?=
GO_IMAGE := $(shell $(GO) run ./cmd/mw-collector-tools image --key go)
GADGET_BUILDER := $(shell $(GO) run ./cmd/mw-collector-tools image --key gadget_builder)
JDK_IMAGE := $(shell $(GO) run ./cmd/mw-collector-tools image --key jdk)

.PHONY: deps unit upstream-test quality-fuzz kernel-cache-test build gadget demo container-build preflight preflight-test graph verify
.PHONY: benchmark-fixture benchmark-report experiment-tools benchmark loss-gadget event-loss-control
.PHONY: event-read-kernel-test
.PHONY: queued-tail-kernel-test
.PHONY: event-finalization-kernel-test
.PHONY: definition-finalization-kernel-test
definition-finalization-kernel-test: deps
	$(GO) test -c -race -modfile build/experiment.go.mod -tags mw_kernel \
		-o build/definition-finalization-kernel.test go.opentelemetry.io/ebpf-profiler/tracer
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --privileged \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--entrypoint /work/build/definition-finalization-kernel.test $(GO_IMAGE) \
		-test.run TestKernelDefinitionPerfDrain -test.v
event-finalization-kernel-test: deps
	$(GO) test -c -race -modfile build/experiment.go.mod -tags mw_kernel \
		-o build/event-finalization-kernel.test github.com/inspektor-gadget/inspektor-gadget/pkg/operators/ebpf
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --privileged --pid=host \
		--mount type=bind,src=/sys,dst=/sys,readonly \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--entrypoint /work/build/event-finalization-kernel.test $(GO_IMAGE) \
		-test.run TestKernelEventFinalization -test.v
queued-tail-kernel-test: deps
	$(GO) test -c -race -modfile build/experiment.go.mod -tags mw_kernel \
		-o build/queued-tail-kernel.test github.com/inspektor-gadget/inspektor-gadget/pkg/operators/ebpf
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --privileged \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--entrypoint /work/build/queued-tail-kernel.test $(GO_IMAGE) \
		-test.run 'TestKernelQueuedTail|TestKernelGadgetClose' -test.v
event-read-kernel-test: deps
	$(GO) test -c -modfile build/experiment.go.mod -tags mw_kernel \
		-o build/event-read-kernel.test github.com/inspektor-gadget/inspektor-gadget/pkg/operators/ebpf
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --privileged \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--entrypoint /work/build/event-read-kernel.test $(GO_IMAGE) \
		-test.run TestKernelEventReadRetainsPayloadAfterRingPressure -test.v
loss-gadget: deps
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

experiment-tools: deps
	$(GO) build -modfile build/experiment.go.mod -trimpath -o build/mw-experiment-tools ./cmd/mw-experiment-tools

benchmark: build gadget benchmark-fixture benchmark-report
	bash scripts/run-benchmark.sh
deps:
	$(GO) run ./cmd/mw-collector-tools prepare

unit: deps
	$(GO) test -race -modfile build/experiment.go.mod ./...

upstream-test: deps
	$(GO) test -race -modfile build/experiment.go.mod github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer/otel github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer github.com/inspektor-gadget/inspektor-gadget/pkg/datasource
	$(GO) test -race -modfile build/experiment.go.mod github.com/inspektor-gadget/inspektor-gadget/pkg/operators/ebpf -count=1
	$(GO) test -race -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/tracer -run 'TestTraceReaderHealthSurvivesMetricDrainAndIsTracerOwned|TestHotSpotEpoch|TestDefinition' -count=1
	$(GO) test -race -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/processmanager -run TestHotSpotEpoch -count=1
	$(GO) test -race -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/libpf go.opentelemetry.io/ebpf-profiler/interpreter/hotspot go.opentelemetry.io/ebpf-profiler/periodiccaller
	$(GO) test -race -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/processmanager/ebpf -run TestAuxiliary -count=1

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
	patch=$$($(GO) run ./cmd/mw-collector-tools patch-id) && \
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 $(GO) build -modfile build/experiment.go.mod -trimpath -buildvcs=false \
		-ldflags "-s -w -X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$patch" \
		-o "$(OUTPUT)" ./cmd/mw-hotspot-demo

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
	$(GO) run ./cmd/mw-collector-tools prepare --identity-observations
	patch=$$($(GO) run ./cmd/mw-collector-tools patch-id --identity-observations) && \
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
	run=$$($(GO) run -modfile build/experiment.go.mod ./cmd/mw-experiment-tools token) && \
	MW_CORPUS_RUN="$$run" bash scripts/run-demo.sh

corpus-matrix: build gadget corpus-fixture
	bash scripts/run-corpus-matrix.sh

oracle: build gadget corpus-fixture
	bash scripts/run-oracle.sh

recovery-test-build:
	$(GO) run ./cmd/mw-collector-tools prepare --drop-first-definition
	patch=$$($(GO) run ./cmd/mw-collector-tools patch-id --drop-first-definition) && \
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
	image=$$($(GO) run ./cmd/mw-collector-tools oci-ref --store "$$HOME/.ig/oci-store" --name mw-hotspot-openat:v1) && printf '%s\n' "$$image" > build/gadget-image.ref

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
		-e CGO_ENABLED=0 -e GOOS=linux -e GOARCH=amd64 \
		-e GADGET_PATCH_ID="$$($(GO) run ./cmd/mw-collector-tools patch-id)" \
		-v "$$(pwd):/work" -w /work $(GO_IMAGE) \
		sh -c 'go build -modfile build/experiment.go.mod -trimpath -buildvcs=false -ldflags "-s -w -X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$GADGET_PATCH_ID" -o build/mw-hotspot-demo ./cmd/mw-hotspot-demo'

preflight: build
	bash scripts/preflight.sh --pid "$(PID)"

preflight-test: build
	bash scripts/test-preflight.sh

verify: unit upstream-test build graph
