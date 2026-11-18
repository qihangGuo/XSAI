"""Source/compile-only checks. No emu, Verilog or guest binary is built."""

import re
import ast
from collections import Counter
import shutil
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SOFTWARE = ROOT / 'software' / 'evu'


def function_body(source, name):
    start = re.search(r'\b' + name + r'\([^;{}]*\)\s*\{', source).end()
    depth, end = 1, start
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end - 1]


class FusedProfileTest(unittest.TestCase):
    def compiler(self):
        compiler = shutil.which('clang')
        if not compiler:
            self.skipTest('Clang is required for RISC-V syntax-only checks')
        am = ROOT.parent / 'nexus-am'
        if not (am / 'am' / 'include' / 'arch' / 'riscv64-xs.h').is_file():
            self.skipTest('the local nexus-am headers are not available')
        return [compiler, '--target=riscv64-unknown-elf', '-march=rv64gc', '-mabi=lp64d',
                '-std=gnu11', '-Wall', '-Werror', '-Wno-main', '-fno-builtin',
                '-fno-fast-math', '-ffp-contract=off', '-fno-vectorize', '-fno-slp-vectorize',
                '-DARCH_H="arch/riscv64-xs.h"', '-I' + str(am / 'am'),
                '-I' + str(am / 'am' / 'include'), '-I' + str(am / 'libs' / 'klib' / 'include'),
                '-I' + str(SOFTWARE / 'include')]

    def test_riscv_syntax_and_option_validation_without_code_generation(self):
        source = SOFTWARE / 'rmsnorm_nvfp4_fused_evu_test.c'
        for flags in ([], ['-DEVU_RMS_QUANT_DIRECT_FIRST=0'],
                      ['-DEVU_RMS_QUANT_DIRECT_MAXIMA=0'], ['-DEVU_RMS_QUANT_SCALAR_FIRST=0'],
                      ['-DEVU_RMS_SAMPLES=3'], ['-DEVU_RMS_LANE_SUM=0'],
                      ['-DEVU_RMS_BF16_INPUT=1', '-DEVU_RMS_QUANT_BANKED=0', '-DEVU_RMS_QUANT_RENAME=0']):
            result = subprocess.run(self.compiler() + ['-fsyntax-only', *flags, str(source)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        for flag in ('-DEVU_RMS_ONLY=1', '-DEVU_RMS_SAMPLES=0', '-DEVU_RMS_QUANT_DIRECT_FIRST=2',
                     '-DEVU_RMS_QUANT_DIRECT_MAXIMA=2', '-DEVU_RMS_QUANT_SCALAR_FIRST=2'):
            result = subprocess.run(self.compiler() + ['-fsyntax-only', flag, str(source)],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)

    def test_profile_reuses_the_exact_existing_kernel_bodies(self):
        for lane_sum in (0, 1):
            sources = []
            for name in ('rmsnorm_nvfp4_evu_test.c', 'rmsnorm_nvfp4_fused_evu_test.c'):
                result = subprocess.run(self.compiler() + ['-E', '-P', f'-DEVU_RMS_LANE_SUM={lane_sum}',
                                        '-DEVU_RMS_QUANT_DIRECT_FIRST=0',
                                        '-DEVU_RMS_QUANT_DIRECT_MAXIMA=0', '-DEVU_RMS_QUANT_SCALAR_FIRST=0',
                                        str(SOFTWARE / name)], capture_output=True, text=True, check=True)
                sources.append(result.stdout)
            for name in ('load_operands', 'sum_of_squares', 'scale_epsilon', 'evu_rsqrt',
                         'normalize', 'rmsnorm', 'quantize', 'execute', 'prepare', 'synchronize'):
                self.assertEqual(function_body(sources[0], name), function_body(sources[1], name), name)
            self.assertIn('static int rmsnorm_nvfp4_test_main(void)', sources[0])
            self.assertNotIn('static int rmsnorm_nvfp4_test_main(void)', sources[1])
            self.assertIn('tail rmsnorm_nvfp4_fused_test_main', sources[1])

    def test_driver_runs_only_fused_row_major_and_checker_does_not_replay_evu(self):
        source = (SOFTWARE / 'rmsnorm_nvfp4_fused_evu_test.c').read_text()
        driver = function_body(source, 'rmsnorm_nvfp4_fused_test_main')
        self.assertEqual(re.findall(r'\bexecute\(([^)]*)\)', driver), ['FUSED_NVFP4, 0'])
        self.assertEqual(re.findall(r'\bprepare\(([^)]*)\)', driver), ['FUSED_NVFP4, 0'])
        self.assertNotIn('wave4', driver)
        self.assertNotIn('check(FUSED_NVFP4', driver)
        self.assertIn('cases=1 format=11', driver)
        self.assertIn('profile=fused_row_major oracle_dlen=512', driver)
        self.assertIn('[RN-SAMPLE]', driver)
        self.assertIn('start_fence = fence_count - 1', driver)
        self.assertIn('end_fence = fence_count - 1', driver)
        for name in ('fused_reference_sum', 'fused_reference', 'check_fused'):
            body = function_body(source, name)
            self.assertNotRegex(body, r'\b(?:rmsnorm|load_operands|store_vectors|synchronize|execute|check)\(')
            self.assertNotRegex(body, r'\bXV_(?:LOAD|STORE|FENCE|FMUL|FREDUCE)')
        checker = function_body(source, 'check_fused')
        self.assertIn('memcmp(scales.data, reference_scales', checker)
        self.assertIn('memcmp(packed.data, reference_packed', checker)

    def quantization_program(self, direct, banked=1, rename=1, maxima=0, scalar=0):
        result = subprocess.run(self.compiler() + ['-E', '-P',
                                f'-DEVU_RMS_QUANT_DIRECT_FIRST={direct}',
                                f'-DEVU_RMS_QUANT_DIRECT_MAXIMA={maxima}',
                                f'-DEVU_RMS_QUANT_SCALAR_FIRST={scalar}',
                                f'-DEVU_RMS_QUANT_BANKED={banked}', f'-DEVU_RMS_QUANT_RENAME={rename}',
                                str(SOFTWARE / 'rmsnorm_nvfp4_fused_evu_test.c')],
                                capture_output=True, text=True, check=True)
        body = function_body(result.stdout, 'quantize')
        events = re.finditer(r'uint64_t xv_offset_ = \(uint64_t\)\((.*?)\);|'
                             r'uintptr_t xv_base_ = \(uintptr_t\)\((.*?)\);|'
                             r'__asm__ volatile \((.*?)\);', body, re.S)
        program, offset, address = [], None, None
        for event in events:
            if event[1] is not None:
                offset = integer_expression(event[1])
            elif event[2] is not None:
                match = re.fullmatch(r'&([\w.]+)\[(.*)\]', event[2])
                self.assertIsNotNone(match)
                address = match[1], integer_expression(match[2])
            else:
                text = ''.join(re.findall(r'"([^"]*)"', event[3].split(':', 1)[0]))
                if text.startswith('.insn i '):
                    program.append(('vl',))
                    continue
                parts = [part.strip() for part in text.split(',')]
                self.assertEqual(len(parts), 6)
                opcode, destination = int(parts[2], 0), int(parts[3][1:])
                source1 = offset if parts[4] == '%0' else int(parts[4][1:])
                program.append((opcode, destination, source1, int(parts[5][1:]), address))
        return program

    def test_direct_first_removes_only_32_zero_slides_and_preserves_output_bytes(self):
        for banked, rename in ((1, 1), (1, 0), (0, 1), (0, 0)):
            with self.subTest(banked=banked, rename=rename):
                old = self.quantization_program(0, banked, rename)
                new = self.quantization_program(1, banked, rename)
                old_counts, new_counts = Counter(x[0] for x in old), Counter(x[0] for x in new)
                self.assertEqual(len(old), 1536)
                self.assertEqual(len(new), 1504)
                self.assertEqual(old_counts - new_counts, {0x3f: 32})
                self.assertFalse(new_counts - old_counts)
                self.assertEqual(sum(x[0] == 0x3f and x[2] == 0 for x in old), 32)
                self.assertEqual(sum(x[0] == 0x3f and x[2] == 0 for x in new), 0)
                symbols = {}
                old_stores = symbolic_quantization(old, symbols)
                new_stores = symbolic_quantization(new, symbols)
                self.assertEqual(old_stores, new_stores)
                self.assertEqual(len(new_stores), 18)
                self.assertEqual(sum(len(data) for _, data in new_stores), 1152)

    def test_direct_maxima_and_scalar_first_remove_eight_moves_each_without_changing_bytes(self):
        for banked, rename in ((1, 1), (1, 0), (0, 1), (0, 0)):
            baseline = self.quantization_program(1, banked, rename)
            symbols = {}
            expected = symbolic_quantization(baseline, symbols)
            for maxima, scalar in ((1, 0), (0, 1), (1, 1)):
                with self.subTest(banked=banked, rename=rename, maxima=maxima, scalar=scalar):
                    candidate = self.quantization_program(1, banked, rename, maxima, scalar)
                    self.assertEqual(len(candidate), 1504 - 8 * maxima - 8 * scalar)
                    self.assertEqual(sum(op[0] == 0x5c for op in candidate), 8 * scalar)
                    self.assertEqual(symbolic_quantization(candidate, symbols), expected)


def integer_expression(text):
    def value(node):
        if isinstance(node, ast.Constant) and isinstance(node.value, int):
            return node.value
        if isinstance(node, ast.BinOp):
            left, right = value(node.left), value(node.right)
            if isinstance(node.op, ast.Add):
                return left + right
            if isinstance(node.op, ast.Sub):
                return left - right
            if isinstance(node.op, ast.Mult):
                return left * right
            if isinstance(node.op, ast.BitAnd):
                return left & right
        raise ValueError('unsupported constant expression: ' + text)
    return value(ast.parse(text, mode='eval').body)


def symbolic_quantization(program, symbols):
    """Track exact byte dependencies, poisoning every unwritten register byte.

    Arithmetic is opaque: identical operation/operand tokens imply identical
    result bits and flags. No floating-point approximation or guest execution.
    """
    def symbol(key):
        if key not in symbols:
            symbols[key] = len(symbols)
        return symbols[key]

    def result(opcode, operands, size):
        operation = symbol(('operation', opcode, tuple(operands)))
        return [symbol(('result', operation, byte)) for byte in range(size)]

    registers = [[symbol(('initial', reg, byte)) for byte in range(512)] for reg in range(32)]
    stores = []
    for instruction in program:
        if instruction[0] == 'vl':
            continue
        opcode, dest, first, second, address = instruction
        right = registers[second][:64]
        left = registers[first][:64] if opcode in (0x02, 0x03, 0x25, 0x32, 0x34, 0x36, 0x44, 0x66, 0x75) else []
        if opcode == 0x5c:
            left = registers[first][:4] * 16
            opcode = 0x32
        if opcode == 0x52:
            registers[dest][:4] = [symbol(('load', address, byte)) for byte in range(4)]
        elif opcode == 0x11:
            stores.append((address, tuple(registers[dest][:64])))
        elif opcode == 0x3f:
            registers[dest][:64] = registers[second][4 * first:4 * first + 64]
        elif opcode == 0x3e:
            registers[dest][4 * first:64] = right[:64 - 4 * first]
        elif opcode == 0x51:
            word = registers[second][4 * first:4 * first + 4]
            registers[dest][:64] = word * 16
        elif opcode == 0x44:
            registers[dest][:4] = result(opcode, left[:4] + right, 4)
        elif opcode == 0x71:
            registers[dest][:16] = result(opcode, right, 16)
        elif opcode == 0x73:
            registers[dest][:64] = result(opcode, right[:16], 64)
        elif opcode == 0x75:
            registers[dest][:8] = result(opcode, left + right, 8)
        elif opcode in (0x02, 0x03, 0x25, 0x32, 0x34, 0x36, 0x66):
            registers[dest][:64] = result(opcode, left + right, 64)
        else:
            raise ValueError('unmodeled opcode: ' + hex(opcode))
    return stores


if __name__ == '__main__':
    unittest.main()
