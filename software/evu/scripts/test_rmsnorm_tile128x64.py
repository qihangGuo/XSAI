"""Preprocessing, syntax and Python dataflow checks; no guest binary or RTL."""

from collections import Counter
import math
import random
import re
import subprocess
import unittest

from test_rmsnorm_batch_schedule import BatchMachine, bits, floating, fp32, integer, parse_trace, scalar_quantize
import test_rmsnorm_fused_profile as fused


SOURCE = fused.SOFTWARE / 'rmsnorm_nvfp4_tile128x64_evu_test.c'


def traces(banked=1, source_path=SOURCE, functions=None):
    names = ('xv_setvli', 'XV_LOAD', 'XV_LOAD_E0', 'XV_STORE', 'XV_BROADCAST',
             'XV_SLIDEDOWN', 'XV_SLIDEUP', 'XV_FABS', 'XV_FREDUCE_MAX', 'XV_FREDUCE_SUM',
             'XV_BLOCK_PACK', 'XV_BLOCK_AMAX', 'XV_FSGNJX', 'XV_GATHER_IMM', 'XV_FMUL', 'XV_FMUL_SCALAR', 'XV_FMUL_E0', 'XV_FADD_E0',
             'XV_FDIV_E0', 'XV_FSQRT_E0', 'XV_FSQRT', 'XV_FADD_SCALAR', 'XV_FDIV', 'XV_FMAX', 'XV_BINARY',
             'XV_CVT_E4M3_F32', 'XV_CVT_F32_E4M3', 'XV_CVT_MXFP4_F32')
    source = '#define XSAI_EVU_CSR_H\n'
    source += '\n'.join(f'#define {name}(...) TRACE({name}, __VA_ARGS__)' for name in names)
    source += f'\n#include "{source_path}"\n'
    result = subprocess.run(fused.FusedProfileTest().compiler() +
                            ['-E', '-P', '-x', 'c', f'-DEVU_RMS_QUANT_BANKED={banked}', '-'],
                            input=source, capture_output=True, text=True, check=True)
    if functions is None:
        functions = ('load_tile_constants', 'tile_batch', 'quantize_batch', 'execute_tile')
    return {name: parse_trace(fused.function_body(result.stdout, name)) for name in functions}


def arguments(instruction, batch):
    replacements = {'COLS': 64, 'ROW_BATCH': 4, 'batch': batch}
    return tuple(re.sub(r'\b(COLS|ROW_BATCH|batch)\b', lambda match: str(replacements[match[0]]), item)
                 for item in instruction)


def sum64(values):
    """An independent per-lane sum followed by half folding, all in FP32."""
    lanes = [fp32(fp32(fp32(values[lane] + values[lane + 16]) + values[lane + 32]) +
                  values[lane + 48]) for lane in range(16)]
    for half in (8, 4, 2, 1):
        lanes = [fp32(lanes[lane] + lanes[lane + half]) for lane in range(half)]
    return lanes[0]


