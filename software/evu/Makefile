# Build one bare-metal test using the server's Abstract Machine toolchain.
# Example: make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=online_softmax_evu_test

NAME ?= quanti8mma_evu_example
# Keep SRCS as a makefile variable rather than a command-line variable.  A
# command-line SRCS is inherited by the recursive AM_HOME build and would make
# the AM support library try to compile this application source as its own.
SRCS := $(NAME).c
INC_DIR += $(CURDIR)/include
# Separate each application's objects from older shared build/am-build files.
DST_DIR := $(CURDIR)/build/obj/$(NAME)

ifeq ($(NAME),vector_memory_path_evu_test)
MEM_PATH_CASE ?= 0
MEM_PATH_CACHE ?= -1
MEM_PATH_EVICT_KIB ?= 256
MEM_PATH_EVICT_PASSES ?= 2
MEM_PATH_ITERS ?= 8
MEM_PATH_WARMUP ?= 1
MEM_PATH_RESIDENT_VL ?= 128
MEM_PATH_GROUP ?= 0
MEM_PATH_TRANSPORT_CHECK ?= 0
CFLAGS += -DMEM_PATH_CASE=$(MEM_PATH_CASE) -DMEM_PATH_CACHE=$(MEM_PATH_CACHE)
CFLAGS += -DMEM_PATH_EVICT_KIB=$(MEM_PATH_EVICT_KIB) -DMEM_PATH_EVICT_PASSES=$(MEM_PATH_EVICT_PASSES)
CFLAGS += -DMEM_PATH_ITERS=$(MEM_PATH_ITERS) -DMEM_PATH_WARMUP=$(MEM_PATH_WARMUP) -DMEM_PATH_RESIDENT_VL=$(MEM_PATH_RESIDENT_VL)
CFLAGS += -DMEM_PATH_GROUP=$(MEM_PATH_GROUP)
CFLAGS += -DMEM_PATH_TRANSPORT_CHECK=$(MEM_PATH_TRANSPORT_CHECK)
CFLAGS += -fno-fast-math
INC_DIR += $(AM_HOME)/tests/exp2test/include
MP_VARIANT := case$(MEM_PATH_CASE)-cache$(MEM_PATH_CACHE)-evict$(MEM_PATH_EVICT_KIB)-pass$(MEM_PATH_EVICT_PASSES)-it$(MEM_PATH_ITERS)-w$(MEM_PATH_WARMUP)-vl$(MEM_PATH_RESIDENT_VL)-g$(MEM_PATH_GROUP)-check$(MEM_PATH_TRANSPORT_CHECK)
DST_DIR := $(CURDIR)/build/obj/$(NAME)/$(MP_VARIANT)
ifneq ($(MEM_PATH_CASE)-$(MEM_PATH_CACHE)-$(MEM_PATH_EVICT_KIB)-$(MEM_PATH_EVICT_PASSES)-$(MEM_PATH_ITERS)-$(MEM_PATH_WARMUP)-$(MEM_PATH_RESIDENT_VL)-$(MEM_PATH_GROUP)-$(MEM_PATH_TRANSPORT_CHECK),0--1-256-2-8-1-128-0-0)
BINARY := $(CURDIR)/build/$(NAME)-$(MP_VARIANT)-$(ARCH)
endif
endif

ifeq ($(NAME),online_softmax_evu_test)
EVU_SOFTMAX_ROWS ?= 32
EVU_SOFTMAX_COLS ?= 256
EVU_SOFTMAX_STRIDE ?= $(EVU_SOFTMAX_COLS)
EVU_SOFTMAX_SCALE ?= 1.0f
EVU_SOFTMAX_FIRST ?= -1
EVU_SOFTMAX_WARMUP ?= 1
EVU_SOFTMAX_CHECK ?= 0
EVU_SOFTMAX_STATE_BATCH ?= 0
EVU_SOFTMAX_REDUCE_VL ?= 128
# The wavefront uses existing m1/E0 operations; incompatible experiments keep
# their original schedule unless the caller explicitly requests otherwise.
EVU_SOFTMAX_PIPELINE ?= $(if $(filter 256-0-128,$(EVU_SOFTMAX_COLS)-$(EVU_SOFTMAX_STATE_BATCH)-$(EVU_SOFTMAX_REDUCE_VL)),1,0)
EVU_SOFTMAX_STATE_LOAD_BATCH ?= 0
EVU_SOFTMAX_DRAIN_DIAG ?= 1
CFLAGS += -DEVU_SOFTMAX_ROWS=$(EVU_SOFTMAX_ROWS) -DEVU_SOFTMAX_COLS=$(EVU_SOFTMAX_COLS) -DEVU_SOFTMAX_STRIDE=$(EVU_SOFTMAX_STRIDE)
CFLAGS += -DEVU_SOFTMAX_SCALE=$(EVU_SOFTMAX_SCALE) -DEVU_SOFTMAX_FIRST=$(EVU_SOFTMAX_FIRST) -DEVU_SOFTMAX_WARMUP=$(EVU_SOFTMAX_WARMUP)
CFLAGS += -DEVU_SOFTMAX_CHECK=$(EVU_SOFTMAX_CHECK)
CFLAGS += -DEVU_SOFTMAX_STATE_BATCH=$(EVU_SOFTMAX_STATE_BATCH) -DEVU_SOFTMAX_REDUCE_VL=$(EVU_SOFTMAX_REDUCE_VL) -DEVU_SOFTMAX_DRAIN_DIAG=$(EVU_SOFTMAX_DRAIN_DIAG)
CFLAGS += -DEVU_SOFTMAX_PIPELINE=$(EVU_SOFTMAX_PIPELINE)
CFLAGS += -DEVU_SOFTMAX_STATE_LOAD_BATCH=$(EVU_SOFTMAX_STATE_LOAD_BATCH)
CFLAGS += -fno-fast-math
INC_DIR += $(AM_HOME)/tests/exp2test/include
OS_VARIANT := r$(EVU_SOFTMAX_ROWS)-c$(EVU_SOFTMAX_COLS)-s$(EVU_SOFTMAX_STRIDE)-scale$(EVU_SOFTMAX_SCALE)-first$(EVU_SOFTMAX_FIRST)-w$(EVU_SOFTMAX_WARMUP)-check$(EVU_SOFTMAX_CHECK)-state$(EVU_SOFTMAX_STATE_BATCH)-red$(EVU_SOFTMAX_REDUCE_VL)-diag$(EVU_SOFTMAX_DRAIN_DIAG)-pipe$(EVU_SOFTMAX_PIPELINE)-loads4$(EVU_SOFTMAX_STATE_LOAD_BATCH)
DST_DIR := $(CURDIR)/build/obj/$(NAME)/$(OS_VARIANT)
ifneq ($(EVU_SOFTMAX_ROWS)-$(EVU_SOFTMAX_COLS)-$(EVU_SOFTMAX_STRIDE)-$(EVU_SOFTMAX_SCALE)-$(EVU_SOFTMAX_FIRST)-$(EVU_SOFTMAX_WARMUP)-$(EVU_SOFTMAX_CHECK)-$(EVU_SOFTMAX_STATE_BATCH)-$(EVU_SOFTMAX_REDUCE_VL)-$(EVU_SOFTMAX_DRAIN_DIAG)-$(EVU_SOFTMAX_PIPELINE)-$(EVU_SOFTMAX_STATE_LOAD_BATCH),32-256-256-1.0f--1-1-0-0-128-1-1-0)
BINARY := $(CURDIR)/build/$(NAME)-$(OS_VARIANT)-$(ARCH)
endif
endif

