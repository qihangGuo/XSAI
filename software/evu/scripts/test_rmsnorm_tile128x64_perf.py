"""Cycles-only guest checks using preprocessing/syntax only, never codegen."""

from collections import Counter
import ast
import math
import random
import re
import struct
import subprocess
import unittest

import test_rmsnorm_fused_profile as fused
import test_rmsnorm_tile128x64 as checked
from test_rmsnorm_batch_schedule import LEVELS4, bits, encode, floating, fp32, integer, scalar_quantize


SOURCE = fused.SOFTWARE / 'rmsnorm_nvfp4_tile128x64_perf_evu_test.c'
CHECKED = fused.SOFTWARE / 'rmsnorm_nvfp4_tile128x64_evu_test.c'


class PerfTileMachine(checked.TileMachine):
    def execute(self, operation, *args):
        if operation == 'XV_FADD_SCALAR':
            dest, first, second = map(integer, args)
            scalar = floating(self.words(first)[0])
            self.put_words(dest, [bits(fp32(floating(word) + scalar))
                                  for word in self.words(second)[:self.vl]])
        elif operation == 'XV_FSQRT':
            dest, source, _ = map(integer, args)
            self.put_words(dest, [bits(fp32(math.sqrt(floating(word))))
                                  for word in self.words(source)[:self.vl]])
        elif operation == 'XV_BLOCK_AMAX':
            dest, quarter, source = map(integer, args)
            assert self.vl == 64
            row = self.words(source)[:64]
            maxima = [max(w & 0x7fffffff for w in row[i:i + 16]) for i in range(0, 64, 16)]
            struct.pack_into('<4I', self.registers[dest], 16 * quarter, *maxima)
        elif operation == 'XV_FSGNJX':
            dest, mask, magnitude, _ = map(integer, args)
            self.put_words(dest, [v ^ (m & 0x80000000) for v, m in
                                  zip(self.words(magnitude)[:self.vl], self.words(mask))])
        elif operation == 'XV_BLOCK_PACK':
            dest, source, desc, group, index = map(integer, args)
            assert self.vl == 16
            control = self.words(desc)[index]
            inverse = floating(control & 0x7fffffff)
            codes = []
            for word in self.words(source)[16 * group:16 * group + 16]:
                value = fp32(floating(word) * inverse) if control >> 31 else 0.0
                codes.append(encode(abs(value), LEVELS4) | ((bits(value) >> 31) << 3))
            slot = (index & 7) * 8
            self.registers[dest][slot:slot + 8] = bytes(a | (b << 4) for a, b in zip(codes[::2], codes[1::2]))
        else:
            super().execute(operation, *args)


