import ast
import math
import operator
import random
import re
import shutil
import struct
import subprocess
import unittest
from pathlib import Path


def fp32(value):
    try:
        return struct.unpack('<f', struct.pack('<f', value))[0]
    except OverflowError:
        return math.copysign(math.inf, value)


def bits(value):
    return struct.unpack('<I', struct.pack('<f', value))[0]


def floating(value):
    return struct.unpack('<f', struct.pack('<I', value))[0]


def integer(expression):
    operations = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul,
                  ast.BitAnd: operator.and_}

    def evaluate(node):
        if isinstance(node, ast.Constant) and isinstance(node.value, int):
            return node.value
        if isinstance(node, ast.BinOp) and type(node.op) in operations:
            return operations[type(node.op)](evaluate(node.left), evaluate(node.right))
        raise ValueError(expression)

    return evaluate(ast.parse(expression, mode='eval').body)


def instruction_trace():
    compiler = shutil.which('clang') or shutil.which('cc')
    if not compiler:
        raise unittest.SkipTest('a C preprocessor is required; no binary is built')
    names = ('xv_setvli', 'XV_LOAD_E0', 'XV_BROADCAST', 'XV_SLIDEDOWN', 'XV_SLIDEUP',
             'XV_FABS', 'XV_FREDUCE_MAX', 'XV_GATHER_IMM', 'XV_FMUL', 'XV_FDIV',
             'XV_FMAX', 'XV_BINARY', 'XV_CVT_E4M3_F32', 'XV_CVT_F32_E4M3',
             'XV_CVT_MXFP4_F32', 'XV_STORE')
    source = '\n'.join(f'#define {name}(...) TRACE({name}, __VA_ARGS__)' for name in names)
    source += '\n#include "rmsnorm_nvfp4_batch.h"\nRN_QUANT_INITIALIZE(); RN_QUANT_BATCHES();\n'
    include = Path(__file__).resolve().parents[1] / 'include'
    text = subprocess.run([compiler, '-E', '-P', '-x', 'c', '-I' + str(include), '-'],
                          input=source, text=True, capture_output=True, check=True).stdout
    trace = []
    for match in re.finditer(r'\bTRACE\(', text):
        depth, end = 1, match.end()
        while depth:
            depth += (text[end] == '(') - (text[end] == ')')
            end += 1
        trace.append(tuple(part.strip() for part in text[match.end():end - 1].split(',')))
    return trace


LEVELS8 = [fraction / 512 if exponent == 0 else (1 + fraction / 8) * 2 ** (exponent - 7)
           for exponent in range(16) for fraction in range(8) if exponent != 15 or fraction != 7]
LEVELS4 = [0, 0.5, 1, 1.5, 2, 3, 4, 6]


def encode(value, levels):
    if value >= levels[-1]:
        return len(levels) - 1
    return min(range(len(levels)), key=lambda code: (abs(value - levels[code]), code & 1))


def scalar_quantize(values, alpha):
    scales, packed = bytearray(), bytearray()
    for start in range(0, len(values), 16):
        block = values[start:start + 16]
        maximum = max(map(abs, block))
        scale = encode(fp32(fp32(maximum / 6) / alpha), LEVELS8)
        if maximum != 0:
            scale = max(1, scale)
        scales.append(scale)
        inverse = fp32(1 / fp32(alpha * LEVELS8[scale])) if scale else 0
        codes = []
        for value in block:
            scaled = fp32(value * inverse) if scale else 0.0
            codes.append(encode(abs(scaled), LEVELS4) | ((bits(scaled) >> 31) << 3))
        packed.extend(codes[index] | (codes[index + 1] << 4) for index in range(0, 16, 2))
    return packed, scales


