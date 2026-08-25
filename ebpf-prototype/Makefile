# Tier E prototype build (research area, unwired until Gate G2).
# Host side compiles the BPF objects and userspace loaders using the vendored,
# pinned libbpf (vendor/LIBBPF_PINNED_COMMIT). Kernel-phase execution goes
# through scripts/run_collector.sh / scripts/run_wp03.sh (rootful container).

CLANG ?= clang
LLVM_STRIP ?= llvm-strip
# Host-side C compiler for vendored libbpf + tools. gcc trips libbpf's -Werror
# on this toolchain; `make CC=clang` is the supported override.
CC ?= cc

LIBBPF_SRC := $(abspath vendor/libbpf/src)
LIBBPF_DEST := $(abspath build/libbpf)
LIBBPF_A := $(LIBBPF_DEST)/libbpf.a

BUILD := build
BPF_O := $(BUILD)/syscall_collector.bpf.o
COLLECTOR := $(BUILD)/tier_e_collector
CTX_BPF_C := bpf/context_probe.bpf.c
CTX_BPF_O := $(BUILD)/context_probe.bpf.o
MARKER_SO := $(BUILD)/libmazewall_context.so
MARKER_USDT_SO := $(BUILD)/libmazewall_context_usdt.so
WP03_LOADER := $(BUILD)/wp03_loader
WP03_DRIVER := $(BUILD)/wp03_driver

# Resolves <bpf/*.h> from install_headers output. The bpf-target compile needs
# the host multiarch include for asm/types.h (clang drops the default path).
MULTIARCH := $(shell uname -m)-linux-gnu
CFLAGS_COMMON := -O2 -g -Wall -I$(LIBBPF_DEST) -I/usr/include/$(MULTIARCH)
# bpf_tracing.h (BPF_UPROBE/BPF_KPROBE register access) requires the target arch.
ifeq ($(shell uname -m),aarch64)
BPF_ARCH_FLAG := -D__TARGET_ARCH_arm64
else
BPF_ARCH_FLAG := -D__TARGET_ARCH_x86
endif

.PHONY: all clean

all: $(BPF_O) $(COLLECTOR) $(CTX_BPF_O) $(MARKER_SO) $(MARKER_USDT_SO) $(WP03_LOADER) $(WP03_DRIVER)

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

define bpf_compile
	mkdir -p $(BUILD)
	$(CLANG) -O2 -g -target bpf $(CFLAGS_COMMON) -c $(1) -o $(2)
	$(LLVM_STRIP) --strip-debug $(2)
endef

$(BPF_O): $(BPF_C) $(LIBBPF_A)
	@mkdir -p $(BUILD)
	$(CLANG) -O2 -g -target bpf $(BPF_ARCH_FLAG) $(CFLAGS_COMMON) -c $(BPF_C) -o $@
	$(LLVM_STRIP) --strip-debug $@

$(CTX_BPF_O): $(CTX_BPF_C) $(LIBBPF_A)
	@mkdir -p $(BUILD)
	$(CLANG) -O2 -g -target bpf $(BPF_ARCH_FLAG) $(CFLAGS_COMMON) -c $(CTX_BPF_C) -o $@
	$(LLVM_STRIP) --strip-debug $@

$(COLLECTOR): collector/collector.c $(LIBBPF_A)
	mkdir -p $(BUILD)
	$(CC) $(CFLAGS_COMMON) collector/collector.c \
		$(LIBBPF_A) -lelf -lz -lpthread -o $@

$(MARKER_SO): marker/mazewall_context.c
	mkdir -p $(BUILD)
	$(CC) -O2 -g -Wall -fPIC -shared \
		-Wl,-soname,libmazewall_context.so \
		marker/mazewall_context.c -o $@

# G0b variant: exposes the mazewall:context_switch USDT probe in
# .note.stapsdt (requires systemtap-sdt-dev at build time).
$(MARKER_USDT_SO): marker/mazewall_context.c
	mkdir -p $(BUILD)
	$(CC) -O2 -g -Wall -fPIC -shared -DTIER_E_USDT \
		-Wl,-soname,libmazewall_context_usdt.so \
		marker/mazewall_context.c -o $@

$(WP03_LOADER): driver/wp03_loader.c $(LIBBPF_A)
	mkdir -p $(BUILD)
	$(CC) $(CFLAGS_COMMON) driver/wp03_loader.c \
		$(LIBBPF_A) -lelf -lz -lpthread -o $@

$(WP03_DRIVER): driver/wp03_driver.c
	mkdir -p $(BUILD)
	$(CC) -O2 -g -Wall driver/wp03_driver.c -ldl -o $@

clean:
	rm -rf $(BUILD)
