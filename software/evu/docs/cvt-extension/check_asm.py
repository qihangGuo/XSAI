"""Assemble every proposed/legacy macro and check the actual ELF instruction words.

Uses Clang's RISC-V integrated assembler; this does not execute EVU instructions.
Run with python3 check_asm.py [--clang /path/to/clang]. No target sysroot needed.
"""
import argparse
import json
import struct
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--clang', default='clang')
args = parser.parse_args()
root = Path(__file__).resolve().parent
ops = json.loads((root / 'evu-cvt-isa.json').read_text())['operations']
calls = []
expected = []
for op in ops:
    scaled = op['scale'] == 'UE8M0'
    calls.append(f"  {op['name']}(1, {('3, ' if scaled else '')}2);")
    expected.append((op['funct7'] << 25) | (2 << 20) |
                    ((3 if scaled else 0) << 15) | (2 << 12) | (1 << 7) | 11)
source = '#define XV_ENABLE_CVT_EXTENSION_PROPOSAL\n#include "evu_cvt_proposal.h"\n'
source += 'void probe(void) {\n' + '\n'.join(calls) + '\n}\n'
with tempfile.TemporaryDirectory(prefix='evu-cvt-asm-') as tmp:
    c = Path(tmp) / 'probe.c'
    obj = Path(tmp) / 'probe.o'
    c.write_text(source)
    subprocess.run([args.clang, '--target=riscv64-unknown-elf', '-march=rv64g',
                    '-mabi=lp64d', '-ffreestanding', '-O2', '-I', str(root),
                    '-c', str(c), '-o', str(obj)], check=True)
    elf = obj.read_bytes()
    assert elf[:6] == b'\x7fELF\x02\x01', 'expected ELF64 little endian'
    shoff = struct.unpack_from('<Q', elf, 40)[0]
    shentsize, shnum, shstrndx = struct.unpack_from('<HHH', elf, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', elf, shoff + i * shentsize)
                for i in range(shnum)]
    strings = sections[shstrndx]
    names = elf[strings[4]:strings[4] + strings[5]]
    text_section = next(s for s in sections if names[s[0]:].split(b'\0')[0] == b'.text')
    payload = elf[text_section[4]:text_section[4] + text_section[5]]
    assert len(payload) % 4 == 0
    words = struct.unpack('<' + 'I' * (len(payload) // 4), payload)
    actual = [word for word in words if (word & 127) == 11]
    assert actual == expected, (actual, expected)
print(json.dumps({'status': 'PASS', 'assembled_words': len(expected),
                  'scope': 'actual macro -> assembler -> ELF bits; no RTL/model execution',
                  'words': [f'0x{x:08x}' for x in expected]}, indent=2))