class BatchMachine:
    def __init__(self, values, alpha):
        self.registers = [bytearray([0xa5] * 512) for _ in range(32)]
        for index, value in enumerate(values):
            self.registers[index // 128][4 * (index % 128):4 * (index % 128 + 1)] = struct.pack('<f', value)
        self.constants = {'quant_constants': [bits(6.0), bits(alpha), bits(1.0), bits(1 / 512)],
                          'constants': [0], 'quant_integers': [1, 127]}
        self.output = {'packed': bytearray([0xcc] * 1024), 'scales': bytearray([0xcc] * 128)}
        self.written = {'packed': set(), 'scales': set()}
        self.stores, self.divisions = [], []
        self.vl, self.width = 0, 4

    def words(self, register):
        return struct.unpack('<128I', self.registers[register])

    def put_words(self, register, values):
        for index, value in enumerate(values):
            struct.pack_into('<I', self.registers[register], 4 * index, value & 0xffffffff)

    def execute(self, operation, *arguments):
        if operation == 'xv_setvli':
            self.vl, vtype = map(integer, arguments)
            self.width = 1 << ((vtype >> 3) & 7)
            return
        dest = integer(arguments[0]) if operation != 'XV_BINARY' else integer(arguments[1])
        if operation == 'XV_LOAD_E0':
            address = re.fullmatch(r'&(\w+)\[(.*)\]', arguments[1])
            self.put_words(dest, [self.constants[address[1]][integer(address[2])]])
        elif operation == 'XV_STORE':
            address = re.fullmatch(r'&(\w+)\.data\[(.*)\]', arguments[1])
            name, offset = address[1], integer(address[2])
            length = self.vl * self.width
            assert offset % 64 == 0 and length % 64 == 0
            assert offset + length <= len(self.output[name])
            positions = set(range(offset, offset + length))
            assert not self.written[name] & positions
            self.written[name] |= positions
            self.output[name][offset:offset + length] = self.registers[dest][:length]
            self.stores.append((name, offset, length))
        elif operation in ('XV_SLIDEDOWN', 'XV_SLIDEUP'):
            offset, source, sew = map(integer, arguments[1:])
            assert self.width == 1 << sew
            data = bytes(self.registers[source])
            for index in range(self.vl):
                if operation == 'XV_SLIDEUP' and index < offset:
                    continue
                selected = index + offset if operation == 'XV_SLIDEDOWN' else index - offset
                value = data[selected * self.width:(selected + 1) * self.width]
                self.registers[dest][index * self.width:(index + 1) * self.width] = value.ljust(self.width, b'\0')
        elif operation in ('XV_GATHER_IMM', 'XV_BROADCAST'):
            index, source = (map(integer, arguments[1:3]) if operation == 'XV_GATHER_IMM'
                             else (0, integer(arguments[1])))
            value = bytes(self.registers[source][index * self.width:(index + 1) * self.width])
            self.registers[dest][:self.vl * self.width] = value * self.vl
        elif operation == 'XV_FABS':
            source = integer(arguments[1])
            self.put_words(dest, [word & 0x7fffffff for word in self.words(source)[:self.vl]])
        elif operation == 'XV_FREDUCE_MAX':
            seed, source = map(integer, arguments[1:3])
            maximum = max([floating(self.words(seed)[0])] + list(map(floating, self.words(source)[:self.vl])))
            self.put_words(dest, [bits(maximum)])
        elif operation in ('XV_FDIV', 'XV_FMUL', 'XV_FMAX'):
            source1, source2 = map(integer, arguments[1:3])
            first, second = self.words(source1), self.words(source2)
            results = []
            for index in range(self.vl):
                denominator, numerator = floating(first[index]), floating(second[index])
                if operation == 'XV_FDIV':
                    assert denominator != 0
                    result = numerator / denominator
                elif operation == 'XV_FMUL':
                    result = numerator * denominator
                else:
                    result = max(numerator, denominator)
                results.append(bits(fp32(result)))
            self.put_words(dest, results)
            if operation == 'XV_FDIV':
                self.divisions.append(self.vl)
        elif operation == 'XV_BINARY':
            opcode, dest, source1, source2, sew = map(integer, arguments)
            assert sew == 2
            functions = {0x25: min, 0x02: operator.sub, 0x03: operator.and_}
            self.put_words(dest, [functions[opcode](second, first) for first, second in
                                  zip(self.words(source1)[:self.vl], self.words(source2)[:self.vl])])
        elif operation == 'XV_CVT_E4M3_F32':
            source = integer(arguments[1])
            codes = bytes(encode(floating(word), LEVELS8) for word in self.words(source)[:self.vl])
            self.registers[dest][:self.vl] = codes
        elif operation == 'XV_CVT_F32_E4M3':
            source = integer(arguments[1])
            self.put_words(dest, [bits(LEVELS8[code]) for code in self.registers[source][:self.vl]])
        elif operation == 'XV_CVT_MXFP4_F32':
            factor, source = map(integer, arguments[1:])
            assert self.words(factor)[0] == 127
            codes = [encode(abs(floating(word)), LEVELS4) | ((word >> 31) << 3)
                     for word in self.words(source)[:self.vl]]
            self.registers[dest][:self.vl // 2] = bytes(codes[index] | (codes[index + 1] << 4)
                                                      for index in range(0, self.vl, 2))
        else:
            raise ValueError(operation)


class RmsnormBatchScheduleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.trace = instruction_trace()

    def test_actual_preprocessed_schedule_matches_scalar_reference(self):
        generator = random.Random(512)
        values = [fp32(generator.uniform(-9, 9)) for _ in range(2048)]
        values[:16] = [-0.0] * 16
        values[16:32] = [floating(1)] * 16
        values[32:48] = [fp32(1e-9)] * 16
        values[48:64] = [fp32(-1e10)] * 16
        for block in (15, 16, 31, 32, 63, 64, 127):
            values[16 * block:16 * block + 16] = [0.0] * 16
        for alpha in (fp32(1 / 448), 1.0, fp32(1e-5)):
            with self.subTest(alpha=alpha):
                machine = BatchMachine(values, alpha)
                resident = [bytes(register) for register in machine.registers[:16]]
                for instruction in self.trace:
                    machine.execute(*instruction)
                expected_packed, expected_scales = scalar_quantize(values, alpha)
                self.assertEqual(machine.output['packed'], expected_packed)
                self.assertEqual(machine.output['scales'], expected_scales)
                self.assertEqual(machine.registers[:16], resident)
                self.assertEqual(machine.divisions, [16] * 24)
                self.assertEqual(len(machine.stores), 10)
                self.assertEqual(sum(length for _, _, length in machine.stores), 1152)
                self.assertEqual(len(machine.written['packed']), 1024)
                self.assertEqual(len(machine.written['scales']), 128)

    def test_minimum_scale_reordering_preserves_e4m3_boundaries(self):
        boundaries = [0.0, floating(1)] + LEVELS8
        boundaries += [(lower + upper) / 2 for lower, upper in zip(LEVELS8, LEVELS8[1:])]
        for candidate in boundaries:
            with self.subTest(candidate=candidate):
                self.assertEqual(max(1, encode(candidate, LEVELS8)),
                                 encode(max(candidate, 1 / 512), LEVELS8))

    def test_immediate_gather_indices_are_encodable_register_tokens(self):
        for operation, *arguments in self.trace:
            if operation == 'XV_GATHER_IMM':
                self.assertRegex(arguments[1], r'^(?:[0-9]|1[0-5])$')


if __name__ == '__main__':
    unittest.main()