class Tile128x64PerfTest(unittest.TestCase):
    def test_preprocessed_insn_register_fields_and_inverse_indices(self):
        result = subprocess.run(fused.FusedProfileTest().compiler() + ['-E', '-P', str(SOURCE)],
                                capture_output=True, text=True, check=True)

        def instructions(source):
            string = r'"(?:\\.|[^"\\])*"'
            templates = re.findall(r'__asm__\s+volatile\s*\(\s*((?:' + string + r'\s*)+)', source)
            for template in templates:
                assembly = ''.join(ast.literal_eval(literal) for literal in re.findall(string, template))
                if assembly.startswith('.insn r '):
                    yield [field.strip() for field in assembly[len('.insn r '):].split(',')]

        # -fsyntax-only does not invoke the assembler; check actual stringified operands.
        encoded = list(instructions(result.stdout))
        self.assertGreater(len(encoded), 100)
        for fields in encoded:
            self.assertEqual(len(fields), 6, fields)
            for operand in fields[3:]:
                self.assertRegex(operand, r'^(?:x(?:[0-9]|[12][0-9]|3[01])|%[0-9]+)$', fields)
        normalized = fused.function_body(result.stdout, 'perf_normalize_rows')
        quant = fused.function_body(result.stdout, 'perf_quantize_rows')
        opcodes = Counter(int(f[2], 0) for f in instructions(normalized + quant))
        self.assertEqual((opcodes[0x76], opcodes[0x77], opcodes[0x44]), (16, 64, 0))
        gathers = [fields[3:] for fields in instructions(normalized) if fields[2] == '0x51']
        destinations = (16, 18, 19, 23, 22, 21, 30, 24)
        self.assertEqual(gathers, [[f'x{destinations[row % 8]}', f'x{row}', 'x20']
                                   for row in range(1, 16)])

    def test_riscv_syntax_without_code_generation(self):
        for flags in ([], ['-DEVU_RMS_SAMPLES=3'], ['-DEVU_RMS_BF16_INPUT=1'],
                      ['-DEVU_RMS_ALPHA=1.0f', '-DEVU_RMS_EPS=0.0001f']):
            result = subprocess.run(fused.FusedProfileTest().compiler() + ['-fsyntax-only', *flags, str(SOURCE)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        for flag in ('-DEVU_RMS_SAMPLES=0', '-DEVU_RMS_BF16_INPUT=2', '-DEVU_RMS_QUANT_BANKED=0'):
            result = subprocess.run(fused.FusedProfileTest().compiler() + ['-fsyntax-only', flag, str(SOURCE)],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)

    def test_shared_preparation_but_no_reference_arrays_or_checked_driver(self):
        texts = []
        for source in (CHECKED, SOURCE):
            result = subprocess.run(fused.FusedProfileTest().compiler() + ['-E', '-P', str(source)],
                                    capture_output=True, text=True, check=True)
            texts.append(result.stdout)
        for name in ('prepare', 'synchronize'):
            self.assertEqual(fused.function_body(texts[0], name), fused.function_body(texts[1], name), name)
        for name in ('reference_rsqrt', 'reference_sum', 'reference_y', 'reference_maxima',
                     'reference_inverses', 'reference_scales', 'reference_packed',
                     'check_fused', 'read_instret', 'rmsnorm_nvfp4_tile_test_main'):
            self.assertNotIn(name, texts[1])
        self.assertIn('tail rmsnorm_nvfp4_tile_perf_main', texts[1])
        self.assertIn('static int rmsnorm_nvfp4_tile_test_main(void)', texts[0])

    def test_prints_only_cycles_after_completed_compute_and_status_checks(self):
        source = SOURCE.read_text()
        driver = fused.function_body(source, 'rmsnorm_nvfp4_tile_perf_main')
        self.assertEqual(driver.count('execute_perf_tile();'), 1)
        self.assertEqual(source.count('printf('), 1)
        self.assertIn('[RN-PERF] rows=128 cols=64', driver)
        self.assertIn('schedule=pack_interleaved_amax16_early4_load2_sum2_collect8_norm8_vl81_earlyscale2_gamma16_load89early ', driver)
        self.assertIn('XV_CAP_BLOCK_QUANT', driver)
        self.assertIn('cycles=%lu', driver)
        self.assertIn('flags=0x%x', driver)
        self.assertNotIn('instructions=', driver)
        self.assertNotIn('PASS', driver)
        timed = driver[driver.index('const uint64_t start = read_cycle();'):driver.index('const uint64_t end = read_cycle();')]
        self.assertIn('execute_perf_tile();', timed)
        self.assertIn('synchronize();', timed)
        self.assertNotIn('initialize_inputs();', timed)
        self.assertNotIn('printf(', timed)
        self.assertGreater(driver.index('printf('), driver.index('XV_CSR_READ(XV_EVSTATUS)'))
        initialize = fused.function_body(source, 'initialize_inputs')
        self.assertNotIn('reference', initialize)
        self.assertIn('float input_values[509]', initialize)
        self.assertEqual(initialize.count('/ 97.0f'), 1)
        self.assertIn('input_values[(row * 113u + col * 37u) % 509u]', initialize)

    def test_vector_rsqrt_and_overlapped_quantization_preserve_every_output_byte(self):
        functions = ('execute_perf_tile', 'perf_reload_gamma', 'perf_sum_rows',
                     'perf_normalize_rows', 'perf_quantize_rows')
        trace = checked.traces(source_path=SOURCE, functions=functions)
        self.assertEqual(trace['execute_perf_tile'][0], ('xv_setvli', '16', '0x10'))
        set_full_vl = ('xv_setvli', 'COLS', '0x10')
        load_gamma = ('XV_LOAD', '27', '&gamma[0]', '2')
        self.assertEqual(trace['execute_perf_tile'][-2:], [set_full_vl, load_gamma])
        self.assertEqual(trace['perf_reload_gamma'], [load_gamma, set_full_vl])
        loop = fused.function_body(SOURCE.read_text(), 'execute_perf_tile')
        self.assertRegex(loop, r'perf_quantize_rows\(tile\);\s*if \(tile \+ 1 == ROWS / 16\) break;\s*perf_reload_gamma\(\);')
        self.assertEqual(trace['perf_sum_rows'][0][0], 'XV_LOAD')
        row_ops = trace['perf_sum_rows']
        loads = [op for op in row_ops if op[0] == 'XV_LOAD']
        self.assertEqual([integer(op[1]) for op in loads], list(range(16)))
        collect = row_ops.index(('xv_setvli', '16', '0x10'))
        self.assertEqual(row_ops[collect - 3][0], 'XV_FREDUCE_SUM')
        self.assertEqual(row_ops[collect - 2:collect], loads[8:10])
        reduced, switches = 0, []
        for op in trace['perf_sum_rows']:
            if op[0] == 'XV_FREDUCE_SUM':
                reduced += 1
            elif op[0] == 'xv_setvli':
                switches.append((reduced, op[1]))
        self.assertEqual(switches, [(8, '16'), (8, 'COLS'), (16, '16')])
        normalize = trace['perf_normalize_rows']
        self.assertEqual([op[1] for op in normalize if op[0] == 'xv_setvli'],
                         ['COLS', '16', 'COLS'])
        apply_four = ['XV_FMUL_SCALAR'] * 4 + ['XV_FMUL'] * 4
        self.assertEqual([op[0] for op in normalize],
                         ['XV_GATHER_IMM'] * 7 + ['xv_setvli'] + apply_four * 2 +
                         ['xv_setvli'] + ['XV_GATHER_IMM'] * 8 + ['xv_setvli'] +
                         apply_four + ['XV_BLOCK_AMAX'] * 4 + apply_four)
        sums = [(op[0], integer(op[3])) for op in trace['perf_sum_rows']
                if op[0] in ('XV_FMUL', 'XV_FREDUCE_SUM')]
        self.assertEqual(sums, [
            (op, src) for row in range(0, 16, 4)
            for op, src in (('XV_FMUL', row), ('XV_FMUL', row + 1),
                            ('XV_FREDUCE_SUM', 16), ('XV_FREDUCE_SUM', 18),
                            ('XV_FMUL', row + 2), ('XV_FMUL', row + 3),
                            ('XV_FREDUCE_SUM', 19), ('XV_FREDUCE_SUM', 20))])
        quant = trace['perf_quantize_rows']
        self.assertEqual([tuple(map(integer, op[1:])) for op in normalize + quant if op[0] == 'XV_BLOCK_AMAX'],
                         [(dest, row % 4, row) for row, dest in enumerate([23]*4 + [19]*4 + [22]*4 + [23]*4)])
        self.assertEqual([op[1] for op in quant if op[0] == 'xv_setvli'], ['16', 'COLS', '16'])
        maxima, packs, at_store = 4, 0, []
        for op in quant:
            maxima += op[0] == 'XV_BLOCK_AMAX'
            packs += op[0] == 'XV_BLOCK_PACK'
            if op[0] == 'XV_STORE' and '&packed.data' in op[2]:
                at_store.append((maxima, packs))
        self.assertEqual(at_store, [(12, 16)]*2 + [(16, 32)]*2 + [(16, 48)]*2 + [(16, 64)]*2)
        self.assertEqual(quant[-1][:2], ('XV_STORE', '27'))
        generator = random.Random(1286402)
        random_values = [fp32(generator.uniform(-3, 3)) for _ in range(8192)]
        for row in (0, 3, 4, 15, 16, 63, 64, 127):
            random_values[row * 64:(row + 1) * 64] = [-0.0, 0.0] * 32
        for row in (1, 17, 37, 81, 126):
            random_values[row * 64 + 16:row * 64 + 32] = [-0.0] * 16
        generated_values = [fp32(((row * 113 + col * 37) % 509 - 254) / 97)
                            for row in range(128) for col in range(64)]
        for row in range(128):
            for col in range(64):
                if row == 0 or (row % 17 == 0 and col < 16):
                    generated_values[row * 64 + col] = -0.0 if col & 1 else 0.0
        cases = ((random_values, False, fp32(1 / 448)), (random_values, True, fp32(1e-5)),
                 (generated_values, False, fp32(1 / 448)), (random_values, False, 1.0),
                 ([-0.0, 0.0] * 4096, False, fp32(1 / 448)))
        for values, bf16, alpha in cases:
            with self.subTest(bf16=bf16, alpha=alpha, generated=values is generated_values):
                def rounded(value):
                    word = bits(value)
                    return floating((word + 0x7fff + ((word >> 16) & 1)) & 0xffff0000) if bf16 else value

                inputs = list(map(rounded, values))
                gamma = [rounded(fp32(0.5 + ((col * 13) % 101) / 128)) for col in range(64)]
                epsilon = fp32(1e-5)
                normalized, sum_words = [], []
                for row in range(128):
                    current = inputs[row * 64:(row + 1) * 64]
                    row_sum = checked.sum64([fp32(value * value) for value in current])
                    sum_words.append(bits(row_sum))
                    mean = fp32(row_sum / 64)
                    inverse = fp32(1 / fp32(math.sqrt(fp32(mean + epsilon))))
                    normalized.extend(fp32(fp32(value * inverse) * weight) for value, weight in zip(current, gamma))
                machine = PerfTileMachine(inputs, gamma, alpha, epsilon)
                events = []
                gamma_lengths = []

                def run(sequence, tile=0):
                    for instruction in sequence:
                        resolved = tuple(re.sub(r'\b(COLS|tile)\b',
                                                lambda match: str(64 if match[0] == 'COLS' else tile), value)
                                         for value in instruction)
                        if resolved == load_gamma:
                            gamma_lengths.append(machine.vl)
                        machine.execute(*resolved)
                        events.append(resolved)

                run(trace['execute_perf_tile'])
                for tile in range(8):
                    self.assertEqual(list(machine.words(27)[:64]), list(map(bits, gamma)))
                    run(trace['perf_sum_rows'], tile)
                    self.assertEqual(list(machine.words(23)[:16]), sum_words[16 * tile:16 * (tile + 1)])
                    run(trace['perf_normalize_rows'], tile)
                    for row in range(16):
                        first = (16 * tile + row) * 64
                        self.assertEqual(list(machine.words(row)[:64]),
                                         list(map(bits, normalized[first:first + 64])))
                    self.assertEqual(machine.vl, 64)
                    run(trace['perf_quantize_rows'], tile)
                    self.assertEqual(machine.vl, 16)
                    if tile != 7:
                        run(trace['perf_reload_gamma'], tile)
                self.assertEqual(gamma_lengths, [64] + [16] * 7)
                expected = scalar_quantize(normalized, alpha)
                self.assertEqual(machine.output['packed'], expected[0])
                self.assertEqual(machine.output['scales'], expected[1])
                self.assertEqual(len(machine.stores), 72)
                self.assertEqual(sum(length for _, _, length in machine.stores), 4608)
                self.assertEqual(len(machine.written['packed']), 4096)
                self.assertEqual(len(machine.written['scales']), 512)
                counts = Counter(event[0] for event in events)
                self.assertEqual(counts['XV_BLOCK_PACK'], 512)
                self.assertEqual(counts['XV_BLOCK_AMAX'], 128)
                self.assertEqual(counts['XV_FABS'], 0)
                self.assertEqual(counts['XV_FREDUCE_MAX'], 0)
                self.assertEqual(len(events) + 1, 2159)  # Include the final EVU FENCE.
                self.assertEqual(counts['xv_setvli'], 81)
                self.assertEqual(counts['XV_CVT_MXFP4_F32'], 0)
                self.assertEqual(counts['XV_LOAD'], 136)
                self.assertEqual(counts['XV_FSQRT'], 8)
                self.assertEqual(counts['XV_FDIV'], 104)
                self.assertEqual(counts['XV_FREDUCE_SUM'], 128)
                self.assertEqual(counts['XV_CVT_E4M3_F32'], 32)
                self.assertEqual(counts['XV_CVT_F32_E4M3'], 32)
                self.assertNotIn('XV_FSQRT_E0', counts)
                self.assertNotIn('XV_FDIV_E0', counts)

    def test_build_flags_and_variant_isolation_without_building(self):
        makefile = (fused.SOFTWARE / 'Makefile').read_text().replace('include $(AM_HOME)/Makefile.app', '')
        makefile += ('\n.PHONY: inspect\ninspect:\n'
                     '\t$(info source=$(SRCS))\n\t$(info flags=$(CFLAGS))\n'
                     '\t$(info objects=$(DST_DIR))\n\t$(info image=$(BINARY))\n')
        for options in ([], ['EVU_RMS_SAMPLES=3', 'EVU_RMS_BF16_INPUT=1']):
            result = subprocess.run(['make', '--no-print-directory', '-f', '-', 'inspect',
                                     'NAME=' + SOURCE.stem, 'ARCH=riscv64-xs', *options],
                                    cwd=fused.SOFTWARE, input=makefile, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            config = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
            self.assertEqual(config['source'], SOURCE.name)
            for flag in ('-fno-fast-math', '-ffp-contract=off', '-fno-tree-vectorize'):
                self.assertIn(flag, config['flags'])
            self.assertIn(SOURCE.stem, config['objects'])
            self.assertEqual(bool(config['image']), bool(options))


if __name__ == '__main__':
    unittest.main()
