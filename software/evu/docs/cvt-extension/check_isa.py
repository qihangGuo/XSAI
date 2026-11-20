from pathlib import Path
import json,re
P=Path(__file__).resolve().parent;X=P.parents[3];spec=json.loads((P/'evu-cvt-isa.json').read_text());ops=spec['operations']
s=(X/'src/main/scala/xiangshan/backend/decode/EvuDecoder.scala').read_text().split('override val decodeArray')[1]
used={int(x,0) for x in re.findall(r'\bop\((0x[0-9a-f]+|[0-9]+)',s)}|{0x45,0x46}
reserved={0,8,9,10,11,0x18,0x19,0x38,0x49,0x4a,0x4b,0x4c,0x4d,0x4e,0x4f,0x50,0x5e}
assert len({o['funct7'] for o in ops})==18
for o in ops:
 assert (o['funct7'] in used)==(o['status']=='existing'),o
 assert o['funct7'] not in reserved,o
# Validate internal OPIVV funct6 candidates are holes in the enum; F6/RS1 distinct per route.
s=(X/'src/main/scala/xiangshan/evu/common/Consts.scala').read_text().split('object OPIFunct6')[1].split('object OPMFunct6')[0];enum={};index=0
for line in s.splitlines():
 m=re.match(r'\s*val (.*?) = Value\s*$',line)
 if m:
  for name in m[1].split(','):
   name=name.strip();enum[index]=name;index+=1
# The enum ends at wsll=0x35 then jumps explicitly to illegal=0x40;
# 0x36..0x3f are undeclared holes, not named underscore values.
assert enum[0x35] == 'wsll' and len(enum) == 0x36
assert re.search(r'val illegal = Value\(0x40.U\)', s)
for value in range(0x36, 0x40): enum[value] = '_'
for o in ops:assert enum[o['internal_f6']]=='_',o
for i,o in enumerate(ops):
 for p in ops[i+1:]:
  assert o['internal_f6']!=p['internal_f6'] or (o['internal_rs1'] is not None and p['internal_rs1'] is not None and o['internal_rs1']!=p['internal_rs1']), (o,p)
# Existing source macros and external selectors remain identical.
macros=(X/'software/evu/include/evu_intrinsics.h').read_text()
for o in ops[:6]:assert re.search(r'#define '+o['name']+r'\([^\n]*XV_BINARY\(0x'+format(o['funct7'],'x')+',',macros),o
# Pure encoding contract tests, no claim of decoder RTL.
checks=0
for o in ops:
 for rd in [1,16,31]:
  for src in [0,7,30]:
   if rd==src:continue
   for scale in ([0,7,30] if o['scale']=='UE8M0' else [0]):
    if o['scale']=='UE8M0' and rd==scale:continue
    word=(o['funct7']<<25)|(src<<20)|(scale<<15)|(2<<12)|(rd<<7)|0xb
    assert (word>>25)==o['funct7'] and (word&127)==11
    sub=scale if o['internal_rs1'] is None else o['internal_rs1']
    internal=(o['internal_f6']<<26)|(1<<25)|(src<<20)|(sub<<15)|(rd<<7)|0x57
    matches=[p for p in ops if p['internal_f6']==internal>>26 and (p['internal_rs1'] is None or p['internal_rs1']==((internal>>15)&31))]
    assert matches==[o];checks+=1
r={'status':'PASS','scope':'static proposal collision/mapping check, no EVU RTL implementation','operations':18,'existing':6,'new':12,'encoding_cases':checks,'new_external_selectors':[hex(o['funct7']) for o in ops if o['status']=='proposed'],'internal_reserved_enum_verified':True,'capability_allocation':'proposed 30/raw,31/MX,32/BF16; owner review required'}
print(json.dumps(r,indent=2))
