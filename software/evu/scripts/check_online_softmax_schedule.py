#!/usr/bin/env python3
"""Preprocess and interpret guest schedules; no binary, NEMU or RTL execution.

Uses ideal exp2 and a sequential FP32 sum only to catch layout/schedule bugs.
It does not validate DUT exp2 accuracy, unordered reduction bits or timing.
"""
import math
import re
import struct
import subprocess
from collections import Counter
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'XSAI_external_vector/software/evu/online_softmax_evu_test.c'
CLANG = ['clang', '--target=riscv64', '-march=rv64gc', '-std=gnu11',
         '-DARCH_H="arch/riscv64-xs.h"', '-I', 'nexus-am/am',
         '-I', 'nexus-am/am/include', '-I', 'nexus-am/libs/klib/include',
         '-I', 'XSAI_external_vector/software/evu/include',
         '-I', 'nexus-am/tests/exp2test/include']


def f32(x):
    return struct.unpack('<f', struct.pack('<f', x))[0]


def balanced(text, at, opening='(', closing=')'):
    assert text[at] == opening, text[at:at + 80]
    depth, quote, escape = 1, None, False
    for i in range(at + 1, len(text)):
        ch = text[i]
        if quote:
            if escape:
                escape = False
            elif ch == '\\':
                escape = True
            elif ch == quote:
                quote = None
        elif ch in '\"\'':
            quote = ch
        elif ch == opening:
            depth += 1
        elif ch == closing:
            depth -= 1
            if depth == 0:
                return text[at + 1:i], i + 1
    raise AssertionError('Unbalanced expression')


def parse(text):
    nodes, i = [], 0
    while i < len(text):
        if text[i].isspace():
            i += 1
            continue
        if text[i] == ';':
            i += 1
            continue
        if text[i] == '{':
            body, i = balanced(text, i, '{', '}')
            nodes.extend(parse(body))
            continue
        if text[i:i + 2] == 'do':
            at = i + 2
            while text[at].isspace():
                at += 1
            body, at = balanced(text, at, '{', '}')
            end = re.match(r'\s*while\s*\(\s*0\s*\)\s*;', text[at:])
            assert end, text[at:at + 80]
            nodes.extend(parse(body))
            i = at + end.end()
            continue
        control = re.match(r'(for|if)\s*\(', text[i:])
        if control:
            kind = control.group(1)
            cond, i = balanced(text, i + control.end() - 1)
            while text[i].isspace():
                i += 1
            body, i = balanced(text, i, '{', '}')
            other = []
            j = i
            while j < len(text) and text[j].isspace():
                j += 1
            if kind == 'if' and text[j:j + 4] == 'else':
                j += 4
                while text[j].isspace():
                    j += 1
                other_text, i = balanced(text, j, '{', '}')
                other = parse(other_text)
            nodes.append((kind, cond, parse(body), other))
        else:
            end = text.index(';', i)
            nodes.append(('stmt', text[i:end].strip()))
            i = end + 1
    return nodes


class Pointer:
    def __init__(self, name, offset=0, strides=(1,)):
        self.name, self.offset, self.strides = name, offset, strides

    def __add__(self, value):
        return Pointer(self.name, self.offset + value, self.strides)

    def __getitem__(self, index):
        return Pointer(self.name, self.offset + index * self.strides[0],
                       self.strides[1:] or (1,))


PATTERN = [-4, -2.5, -1, -.25, 0, .5, 1.25, 2,
           3, 3.5, -3, 1.75, -1.5, .25, 2.5, -.75]


def input_value(row, col):
    return f32(f32(PATTERN[(col + row * 3) % 16] +
                   f32((col % 11 - 5) * .00390625)) + f32(row * .015625))


