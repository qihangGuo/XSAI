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

ifneq ($(filter $(NAME),vector_add_evu_test exp2_evu_test online_softmax_evu_test csr_context_evu_test),)
CFLAGS += -fno-tree-vectorize -fno-tree-slp-vectorize
endif

ifeq ($(NAME),online_softmax_evu_test)
CFLAGS += -ffp-contract=off
endif

include $(AM_HOME)/Makefile.app