ifneq ($(filter $(NAME),flash_attention_evu_test flash_attention_evu_breakdown flash_attention_evu_profile_cold flash_attention_evu_correct_warm),)
EVU_FLASH_ROW_BATCH ?= 4
EVU_FLASH_MERGE_PV ?= 1
EVU_FLASH_SCALAR_OPS ?= 1
EVU_FLASH_FUSE_ALPHA ?= $(EVU_FLASH_SCALAR_OPS)
FA_DEFAULT_RECIP := 1
ifeq ($(NAME),flash_attention_evu_correct_warm)
FA_DEFAULT_RECIP := 0
EVU_FLASH_EARLY_RECIP ?= 0
endif
EVU_FLASH_EARLY_RECIP ?= $(EVU_FLASH_SCALAR_OPS)
EVU_FLASH_CHUNK_MAX ?= $(EVU_FLASH_SCALAR_OPS)
CFLAGS += -DEVU_FLASH_ROW_BATCH=$(EVU_FLASH_ROW_BATCH) -DEVU_FLASH_MERGE_PV=$(EVU_FLASH_MERGE_PV) -DEVU_FLASH_SCALAR_OPS=$(EVU_FLASH_SCALAR_OPS)
CFLAGS += -DEVU_FLASH_FUSE_ALPHA=$(EVU_FLASH_FUSE_ALPHA) -DEVU_FLASH_EARLY_RECIP=$(EVU_FLASH_EARLY_RECIP) -DEVU_FLASH_CHUNK_MAX=$(EVU_FLASH_CHUNK_MAX)
# Each schedule gets separate objects and image paths, including when revisiting
# an already-built variant. Keep each target's default image's old name.
FA_VARIANT := rows$(EVU_FLASH_ROW_BATCH)-pv$(EVU_FLASH_MERGE_PV)-scalar$(EVU_FLASH_SCALAR_OPS)-alpha$(EVU_FLASH_FUSE_ALPHA)-recip$(EVU_FLASH_EARLY_RECIP)-max$(EVU_FLASH_CHUNK_MAX)
DST_DIR := $(CURDIR)/build/obj/$(NAME)/$(FA_VARIANT)
ifneq ($(EVU_FLASH_ROW_BATCH)-$(EVU_FLASH_MERGE_PV)-$(EVU_FLASH_SCALAR_OPS)-$(EVU_FLASH_FUSE_ALPHA)-$(EVU_FLASH_EARLY_RECIP)-$(EVU_FLASH_CHUNK_MAX),4-1-1-1-$(FA_DEFAULT_RECIP)-1)
BINARY := $(CURDIR)/build/$(NAME)-$(FA_VARIANT)-$(ARCH)
endif
endif

ifneq ($(filter $(NAME),vector_memory_path_evu_test vector_add_evu_test exp2_evu_test online_softmax_evu_test csr_context_evu_test virtual_memory_evu_test flash_attention_evu_test flash_attention_evu_breakdown flash_attention_evu_profile_cold flash_attention_evu_correct_warm),)
CFLAGS += -fno-tree-vectorize -fno-tree-slp-vectorize
endif

ifneq ($(filter $(NAME),vector_memory_path_evu_test online_softmax_evu_test flash_attention_evu_test flash_attention_evu_breakdown flash_attention_evu_profile_cold flash_attention_evu_correct_warm),)
CFLAGS += -ffp-contract=off
endif

include $(AM_HOME)/Makefile.app
