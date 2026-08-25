# Tier E prototype build (research area, unwired until Gate G2).
# Host side compiles the BPF object and userspace loader using the vendored,
# pinned libbpf (vendor/LIBBPF_PINNED_COMMIT). Kernel-phase execution goes
# through scripts/run_collector.sh (rootful container, no host sudo needed).

CLANG ?= clang
LLVM_STRIP ?= llvm-strip
# Host-side C compiler for vendored libbpf + collector. gcc trips libbpf's
# -Werror on this toolchain; `make CC=clang` is the supported override.
CC ?= cc

LIBBPF_SRC := $(abspath vendor/libbpf/src)
LIBBPF_DEST := $(abspath build/libbpf)
LIBBPF_A := $(LIBBPF_DEST)/libbpf.a

BUILD := build
BPF_C := bpf/syscall_collector.bpf.c
BPF_O := $(BUILD)/syscall_collector.bpf.o
COLLECTOR := $(BUILD)/tier_e_collector

# Resolves <bpf/*.h> from install_headers output. The bpf-target compile needs
# the host multiarch include for asm/types.h (clang drops the default path).
MULTIARCH := $(shell uname -m)-linux-gnu
CFLAGS_COMMON := -O2 -g -Wall -I$(LIBBPF_DEST) -I/usr/include/$(MULTIARCH)

.PHONY: all clean

all: $(BPF_O) $(COLLECTOR)

# libbpf 1.5.0 carries two const-qualifier nits that clang promotes via -Werror
# (upstream gcc builds don't see them); EXTRA_CFLAGS is appended last and wins.
LIBBPF_EXTRA_CFLAGS := -Wno-error

$(LIBBPF_A): $(wildcard $(LIBBPF_SRC)/*.c) $(wildcard $(LIBBPF_SRC)/*.h)
	$(MAKE) -C $(LIBBPF_SRC) \
		CC=$(CC) \
		BUILD_STATIC_ONLY=1 \
		OBJDIR=$(LIBBPF_DEST) \
		EXTRA_CFLAGS="$(LIBBPF_EXTRA_CFLAGS)"
	$(MAKE) -C $(LIBBPF_SRC) \
		DESTDIR=$(LIBBPF_DEST) \
		INCLUDEDIR= \
		install_headers
	$(MAKE) -C $(LIBBPF_SRC) \
		DESTDIR=$(LIBBPF_DEST) \
		UAPIDIR= \
		install_uapi_headers

$(BPF_O): $(BPF_C) $(LIBBPF_A)
	mkdir -p $(BUILD)
	$(CLANG) -O2 -g -target bpf $(CFLAGS_COMMON) -c $(BPF_C) -o $@
	$(LLVM_STRIP) --strip-debug $@

$(COLLECTOR): collector/collector.c $(LIBBPF_A)
	mkdir -p $(BUILD)
	$(CC) $(CFLAGS_COMMON) collector/collector.c \
		$(LIBBPF_A) -lelf -lz -lpthread -o $@

clean:
	rm -rf $(BUILD)