class Machine:
    def __init__(self, rows, cols, stride, scale, first):
        self.rows, self.cols, self.stride = rows, cols, stride
        self.scale, self.first, self.vl = f32(scale), first, 0
        self.regs = [[math.nan] * 128 for _ in range(32)]
        self.memory = {
            'scores': [input_value(r, c) if c < cols else 0.0
                       for r in range(rows) for c in range(stride)],
            'row_max': [f32(2 + r * .0625) for r in range(rows)],
            'row_sum': [f32(8 + r) for r in range(rows)],
            'row_alpha': [0.0] * rows,
            'seeds': [f32(-float.fromhex('0x1.fffffep127'))] * 128 + [0.0] * 128,
            'parameters': [0.0] * 3,
        }
        self.env = dict(ROWS=rows, COLS=cols, STRIDE=stride, VL_MAX=128,
                        online_scale=self.scale, first=first,
                        seeds=Pointer('seeds', strides=(128, 1)),
                        parameters=Pointer('parameters'))
        for name in ['scores', 'row_max', 'row_sum', 'row_alpha']:
            self.env[name] = SimpleNamespace(data=Pointer(name))
        self.counts = Counter()
        self.trace = []

    def expression(self, text):
        text = text.strip().replace('&&', ' and ').replace('&', '')
        text = re.sub(r'0x[0-9a-fA-F.]+p[+-]?\d+f?',
                      lambda m: repr(float.fromhex(m[0].rstrip('f'))), text)
        text = re.sub(r'(?<=\d)f\b', '', text)
        text = re.sub(r'!(?!=)', ' not ', text).strip()
        if '?' in text:
            cond, choices = text.split('?', 1)
            yes, no = choices.split(':', 1)
            return self.expression(yes if self.expression(cond) else no)
        return eval(text, {'__builtins__': {}}, self.env)

    def assign(self, text):
        if text.startswith('++'):
            self.env[text[2:].strip()] += 1
            return
        text = re.sub(r'^(?:(?:const|unsigned|float)\s+)+', '', text)
        match = re.fullmatch(r'(.+?)\s*(\+=|=)\s*(.+)', text)
        assert match, text
        lhs, op, rhs = match.groups()
        value = self.expression(rhs)
        if '[' in lhs:
            pointer = self.expression(lhs)
            assert op == '='
            self.memory[pointer.name][pointer.offset] = f32(value)
        else:
            self.env[lhs.strip()] = self.env.get(lhs.strip(), 0) + value if op == '+=' else value

    def access(self, pointer, count, values=None):
        assert 0 <= pointer.offset and pointer.offset + count <= len(self.memory[pointer.name])
        if pointer.name == 'scores':
            for i in range(count):
                assert (pointer.offset + i) % self.stride < self.cols, 'Padding accessed'
        self.counts[('write_' if values is not None else 'read_') + pointer.name] += count
        if values is not None:
            self.memory[pointer.name][pointer.offset:pointer.offset + count] = values
        return self.memory[pointer.name][pointer.offset:pointer.offset + count]

    def call(self, name, args):
        self.trace.append((name, args))
        if name == 'configure':
            self.vl = self.expression(args[0])
            self.counts['configs'] += 1
            return
        if name == 'select_vl':
            target = self.expression(args[0])
            if target != self.env['current_vl']:
                self.call('configure', args[:1])
                self.env['current_vl'] = target
            return
        if name in ['synchronize', 'XV_CSR_WRITE']:
            return
        self.counts[name] += 1
        d = self.expression(args[0])
        count = 1 if name.endswith('_E0') else self.vl
        if name in ['XV_LOAD', 'XV_LOAD_E0', 'XV_STORE', 'XV_STORE_E0']:
            pointer = self.expression(args[1])
            if 'LOAD' in name:
                self.regs[d][:count] = self.access(pointer, count)
            else:
                assert all(math.isfinite(v) for v in self.regs[d][:count]), name
                self.access(pointer, count, self.regs[d][:count].copy())
            return
        if name in ['XV_EXP2', 'XV_EXP2_E0']:
            source = self.expression(args[1])
            values = self.regs[source][:count].copy()
            assert all(math.isfinite(v) for v in values), name
            self.regs[d][:count] = [f32(2.0 ** v) for v in values]
            return
        a, b = self.expression(args[1]), self.expression(args[2])
        if name in ['XV_GATHER', 'XV_GATHER_IMM', 'XV_BROADCAST']:
            if name == 'XV_BROADCAST':
                b = a
            indices = self.regs[a][:count] if name == 'XV_GATHER' else [a] * count
            if name == 'XV_BROADCAST':
                indices = [0] * count
            self.regs[d][:count] = [self.regs[b][int(i)] if i < 128 else 0 for i in indices]
            return
        if name.startswith('XV_FREDUCE_'):
            values = self.regs[b][:self.vl].copy()
            result = self.regs[a][0]
            assert all(math.isfinite(v) for v in values + [result]), name
            for v in values:
                result = max(result, v) if name.endswith('MAX') else f32(result + v)
            self.regs[d][0] = result
            return
        va = [self.regs[a][0]] * count if name.endswith('_SCALAR') else self.regs[a][:count].copy()
        vb = self.regs[b][:count].copy()
        assert all(math.isfinite(v) for v in va + vb), name
        if name.startswith('XV_FMAX'):
            result = [max(x, y) for x, y in zip(va, vb)]
        elif name.startswith('XV_FADD'):
            result = [f32(y + x) for x, y in zip(va, vb)]
        elif name.startswith('XV_FSUB'):
            result = [f32(y - x) for x, y in zip(va, vb)]
        elif name.startswith('XV_FMUL'):
            result = [f32(y * x) for x, y in zip(va, vb)]
        else:
            raise AssertionError(name)
        self.regs[d][:count] = result

    def execute(self, nodes):
        for node in nodes:
            kind = node[0]
            if kind == 'if':
                self.execute(node[2] if self.expression(node[1]) else node[3])
            elif kind == 'for':
                init, cond, step = node[1].split(';')
                self.assign(init.strip())
                iterations = 0
                while self.expression(cond):
                    self.execute(node[2])
                    self.assign(step.strip())
                    iterations += 1
                    assert iterations < 10000
            else:
                statement = node[1]
                if statement.startswith('__asm__'):
                    continue
                call = re.fullmatch(r'(\w+)\((.*)\)', statement, re.S)
                if call:
                    self.call(call[1], [s.strip() for s in call[2].split(',')])
                else:
                    self.assign(statement)

    def check(self):
        for row in range(self.rows):
            old_max, old_sum = f32(2 + row * .0625), f32(8 + row)
            maximum = f32(max(input_value(row, c) for c in range(self.cols)) * self.scale)
            if not self.first:
                maximum = max(old_max, maximum)
            alpha = 0.0 if self.first else math.exp(old_max - maximum)
            expected_sum = alpha * old_sum
            for col in range(self.cols):
                expected = math.exp(input_value(row, col) * self.scale - maximum)
                expected_sum += expected
                actual = self.memory['scores'][row * self.stride + col]
                assert abs(actual - expected) <= 2e-8 + 2e-5 * abs(expected), (row, col, actual, expected)
            assert self.memory['row_max'][row] == maximum
            assert abs(self.memory['row_alpha'][row] - alpha) <= 2e-8 + 2e-5 * abs(alpha)
            assert abs(self.memory['row_sum'][row] - expected_sum) <= 1e-5 + 3e-5 * expected_sum
            assert all(v == 0 for v in self.memory['scores'][row * self.stride + self.cols:(row + 1) * self.stride])


