import ctypes
import math
import random
import shutil
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path


def fp32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def positive_e4m3(code):
    exponent, fraction = code >> 3, code & 7
    return fraction / 512 if not exponent else (1 + fraction / 8) * 2 ** (exponent - 7)


def nearest_code(value, levels):
    return min(range(len(levels)), key=lambda code: (abs(value - levels[code]), code & 1))


class Nvfp4ReferenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("cc")
        if not compiler:
            raise unittest.SkipTest("a host C compiler is required")
        cls.temporary = tempfile.TemporaryDirectory(prefix="evu-nvfp4-")
        cls.addClassCleanup(cls.temporary.cleanup)
        directory = Path(cls.temporary.name)
        source = directory / "reference.c"
        source.write_text('''#include "rmsnorm_nvfp4_ref.h"
uint8_t encode4(float value) { return rn_e2m1_encode(value); }
uint8_t encode8(float value) { return rn_e4m3_encode(value); }
float decode8(uint8_t code) { return rn_e4m3_decode(code); }
float bf16(float value) { return rn_bf16_round(value); }
void quantize(const float *values, float alpha, uint8_t *scales, uint8_t *packed) {
  float maxima[128], inverses[128];
  rn_block_amax(values, maxima, 128);
  rn_block_scales(maxima, alpha, scales, inverses, 128);
  rn_pack_fp4(values, inverses, packed, 128);
}
''')
        library = directory / "reference.so"
        include = Path(__file__).resolve().parents[1] / "include"
        subprocess.run([compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared",
                        "-ffp-contract=off", "-I" + str(include), str(source), "-o", str(library)], check=True)
        cls.library = ctypes.CDLL(str(library))
        for name in ("encode4", "encode8"):
            function = getattr(cls.library, name)
            function.argtypes = [ctypes.c_float]
            function.restype = ctypes.c_uint8
        cls.library.decode8.argtypes = [ctypes.c_uint8]
        cls.library.decode8.restype = ctypes.c_float
        cls.library.bf16.argtypes = [ctypes.c_float]
        cls.library.bf16.restype = ctypes.c_float
        cls.library.quantize.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_float,
                                        ctypes.POINTER(ctypes.c_uint8), ctypes.POINTER(ctypes.c_uint8)]
        cls.library.quantize.restype = None

    def test_e4m3_levels_and_all_rounding_boundaries(self):
        levels = [positive_e4m3(code) for code in range(127)]
        for code, value in enumerate(levels):
            self.assertEqual(self.library.decode8(code), value)
            self.assertEqual(self.library.encode8(value), code)
        for lower, upper in zip(levels, levels[1:]):
            midpoint = (lower + upper) / 2
            for value in (midpoint, midpoint - (upper - lower) / 32, midpoint + (upper - lower) / 32):
                self.assertEqual(self.library.encode8(value), nearest_code(value, levels))
        self.assertEqual(self.library.encode8(10000), 126)

    def test_e2m1_ties_sign_zero_and_saturation(self):
        levels = [0, 0.5, 1, 1.5, 2, 3, 4, 6]
        values = levels + [100, 0.0]
        for lower, upper in zip(levels, levels[1:]):
            midpoint = (lower + upper) / 2
            values.extend((midpoint, midpoint - 0.001, midpoint + 0.001))
        for value in values:
            for sign in (1, -1):
                rounded = fp32(float(value) * sign)
                expected = nearest_code(abs(rounded), levels) | (8 if sign < 0 else 0)
                self.assertEqual(self.library.encode4(rounded), expected)

    def test_bf16_nearest_even(self):
        for bits, expected in ((0x3f808000, 0x3f800000), (0x3f818000, 0x3f820000),
                               (0xbf808000, 0xbf800000), (0xbf818000, 0xbf820000),
                               (0x80000000, 0x80000000)):
            value = struct.unpack("<f", struct.pack("<I", bits))[0]
            actual = struct.unpack("<I", struct.pack("<f", self.library.bf16(value)))[0]
            self.assertEqual(actual, expected)

    def test_four_rows_packing_zero_blocks_and_scale_clamps(self):
        generator = random.Random(4096)
        levels8 = [positive_e4m3(code) for code in range(127)]
        levels4 = [0, 0.5, 1, 1.5, 2, 3, 4, 6]
        values = [fp32(generator.uniform(-9, 9)) for _ in range(2048)]
        values[:16] = [-0.0] * 16
        values[16:32] = [fp32(1.0e-9)] * 16
        for alpha in (fp32(1 / 448), fp32(1), fp32(1.0e-5)):
            source = (ctypes.c_float * 2048)(*values)
            scales = (ctypes.c_uint8 * 128)()
            packed = (ctypes.c_uint8 * 1024)()
            self.library.quantize(source, alpha, scales, packed)
            for block in range(128):
                chunk = values[16 * block:16 * block + 16]
                maximum = max(map(abs, chunk))
                target = fp32(fp32(maximum / 6) / alpha)
                scale = max(1, nearest_code(target, levels8)) if maximum else 0
                self.assertEqual(scales[block], scale)
                inverse = fp32(1 / fp32(alpha * levels8[scale])) if maximum else 0
                expected = []
                for value in chunk:
                    scaled = fp32(value * inverse)
                    code = nearest_code(abs(scaled), levels4)
                    if maximum and math.copysign(1, scaled) < 0:
                        code |= 8
                    expected.append(code)
                for pair in range(8):
                    self.assertEqual(packed[8 * block + pair], expected[2 * pair] | (expected[2 * pair + 1] << 4))


if __name__ == "__main__":
    unittest.main()