class TileMachine(BatchMachine):
    def __init__(self, values, gamma, alpha, epsilon):
        super().__init__([], alpha)
        self.inputs, self.gamma = values, gamma
        self.constants['constants'] = [0, bits(1 / 64), bits(epsilon)]
        self.output = {'packed': bytearray([0xcc] * 4096), 'scales': bytearray([0xcc] * 512)}

    def execute(self, operation, *args):
        if operation == 'XV_LOAD':
            dest = integer(args[0])
            address = re.fullmatch(r'&(inputs|gamma)\[(.*)\]', args[1])
            offset = integer(address[2])
            data = self.inputs if address[1] == 'inputs' else self.gamma
            assert offset + self.vl <= len(data)
            self.put_words(dest, list(map(bits, data[offset:offset + self.vl])))
        elif operation == 'XV_FREDUCE_SUM':
            dest, seed, source, _ = map(integer, args)
            values = list(map(floating, self.words(source)[:self.vl]))
            assert self.vl == 64
            accumulator = [[0.0] * 16 for _ in range(4)]
            accumulator[0][0] = floating(self.words(seed)[0])
            for index, value in enumerate(values):
                slot, lane = index // 16, index % 16
                accumulator[slot][lane] = fp32(accumulator[slot][lane] + value)
            for slot in range(1, 4):
                accumulator[0] = [fp32(left + right) for left, right in
                                  zip(accumulator[0], accumulator[slot])]
            for half in (8, 4, 2, 1):
                for lane in range(half):
                    accumulator[0][lane] = fp32(accumulator[0][lane] + accumulator[0][lane + half])
            self.put_words(dest, [bits(accumulator[0][0])])
        elif operation == 'XV_FSQRT_E0':
            dest, source = map(integer, args)
            self.put_words(dest, [bits(fp32(math.sqrt(floating(self.words(source)[0]))))])
        elif operation in ('XV_FMUL_E0', 'XV_FADD_E0', 'XV_FDIV_E0'):
            dest, first, second = map(integer, args)
            left, right = floating(self.words(first)[0]), floating(self.words(second)[0])
            value = right * left if operation == 'XV_FMUL_E0' else right + left if operation == 'XV_FADD_E0' else right / left
            self.put_words(dest, [bits(fp32(value))])
        else:
            super().execute(operation, *args)


