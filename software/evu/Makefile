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

ifneq ($(filter $(NAME),flash_attention_evu_test flash_attention_evu_breakdown flash_attention_evu_profile_cold flash_attention_evu_correct_warm),)
EVU_FLASH_ROW_BATCH ?= 4
EVU_FLASH_MERGE_PV ?= 1
EVU_FLASH_SCALAR_OPS ?= 1
CFLAGS += -DEVU_FLASH_ROW_BATCH=$(EVU_FLASH_ROW_BATCH) -DEVU_FLASH_MERGE_PV=$(EVU_FLASH_MERGE_PV) -DEVU_FLASH_SCALAR_OPS=$(EVU_FLASH_SCALAR_OPS)
# Each schedule gets separate objects and image paths, including when revisiting
# an already-built variant. Keep the default four-row paired image's old name.
DST_DIR := $(CURDIR)/build/obj/$(NAME)/rows$(EVU_FLASH_ROW_BATCH)-pv$(EVU_FLASH_MERGE_PV)-scalar$(EVU_FLASH_SCALAR_OPS)
ifneq ($(EVU_FLASH_ROW_BATCH)-$(EVU_FLASH_MERGE_PV)-$(EVU_FLASH_SCALAR_OPS),4-1-1)
BINARY := $(CURDIR)/build/$(NAME)-rows$(EVU_FLASH_ROW_BATCH)-pv$(EVU_FLASH_MERGE_PV)-scalar$(EVU_FLASH_SCALAR_OPS)-$(ARCH)
endif
endif

ifneq ($(filter $(NAME),vector_add_evu_test exp2_evu_test online_softmax_evu_test csr_context_evu_test flash_attention_evu_test flash_attention_evu_breakdown flash_attention_evu_profile_cold flash_attention_evu_correct_warm),)
CFLAGS += -fno-tree-vectorize -fno-tree-slp-vectorize
endif

ifneq ($(filter $(NAME),online_softmax_evu_test flash_attention_evu_test flash_attention_evu_breakdown flash_attention_evu_profile_cold flash_attention_evu_correct_warm),)
CFLAGS += -ffp-contract=off
endif

include $(AM_HOME)/Makefile.app
