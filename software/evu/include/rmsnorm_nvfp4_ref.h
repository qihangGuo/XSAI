#ifndef XSAI_RMSNORM_NVFP4_REF_H
#define XSAI_RMSNORM_NVFP4_REF_H

#include <stdint.h>

static inline uint32_t rn_float_bits(float value) {
  union { float fp; uint32_t bits; } converted = { .fp = value };
  return converted.bits;
}

static inline float rn_bits_float(uint32_t bits) {
  union { float fp; uint32_t bits; } converted = { .bits = bits };
  return converted.fp;
}

static inline float rn_abs(float value) {
  return rn_bits_float(rn_float_bits(value) & UINT32_C(0x7fffffff));
}

static inline float rn_bf16_round(float value) {
  uint32_t bits = rn_float_bits(value);
  bits += UINT32_C(0x7fff) + ((bits >> 16) & 1);
  return rn_bits_float(bits & UINT32_C(0xffff0000));
}

static inline float rn_e4m3_decode(uint8_t code) {
  const unsigned exponent = code >> 3;
  const unsigned fraction = code & 7;
  if (!exponent) return (float)fraction * (1.0f / 512.0f);
  return rn_bits_float(((exponent + 120u) << 23) | (fraction << 20));
}

static inline uint8_t rn_e4m3_encode(float value) {
  if (!(value > 0.0f)) return 0;
  if (value >= 448.0f) return 0x7e;
  if (value < (1.0f / 64.0f)) {
    const float scaled = value * 512.0f;
    unsigned rounded = (unsigned)scaled;
    const float remainder = scaled - (float)rounded;
    rounded += remainder > 0.5f || (remainder == 0.5f && (rounded & 1));
    return (uint8_t)rounded;
  }
  uint32_t bits = rn_float_bits(value);
  bits += UINT32_C(0x7ffff) + ((bits >> 20) & 1);
  return (uint8_t)((bits >> 20) - 960u);
}

static inline uint8_t rn_e2m1_encode(float value) {
  const uint8_t sign = (uint8_t)(rn_float_bits(value) >> 28) & 8;
  const float magnitude = rn_abs(value);
  unsigned code;
  if (magnitude <= 0.25f) code = 0;
  else if (magnitude < 0.75f) code = 1;
  else if (magnitude <= 1.25f) code = 2;
  else if (magnitude < 1.75f) code = 3;
  else if (magnitude <= 2.5f) code = 4;
  else if (magnitude < 3.5f) code = 5;
  else if (magnitude <= 5.0f) code = 6;
  else code = 7;
  return sign | (uint8_t)code;
}

static inline float rn_e2m1_decode(uint8_t code) {
  static const float magnitudes[8] = {0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 6.0f};
  const float magnitude = magnitudes[code & 7];
  return code & 8 ? -magnitude : magnitude;
}

static inline void rn_block_amax(const float *values, float *maxima, unsigned blocks) {
  for (unsigned block = 0; block < blocks; ++block) {
    float maximum = 0.0f;
    for (unsigned element = 0; element < 16; ++element) {
      const float magnitude = rn_abs(values[16 * block + element]);
      if (magnitude > maximum) maximum = magnitude;
    }
    maxima[block] = maximum;
  }
}

static inline void rn_block_scales(const float *maxima, float alpha, uint8_t *scales,
                                   float *inverses, unsigned blocks) {
  for (unsigned block = 0; block < blocks; ++block) {
    if (maxima[block] == 0.0f) {
      scales[block] = 0;
      inverses[block] = 0.0f;
    } else {
      uint8_t scale = rn_e4m3_encode((maxima[block] / 6.0f) / alpha);
      if (!scale) scale = 1;
      scales[block] = scale;
      inverses[block] = 1.0f / (alpha * rn_e4m3_decode(scale));
    }
  }
}

static inline void rn_pack_fp4(const float *values, const float *inverses,
                               uint8_t *packed, unsigned blocks) {
  for (unsigned block = 0; block < blocks; ++block) {
    for (unsigned pair = 0; pair < 8; ++pair) {
      const unsigned element = 16 * block + 2 * pair;
      const uint8_t lower = inverses[block] == 0.0f ? 0 :
        rn_e2m1_encode(values[element] * inverses[block]);
      const uint8_t upper = inverses[block] == 0.0f ? 0 :
        rn_e2m1_encode(values[element + 1] * inverses[block]);
      packed[8 * block + pair] = lower | (uint8_t)(upper << 4);
    }
  }
}

#endif
