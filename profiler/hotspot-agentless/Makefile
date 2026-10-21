SHELL := /bin/bash
GO ?= go
DOCKER ?= docker
DOCKER_HOST_ARG ?=
OUTPUT ?= build/mw-hotspot-collector
SOURCE_DATE_EPOCH ?= 1790985600
GO_IMAGE := $(shell $(GO) run ./cmd/mw-collector-tools image --key go)
GADGET_BUILDER := $(shell $(GO) run ./cmd/mw-collector-tools image --key gadget_builder)

.PHONY: deps unit vuln build container-build gadget bundle verify-bundle install
deps:
	$(GO) run ./cmd/mw-collector-tools prepare

unit: deps
	$(GO) test -race -modfile build/experiment.go.mod ./...

vuln: deps
	GOFLAGS='$(GOFLAGS) "-modfile=$(CURDIR)/build/experiment.go.mod" -mod=readonly' govulncheck ./...

build: deps
	patch=$$($(GO) run ./cmd/mw-collector-tools patch-id) && \
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 $(GO) build -modfile build/experiment.go.mod -trimpath -buildvcs=false \
		-ldflags "-s -w -X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$patch" \
		-o "$(OUTPUT)" ./cmd/mw-hotspot-collector

container-build: deps
	$(DOCKER) $(DOCKER_HOST_ARG) run --rm --user $$(id -u):$$(id -g) \
		-e GOCACHE=/tmp/go-build -e GOPATH=/tmp/go -e HOME=/tmp \
		-e CGO_ENABLED=0 -e GOOS=linux -e GOARCH=amd64 \
		-e GADGET_PATCH_ID="$$($(GO) run ./cmd/mw-collector-tools patch-id)" \
		-v "$$(pwd):/work" -w /work $(GO_IMAGE) \
		sh -c 'go build -modfile build/experiment.go.mod -trimpath -buildvcs=false -ldflags "-s -w -X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$GADGET_PATCH_ID" -o build/mw-hotspot-collector ./cmd/mw-hotspot-collector'

gadget:
	mkdir -p build/gadget
	SOURCE_DATE_EPOCH=$(SOURCE_DATE_EPOCH) $(GO) run ./cmd/mw-gadget-build --builder-image $(GADGET_BUILDER) \
		--builder-image-pull always --output "$$(pwd)/build/gadget" \
		--tag mw-hotspot-openat:v1 gadgets/openat-stack
	image=$$($(GO) run ./cmd/mw-collector-tools oci-ref --store "$$HOME/.ig/oci-store" --name mw-hotspot-openat:v1) && printf '%s\n' "$$image" > build/gadget-image.ref

bundle: container-build gadget
	$(GO) run ./cmd/mw-collector-tools pack --collector build/mw-hotspot-collector --store "$$HOME/.ig/oci-store" --image "$$(cat build/gadget-image.ref)" --output build/assets

verify-bundle:
	$(GO) run ./cmd/mw-collector-tools verify --bundle build/assets

install:
	$(GO) run ./cmd/mw-collector-tools install --bundle build/assets --output "$(DESTDIR)"
