#!/usr/bin/env python3
"""Check max reuse with an existing vfexp2 library; no CPU execution or build."""

import argparse
import ctypes
import math
from pathlib import Path
import random
import struct


def bits(value):
    return struct.unpack('<I', struct.pack('<f', value))[0]


def number(value):
    return struct.unpack('<f', struct.pack('<I', value))[0]


def f32(value):
    return number(bits(value))


def neighbor(value, upwards):
    raw = bits(value)
    if value == 0:
        return number(1 if upwards else 0x80000001)
    return number(raw + (1 if upwards == (value > 0) else -1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path(__file__).resolve().parents[4]
                        / 'NEMU/build/riscv64-nemu-interpreter-so')
    args = parser.parse_args()

    class ExpResult(ctypes.Structure):
        _fields_ = [('result', ctypes.c_uint64), ('flags', ctypes.c_uint32)]

    library = ctypes.CDLL(str(args.library))
    compute = library.vfexp2_compute
    compute.argtypes = (ctypes.c_uint64, ctypes.c_int, ctypes.c_bool, ctypes.c_uint32)
    compute.restype = ExpResult

    def exp2(value):
        return number(compute(bits(value), 2, False, 0).result & 0xffffffff)

    def check_chunk(scores, maximum):
        actual = max(exp2(f32(value - maximum)) for value in scores)
        reused = exp2(f32(max(scores) - maximum))
        if bits(actual) != bits(reused):
            raise AssertionError(('chunk maximum differs', actual, reused, maximum, max(scores)))

    # Fixed production inputs: execute every row, tile and 64-key chunk.
    q = [[(i * 3 + 1) % 7 - 3 for i in range(r * 64, (r + 1) * 64)] for r in range(128)]
    k = [[(i * 5 + 2) % 9 - 4 for i in range(r * 64, (r + 1) * 64)] for r in range(256)]
    scale = f32(f32(f32(f32(0.125) * f32(0.2)) * f32(0.125)) * f32(1.4426950408889634))
    chunks = 0
    for query in q:
        maximum = -number(0x7f7fffff)
        for tile in (0, 128):
            scores = [f32(f32(sum(a * b for a, b in zip(query, key))) * scale)
                      for key in k[tile:tile + 128]]
            maximum = max(maximum, max(scores))
            for chunk in (scores[:64], scores[64:]):
                check_chunk(chunk, maximum)
                chunks += 1

    # Check adjacent FP32 inputs around all polynomial boundaries in the
    # relevant nonpositive exponent range, including underflow transitions.
    boundaries = 0
    inversions = []
    for integer in range(-150, 1):
        for segment in range(128):
            center = f32(integer + segment / 128)
            values = [neighbor(center, False), center, neighbor(center, True)]
            results = list(map(exp2, values))
            if results != sorted(results):
                inversions.append((values, results))
            boundaries += 1
    rng = random.Random(20261028)
    for _ in range(2048):
        scores = [f32(rng.uniform(-180, 80)) for _ in range(64)]
        check_chunk(scores, max(max(scores), f32(rng.uniform(-80, 100))))
    print(f'fixed_chunks={chunks} random_chunks=2048 boundary_triplets={boundaries} '
          f'boundary_inversions={len(inversions)}')
    if inversions:
        raise AssertionError(('exp2 is not monotone at sampled boundaries', inversions[:3]))
    print('PASS: reused max matches probability max for checked inputs; this is not an exhaustive FP32 proof.')


if __name__ == '__main__':
    main()