cases = [(32, 256, 256, 1.0), (4, 1, 8, .5), (8, 17, 32, 2.0),
         (4, 127, 144, .125), (8, 128, 128, 1.0), (4, 129, 144, .125),
         (8, 255, 264, .75), (4, 257, 280, 1.0), (4, 512, 512, 2.0)]
checked = 0
memory_costs = {}
for rows, cols, stride, scale, state, reduce, pipeline, load_batch in [
        (r, c, s, x, 0, 128, 0, 0) for r, c, s, x in cases] + [
        (r, c, s, x, state, red, 0, 0)
        for r, c, s, x in [(32, 256, 256, 1.0), (8, 256, 272, .125),
                           (8, 256, 256, 2.0)]
        for state in [0, 1] for red in [32, 64, 128]] + [
        (r, c, s, x, 1, 128, 0, 0) for r, c, s, x in cases if c <= 128] + [
        (r, 256, s, x, 0, 128, 1, batch)
        for r in [4, 8, 12, 32, 36] for s in [256, 272]
        for x in [1.0, .125, 2.0] for batch in [0, 1]]:
    definitions = [f'-DEVU_SOFTMAX_ROWS={rows}', f'-DEVU_SOFTMAX_COLS={cols}',
                   f'-DEVU_SOFTMAX_STRIDE={stride}', f'-DEVU_SOFTMAX_SCALE={scale}f',
                   f'-DEVU_SOFTMAX_STATE_BATCH={state}', f'-DEVU_SOFTMAX_REDUCE_VL={reduce}',
                   f'-DEVU_SOFTMAX_PIPELINE={pipeline}',
                   f'-DEVU_SOFTMAX_STATE_LOAD_BATCH={load_batch}']
    subprocess.run(CLANG + definitions + ['-O2', '-ffreestanding', '-fno-builtin',
                   '-Wall', '-Werror', '-Wno-main', '-fsyntax-only', str(SOURCE)],
                   cwd=ROOT, check=True, capture_output=True, text=True)
    preprocessed = subprocess.run(CLANG + definitions + ['-DXSAI_EVU_CSR_H', '-E', '-P', str(SOURCE)],
                                  cwd=ROOT, check=True, capture_output=True, text=True).stdout
    start = preprocessed.index('{', preprocessed.index('static void online_update('))
    body, _ = balanced(preprocessed, start, '{', '}')
    ast = parse(body)
    baseline_ast = None
    if pipeline:
        baseline_defs = [arg.replace('PIPELINE=1', 'PIPELINE=0').replace('STATE_LOAD_BATCH=1', 'STATE_LOAD_BATCH=0')
                         for arg in definitions]
        baseline_text = subprocess.run(CLANG + baseline_defs +
            ['-DXSAI_EVU_CSR_H', '-E', '-P', str(SOURCE)], cwd=ROOT,
            check=True, capture_output=True, text=True).stdout
        baseline_start = baseline_text.index('{', baseline_text.index('static void online_update('))
        baseline_body, _ = balanced(baseline_text, baseline_start, '{', '}')
        baseline_ast = parse(baseline_body)
    for first in [0, 1]:
        dut = Machine(rows, cols, stride, scale, first)
        dut.env.update(EVU_SOFTMAX_STATE_BATCH=state, EVU_SOFTMAX_REDUCE_VL=reduce)
        dut.env.update(state_pack=Pointer('state_pack'),
                       fold_indices=Pointer('fold_indices', strides=(reduce, 1)))
        dut.memory['state_pack'] = [math.nan] * 4
        dut.memory['fold_indices'] = [g * reduce + i for g in range(1, 128 // reduce)
                                     for i in range(reduce)]
        dut.execute(ast)
        dut.check()
        if baseline_ast is not None:
            baseline = Machine(rows, cols, stride, scale, first)
            baseline.execute(baseline_ast)
            for name in ['scores', 'row_max', 'row_sum', 'row_alpha']:
                assert all(struct.pack('<f', a) == struct.pack('<f', b) for a, b in
                           zip(dut.memory[name], baseline.memory[name])), name
        if cols == 256:
            assert dut.counts['read_scores'] == rows * cols
            if not state and reduce == 128 and not load_batch:
                assert dut.counts['configs'] == 1
            assert dut.counts['read_row_alpha'] == 0
            assert dut.counts['XV_FREDUCE_MAX'] == rows
            assert dut.counts['XV_FREDUCE_SUM'] == rows
            assert dut.counts['write_scores'] == rows * cols
            max_positions = [i for i, op in enumerate(dut.trace) if op[0] == 'XV_FREDUCE_MAX']
            sum_positions = [i for i, op in enumerate(dut.trace) if op[0] == 'XV_FREDUCE_SUM']
            for batch in range(rows // 4):
                max_gap = dut.trace[max_positions[batch * 4 + 1] + 1:max_positions[batch * 4 + 2]]
                sum_gap = dut.trace[sum_positions[batch * 4 + 1] + 1:sum_positions[batch * 4 + 2]]
                assert sum(op[0] == 'XV_FMUL_SCALAR' for op in max_gap) == 4
                assert sum(op[0] == 'XV_STORE' for op in sum_gap) == 4
                if pipeline:
                    assert sum(op[0] == 'XV_EXP2' for op in max_gap) == 4
                    if batch + 1 < rows // 4:
                        assert any(op[0] == 'XV_LOAD' for op in sum_gap)
            assert dut.counts['XV_EXP2'] == rows * 2 + (rows if state and not first else 0) // 4
            if pipeline:
                assert dut.counts['XV_GATHER_IMM'] == (rows * 2 if load_batch and not first else 0)
                assert dut.counts['read_state_pack'] == 0
                assert dut.counts['write_state_pack'] == 0
                assert dut.counts['configs'] == (1 + rows // 2 if load_batch and not first else 1)
                assert dut.counts['XV_EXP2_E0'] == (0 if first else rows)
            assert dut.counts['write_row_max'] == rows
            assert dut.counts['write_row_sum'] == rows
            assert dut.counts['write_row_alpha'] == rows
            if rows == 32 and stride == 256 and scale == 1.0 and pipeline:
                memory_costs[(load_batch, first)] = (
                    sum(dut.counts[name] for name in
                        ['XV_LOAD', 'XV_LOAD_E0', 'XV_STORE', 'XV_STORE_E0']),
                    dut.counts['configs'], dut.counts['XV_GATHER_IMM'])
        checked += 1
for (batch, first), (commands, configs, gathers) in sorted(memory_costs.items()):
    print(f'COST: loads4={batch} first={first} memory_commands={commands} '
          f'configs={configs} gathers={gathers}')
print(f'PASS: {checked} source-driven schedules; wavefront matches per-row baseline; '
      'syntax checked; ideal exp2 only, no RTL run.')
