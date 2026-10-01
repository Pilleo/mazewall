SHELL := /bin/bash
GO ?= go
DOCKER ?= docker
# Override explicitly when Docker's configured context is unavailable.
DOCKER_HOST_ARG ?=
GO_IMAGE := $(shell python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["go"])')
GADGET_BUILDER := $(shell python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["gadget_builder"])')
JDK_IMAGE := $(shell python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["jdk"])')

.PHONY: deps unit upstream-test quality-fuzz kernel-cache-test build gadget demo container-build preflight preflight-test graph verify
deps:
	python3 scripts/prepare-gadget.py

unit: deps
	$(GO) test -race -modfile build/experiment.go.mod ./...

upstream-test: deps
	$(GO) test -race -modfile build/experiment.go.mod github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer/otel github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer github.com/inspektor-gadget/inspektor-gadget/pkg/datasource
	$(GO) test -race -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/tracer -run TestTraceReaderHealthSurvivesMetricDrainAndIsTracerOwned -count=1
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

.PHONY: recovery-test-build recovery
.PHONY: corpus-fixture inline-corpus
corpus-fixture:
	mkdir -p build/inline-corpus-classes
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --user $$(id -u):$$(id -g) \
		--mount type=bind,src="$$(pwd)",dst=/work,readonly \
		--mount type=bind,src="$$(pwd)/build/inline-corpus-classes",dst=/out \
		$(JDK_IMAGE) javac -g -d /out /work/fixtures/java/InlineCorpusFixture.java

inline-corpus: build gadget corpus-fixture
	MW_CORPUS_RUN="$$(python3 -c 'import secrets; print(secrets.token_hex(8))')" bash scripts/run-demo.sh

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
