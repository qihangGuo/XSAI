#define EVU_FLASH_NATIVE
#include "../flash_attention_evu_test.c"

int main(int argc, char **argv) {
  HOST_CHECK(argc == 2);
  host_init(argv[1]);
  init_inputs();
  for (unsigned test = 0; test < 2; ++test) {
    if (test) {
      // Force late maxima and stronger output rescaling across key tiles.
      for (unsigned k = TILE; k < KV; ++k)
        for (unsigned d = 0; d < D; ++d) k_values[k][d] *= 4;
    }
    make_reference();
    // A fresh invocation must not consume output or row state left by a
    // previous run: the first tile/PV pass defines every live element.
    memset(output, 0xff, sizeof(output));
    memset(row_max, 0xff, sizeof(row_max));
    memset(row_sum, 0xff, sizeof(row_sum));
    flash_attention_evu();
    HOST_CHECK(check_output() == 0);
    HOST_CHECK(!(XV_CSR_READ(XV_EVSTATUS) & 2));
    HOST_CHECK(!(XV_CSR_READ(XV_EFFLAGS) & 0x1c));
  }
  printf("EVU Flash Attention native reference: 2 full Q128/KV256/D64 cases, %u EVU/matrix instructions, both FP64 oracles passed (no RTL/performance simulation)\n", host_instructions);
  dlclose(host_library);
  return 0;
}
