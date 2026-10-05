SHELL := /bin/bash
GO ?= go
DOCKER ?= docker
DOCKER_HOST_ARG ?=
OUTPUT ?= build/mw-hotspot-collector
SOURCE_DATE_EPOCH ?= 1790985600
GO_IMAGE := $(shell python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["go"])')
GADGET_BUILDER := $(shell python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["gadget_builder"])')

.PHONY: deps unit build container-build gadget bundle verify-bundle install
deps:
	python3 scripts/prepare-gadget.py

unit: deps
	$(GO) test -race -modfile build/experiment.go.mod ./...
	python3 scripts/test-assets.py
	python3 scripts/test-distribution.py

build: deps
	patch=$$(python3 -c 'import json; print(json.load(open("build/patch-manifest.json"))["patch_sha256"])'); \
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 $(GO) build -modfile build/experiment.go.mod -trimpath -buildvcs=false \
		-ldflags "-X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$patch" \
		-o "$(OUTPUT)" ./cmd/mw-hotspot-collector

container-build: deps
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --user $$(id -u):$$(id -g) \
		-e GOCACHE=/tmp/go-build -e GOPATH=/tmp/go -e HOME=/tmp \
		-e CGO_ENABLED=0 -e GOOS=linux -e GOARCH=amd64 \
		-e GADGET_PATCH_ID="$$(python3 -c 'import json; print(json.load(open("build/patch-manifest.json"))["patch_sha256"])')" \
		-v "$$(pwd):/work" -w /work $(GO_IMAGE) \
		sh -c 'go build -modfile build/experiment.go.mod -trimpath -buildvcs=false -ldflags "-X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$GADGET_PATCH_ID" -o build/mw-hotspot-collector ./cmd/mw-hotspot-collector'

gadget:
	mkdir -p build/gadget
	SOURCE_DATE_EPOCH=$(SOURCE_DATE_EPOCH) $(GO) run ./cmd/mw-gadget-build --builder-image $(GADGET_BUILDER) \
		--builder-image-pull always --output "$$(pwd)/build/gadget" \
		--tag mw-hotspot-openat:v1 gadgets/openat-stack
	python3 -c 'import json,pathlib; index=json.loads((pathlib.Path.home()/".ig/oci-store/index.json").read_text()); m=next(m for m in index["manifests"] if m.get("annotations",{}).get("org.opencontainers.image.ref.name", "").endswith("/mw-hotspot-openat:v1")); pathlib.Path("build/gadget-image.ref").write_text("ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"+m["digest"]+"\n")'

bundle: container-build gadget
	python3 scripts/assets.py pack --collector build/mw-hotspot-collector --store "$$HOME/.ig/oci-store" --image "$$(cat build/gadget-image.ref)" --output build/assets

verify-bundle:
	python3 scripts/assets.py verify --bundle build/assets

install:
	python3 scripts/assets.py install --bundle build/assets --output "$(DESTDIR)"
