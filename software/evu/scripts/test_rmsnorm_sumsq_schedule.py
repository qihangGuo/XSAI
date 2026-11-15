import math
import random
import shutil
import subprocess
import unittest
from collections import Counter
from pathlib import Path

from test_rmsnorm_batch_schedule import fp32, integer, parse_trace


def sumsq_trace(lane_sum, wave):
    compiler = shutil.which('clang') or shutil.which('cc')
    if not compiler:
        raise unittest.SkipTest('a C preprocessor is required; no binary is built')
    source = (Path(__file__).resolve().parents[1] / 'rmsnorm_nvfp4_evu_test.c').read_text()
    macros = source[source.index('#define ROW_CHUNKS'):source.index('static void load_operands')]
    traced = '\n'.join('#define ' + name + '(...) TRACE(' + name + ', __VA_ARGS__)'
                       for name in ('XV_FMUL', 'XV_FADD', 'XV_FREDUCE_SUM'))
    traced += '\n' + macros + f'\nSCHEDULE(SQUARE_CHUNK, {wave}); FINAL_REDUCTIONS RN_SUMSQ_FINAL();\n'
    text = subprocess.run([compiler, '-E', '-P', '-x', 'c',
                           '-DEVU_RMS_LANE_SUM=' + str(lane_sum), '-'],
                          input=traced, text=True, capture_output=True, check=True).stdout
    scheduled, final = text.split('FINAL_REDUCTIONS')
    wave_schedule, row_schedule = scheduled.split(' else {')
    return parse_trace(wave_schedule if wave else row_schedule) + parse_trace(final)


def execute_sum(values, trace):
    registers = [[math.nan] * 128 for _ in range(32)]
    for register in range(16):
        registers[register] = values[128 * register:128 * (register + 1)]
    for register in range(24, 29):
        registers[register][0] = 0.0
    for operation, *arguments in trace:
        destination, source1, source2, sew = map(integer, arguments)
        assert sew == 2
        first, second = registers[source1], registers[source2]
        if operation == 'XV_FMUL':
            registers[destination] = [fp32(left * right) for left, right in zip(first, second)]
        elif operation == 'XV_FADD':
            registers[destination] = [fp32(left + right) for left, right in zip(first, second)]
        elif operation == 'XV_FREDUCE_SUM':
            reduced = first[0]
            for value in second:
                reduced = fp32(reduced + value)
            registers[destination][0] = reduced
        else:
            raise ValueError(operation)
    assert registers[:16] == [values[start:start + 128] for start in range(0, 2048, 128)]
    return [registers[register][0] for register in range(24, 28)]


class RmsnormSumsqScheduleTest(unittest.TestCase):
    def test_actual_schedule_reduces_four_times_without_fused_multiply_add(self):
        for wave in (0, 1):
            optimized = Counter(operation for operation, *_ in sumsq_trace(1, wave))
            baseline = Counter(operation for operation, *_ in sumsq_trace(0, wave))
            self.assertEqual(optimized, {'XV_FMUL': 16, 'XV_FADD': 12, 'XV_FREDUCE_SUM': 4})
            self.assertEqual(baseline, {'XV_FMUL': 16, 'XV_FREDUCE_SUM': 16})

    def test_every_row_starts_with_a_full_accumulator_write(self):
        for wave in (0, 1):
            trace = sumsq_trace(1, wave)
            for row in range(4):
                first = next((operation, list(map(integer, arguments)))
                             for operation, *arguments in trace if integer(arguments[0]) == 24 + row)
                self.assertEqual(first, ('XV_FMUL', [24 + row, 4 * row, 4 * row, 2]))

    def test_schedules_preserve_inputs_and_stay_within_existing_fp32_tolerance(self):
        generator = random.Random(4096)
        patterns = [[fp32(generator.uniform(-8, 8)) for _ in range(2048)],
                    [fp32((-1 if index & 1 else 1) * 2 ** (index % 31 - 15))
                     for index in range(2048)], [0.0] * 2048]
        for values in patterns:
            expected = [math.fsum(fp32(value * value) for value in values[start:start + 512])
                        for start in range(0, 2048, 512)]
            results = []
            for lane_sum in (0, 1):
                for wave in (0, 1):
                    with self.subTest(lane_sum=lane_sum, wave=wave):
                        actual = execute_sum(values, sumsq_trace(lane_sum, wave))
                        for value, reference in zip(actual, expected):
                            self.assertLessEqual(abs(value - reference), 2e-5 * (1 + abs(reference)))
                        if lane_sum:
                            results.append(actual)
            self.assertEqual(results[0], results[1])


if __name__ == '__main__':
    unittest.main()