class Tile128x64Test(unittest.TestCase):
    def test_make_options_keep_images_separate_without_building(self):
        makefile = (fused.SOFTWARE / 'Makefile').read_text().replace('include $(AM_HOME)/Makefile.app', '')
        makefile += ('\n.PHONY: inspect\ninspect:\n'
                     '\t$(info source=$(SRCS))\n\t$(info flags=$(CFLAGS))\n'
                     '\t$(info objects=$(DST_DIR))\n\t$(info image=$(BINARY))\n')
        for flags in ([], ['EVU_RMS_SAMPLES=3', 'EVU_RMS_BF16_INPUT=1']):
            result = subprocess.run(['make', '--no-print-directory', '-f', '-', 'inspect',
                                     'NAME=' + SOURCE.stem, 'ARCH=riscv64-xs', *flags],
                                    cwd=fused.SOFTWARE, input=makefile, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            config = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
            self.assertEqual(config['source'], SOURCE.name)
            self.assertIn('-fno-fast-math', config['flags'])
            self.assertIn('-ffp-contract=off', config['flags'])
            self.assertIn('-fno-tree-vectorize', config['flags'])
            self.assertIn('tile128x64', config['objects'])
            self.assertEqual(bool(config['image']), bool(flags))

    def test_riscv_syntax_without_code_generation(self):
        for flags in ([], ['-DEVU_RMS_SAMPLES=3'], ['-DEVU_RMS_BF16_INPUT=1'],
                      ['-DEVU_RMS_ALPHA=1.0f', '-DEVU_RMS_EPS=0.0001f']):
            result = subprocess.run(fused.FusedProfileTest().compiler() + ['-fsyntax-only', *flags, str(SOURCE)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        for flag in ('-DEVU_RMS_SAMPLES=0', '-DEVU_RMS_BF16_INPUT=2'):
            result = subprocess.run(fused.FusedProfileTest().compiler() + ['-fsyntax-only', flag, str(SOURCE)],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)

    def test_whole_tile_matches_fp32_reference_with_poisoned_register_tails(self):
        generator = random.Random(12864)
        values = [fp32(generator.uniform(-3, 3)) for _ in range(8192)]
        # Zero rows/blocks cross both row-batch and scale-store boundaries.
        for row in (0, 3, 4, 15, 16, 63, 64, 127):
            values[row * 64:(row + 1) * 64] = [-0.0, 0.0] * 32
        for row in (1, 17, 37, 81, 126):
            values[row * 64 + 16:row * 64 + 32] = [-0.0] * 16
        gamma = [fp32(0.5 + ((col * 13) % 101) / 128) for col in range(64)]
        for banked, bf16, alpha in ((1, False, fp32(1 / 448)), (0, False, 1.0),
                                    (1, True, fp32(1e-5))):
            with self.subTest(banked=banked, bf16=bf16, alpha=alpha):
                def rounded(value):
                    word = bits(value)
                    return floating((word + 0x7fff + ((word >> 16) & 1)) & 0xffff0000) if bf16 else value

                inputs = list(map(rounded, values))
                weights = list(map(rounded, gamma))
                epsilon = fp32(1e-5)
                normalized = []
                for row in range(128):
                    current = inputs[row * 64:(row + 1) * 64]
                    mean = fp32(sum64([fp32(value * value) for value in current]) * fp32(1 / 64))
                    inverse = fp32(1 / fp32(math.sqrt(fp32(mean + epsilon))))
                    normalized.extend(fp32(fp32(value * inverse) * weight) for value, weight in zip(current, weights))
                expected = scalar_quantize(normalized, alpha)
                trace = traces(banked)
                machine = TileMachine(inputs, weights, alpha, epsilon)
                events = []

                def run(sequence, batch=0):
                    for instruction in sequence:
                        resolved = arguments(instruction, batch)
                        machine.execute(*resolved)
                        events.append(resolved)

                run(trace['load_tile_constants'])
                persistent = {reg: bytes(machine.registers[reg]) for reg in (12, 14, 15)}
                for batch in range(32):
                    run(trace['tile_batch'], batch)
                    for row in range(4):
                        first = (4 * batch + row) * 64
                        self.assertEqual(list(machine.words(row)[:64]),
                                         list(map(bits, normalized[first:first + 64])))
                    run(trace['quantize_batch'], batch)
                    for reg, original in persistent.items():
                        self.assertEqual(bytes(machine.registers[reg]), original, f'persistent v{reg}')
                    if batch % 4 == 3:
                        run(trace['execute_tile'], batch - 3)
                self.assertEqual(machine.output['packed'], expected[0])
                self.assertEqual(machine.output['scales'], expected[1])
                self.assertEqual(len(machine.stores), 72)
                self.assertEqual(sum(length for _, _, length in machine.stores), 4608)
                self.assertEqual(len(machine.written['packed']), 4096)
                self.assertEqual(len(machine.written['scales']), 512)
                counts = Counter(event[0] for event in events)
                self.assertEqual(len(events) + 1, 7003)  # Include the final EVU FENCE.
                self.assertEqual(counts['XV_LOAD'], 129)
                self.assertEqual(counts['XV_FREDUCE_SUM'], 128)
                self.assertEqual(counts['XV_FREDUCE_MAX'], 512)
                self.assertEqual(counts['XV_CVT_MXFP4_F32'], 512)

    def test_timing_covers_one_tile_without_checker_replay_or_intermediate_stores(self):
        source = SOURCE.read_text()
        driver = fused.function_body(source, 'rmsnorm_nvfp4_tile_test_main')
        self.assertEqual(driver.count('execute_tile();'), 1)
        timed = driver[driver.index('const uint64_t start = read_cycle();'):driver.index('const uint64_t end = read_cycle();')]
        self.assertIn('execute_tile();', timed)
        self.assertIn('synchronize();', timed)
        self.assertNotIn('initialize();', timed)
        self.assertNotIn('check_fused();', timed)
        for name in ('initialize', 'reference_sum', 'check_fused', 'check_bytes'):
            self.assertNotRegex(fused.function_body(source, name), r'\bXV_(?:LOAD|STORE|FENCE|FMUL|FREDUCE)')
        self.assertNotIn('normalized.data', source)
        self.assertIn('constants[1] = 1.0f / COLS;', source)
        self.assertIn('rows=128 cols=64', driver)
        self.assertIn('profile=fused_tile128x64_row_major oracle_dlen=512', driver)


if __name__ == '__main__':
    unittest.main()
