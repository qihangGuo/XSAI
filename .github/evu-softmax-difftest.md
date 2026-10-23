# Online softmax：归约差异、异常标志与精度验证

更新：下文保留此前共享 FCSR 版本的故障分析。目前已实现独立 EVU CSR ABI 1，
EVU 异常改写 `efflags`，不再发布到核心 FCSR。地址、软件启用方法及当前验证
边界以 [EVU CSR ABI 1](evu-csr-architecture.md) 为准；必须配套更新 RTL、NEMU、
Difftest 和测试程序。既有 trace 重放通过不等于新增 CSR RTL 已通过仿真。

2026-10-23。配置为 `DefaultExternalVectorMatrixConfig`，VLEN=4096、DLEN=256、
FP32、VL=128、不使用掩码。输入与 `software/evu/online_softmax_evu_test.c` 一致。

## 更新日志：已有 FCSR.NX 导致 NEMU 丢失本条指令的 NX

更新后的 `evu_softmax.log` 已通过 case 0 uniform，首个差异变为：

```text
[DiffTest][EVU] completion mismatch core=0 cycle=185941 pc=0x80000592
EVU completion mismatch pc=0x80000592/0x80000592 instr=0x645f228b/0x645f228b flags=0x1/0x0 stores=0/0
```

`0x645f228b` 为 `FMUL v5, v30, v5`（FP32），对应 rising-max 第一个 block
将 `input-new_max` 乘 `log2(e)` 的步骤。0x1 是 NX（舍入不精确）；此处报错
比较的是每条 EVU 指令的 completion flags，还未到这条指令的 VRF 快照比较。
日志同时显示标量 `fcsr=fflags=0x1`、`frm=0`。uniform 用例结束后的标量
参考计算可以置 NX，后续 EVU 再次产生 NX 也必须在 completion 中报告。

问题在 NEMU `instr/evu/compute.c` 的异常收集方式。`rtl_vfpcall` 每完成一个
元素，就将 SoftFloat 异常 OR 到 FCSR，再清空 SoftFloat 的临时异常。
原实现最后使用：

```c
job->flags |= softfloat_exceptionFlags | (fcsr->fflags.val & ~saved_fcsr_flags);
```

若原有 NX=1，本次再次产生的 NX 在第二项中被过滤，而第一项早已清零，
因此得到 REF=0。异常标志具有粘滞性，不能用寄存器位的增量恢复本条指令
产生的全部异常。这不是此次 RTL 乘法精度或前次归约顺序的问题。

独立按输入位模式计算，block 0 的元素 1 为 `-1.6875 * 0x3fb8aa3b`：
精确乘积为 `-2.4345478489995003`，FP32 舍入为 `-2.4345479011535645`
（`0xc01bcfa2`），相对舍入误差约 `2.14225e-8`。乘积确实不可精确表示，
因此这条向量乘法应报告 NX；NX 本身不表示超出精度要求。

已改为：保存原有 FCSR flags 和 FRM；在临时清零的 flags 上完成本条计算，
收集全部 flags 后恢复标量状态；异步 completion 成功时，才将本条 flags OR
到当时的架构 FCSR。计算和舍入方法没有改变，也没有屏蔽 NX。

此前本机回归的 `run_raw` 每条指令都从 `initial` 恢复标量状态，原有 flags
始终是零，因此漏掉了该条件。新增回归先在旧动态库上使用同一指令编码及
rising-max 对应输入复现 `flags=0x1/0x0`，修复后通过：

- 日志指令分别在已有 NX=0/1 时运行，两者 completion 都为 NX=1，VRF 逐位相同。
- 896 组 FP16/FP32 测试，覆盖全部 32 种已有 flags、精确运算及 NX/NV/OF/UF/DZ、
  同步/异步发布和合法 FRM 保存。异常只由第一个元素产生，后续精确元素不能丢失它。
- 将此前保存的真实 RTL trace 分别在标量 flags=0x00/0x01/0x1f 时回放到
  NEMU 异步接口。每轮 690 条指令、112 次 STORE、896 个物理写块，结果与
  completion flags 全部一致，`pending=0`。
- 七组 softmax 的独立精度指标与下表相同，数值计算未改变。

本轮仅构建 NEMU 动态库和运行本机 C 回归，没有运行 EMU，没有生成 Verilog
或 RISC-V guest bin，也没有重跑会生成 RTL 的 Scala 仿真测试。回归输出见
`/tmp/evu-softmax-sticky-flags-before.log`、`/tmp/evu-softmax-sticky-flags-after.log`。

此次新增修复只涉及 NEMU，无需为这个 FCSR 修复再次重建 RTL。如果服务器
EMU 已包含前次 FMA flags/归约掩码修正，更新并重建实际 `--diff` 指向的
NEMU 动态库即可；本日志指向 `/nfs/home/guoqihang/workspace/NEMU_evu/build/riscv64-nemu-interpreter-so`。
在该 NEMU 目录中运行：

```sh
make NEMU_HOME="$PWD" git_commit= -j8
make -C tools/evu-ref-test NEMU_HOME="$PWD"
./tools/evu-ref-test/evu_compute_ref_test ./build/riscv64-nemu-interpreter-so
```

然后在 XSAI_external_vector 目录使用服务器生成的 EMU 和 guest bin 重跑原命令。
完整核 EMU Difftest 仍待服务器确认，要求全部七组 PASS、最终 `HIT GOOD TRAP`、
无 Difftest mismatch。

## 前次日志：1 ULP 差异来自 NEMU 归约顺序

`evu_softmax.log` 首个差异为：

```text
[DiffTest][EVU] vr6 word=0 DUT=0x000000004200d92a REF=0x000000004200d92b
```

对应 case 1（rising-max）、block 1 的 `FREDUCE_SUM`，指令编码 `0x6e5ea30b`。
这一指令对 v5 的 128 个 FP32 指数结果求和，seed 为 v29[0]=+0，结果写 v6[0]。
前面的 Exp2 不是这个差异的来源。

Saturn 的 `fredusum` 使用四个 element-group 累加槽。DLEN=256 时，每组有八个
FP32 元素，元素 i 进入 `slot=(i/8)%4`、`lane=i%8`。各槽逐组累加，随后依次将
槽 1、2、3 合并至槽 0，最后将八个 lane 按上半部折叠，步长为 4、2、1。
seed 在槽 0、lane 0。每一次加法都按 FP32 舍入。

旧 NEMU 将元素按索引依次累加。浮点加法不满足结合律，因此串行和与 Saturn
归约树可以相差低位。对相同的 Exp2 输出，实测如下：

| 计算方式 | FP32 编码 | 十进制 | 相对独立数学参考的误差 |
| --- | --- | --- | --- |
| 旧串行 FP32 和 | `0x4200d92b` | 32.2120780945 | 1.05922448e-7 |
| Saturn 归约树 | `0x4200d92a` | 32.2120742798 | 1.25020031e-8 |
| 独立 `sum(exp(x-max))` 参考 | — | 32.212074682500614 | — |

这既复现日志两端的位模式，也说明本例中 RTL 的数值更接近数学参考。
1 ULP 在此处为 2^-18，约 3.8146973e-6。常量 uniform 用例都是整数/二的幂，
无法暴露这个舍入顺序差异，因而先通过了 case 0。

当前 NEMU 已有的 `src/isa/riscv64/instr/evu/compute.c:fp_unordered_sum` 四槽模型
被保留并核实。它使用配置的 DLEN 和 SEW，调用现有 SoftFloat 操作处理每一次
加法及 flags，没有改成 host double 求和，也没有放宽 Difftest 比较条件。

## 另行确认的 RTL flags 问题

本地保存的 Saturn 源码
`vpu/third_party/saturn_chipyard/generators/saturn/src/main/scala/exu/fp/FPFMAPipe.scala`
中也有以下写法，EVU 的 `SegmentedFMAPipe` 继承了它：

```scala
exc(data_type).slice(index, index + ftype_exc_lanes(data_type))
  .foreach { _ := Pipe(fma.io.validout, narrower.io.invalidExc, depth-4).bits }
```

`invalidExc` 是舍入模块的 1 位输入，不是完整的异常输出。应使用 5 位
`exceptionFlags`，编码 `{NV,DZ,OF,UF,NX}`。原接法丢弃舍入产生的 NX/OF/UF；
1 位的 true 接到 5 位端口还会成为 NX=1，而不是 NV=1。

另外，index 是元素编号，exc 是字节数组。FP32 元素 0/1 应分别使用字节
`[0,4)`、`[4,8)`，原实现却选取 `[0,4)`、`[1,5)`。修复改为
`firstByte=index*bytesPerElement`，每个结果字节携带该元素的完整 5 位标志。
FMA 结果计算和舍入路径保持原计算方式。

`Backend.scala` 的归约折叠还会对丢弃的高 lane 开启全部写/flags 掩码。
VL 之外的 sNaN 虽然不会写回最终结果，却可能在中间折叠产生多余 NV。
折叠掩码现随有效 lane 数缩小；最终仍只写元素零，其他目的元素保持原值。
这些问题与上述日志中的 1-ULP 数值差异分别处理。

## 验证结果

- `EvuSaturnReductionSpec`：FP16/FP32、VL=0/1/3/7/8/9/15/16/17/31/32/33/
  63/64/65/127/128/129/255/256（各宽度取合法长度）、大数消去、四槽复用、
  seed/目标尾部保持、非活动 sNaN 不产生 flags，以及日志中的求和值。
- `EvuFpFlagsSpec`：Tandem/Segmented 两种 FMA，FP16/FP32 每个 packed 元素的
  NV、OF、UF、NX 与逐字节位置。输入分别覆盖精确运算、半 ULP 舍入、溢出、
  下溢和 infinity*zero。
- `EvuElementWidthSpec`、`EvuSaturnExp2Spec` 与以上两套测试合计 10 项通过。
- `EvuSoftmaxDifftestSpec`：690 条指令，七组数据；每条比较目标寄存器全部
  128 个 word 和 completion flags，含归约目的尾部。112 次向量 STORE 的数据、
  字节掩码和完整写出均检查通过。
- 实际 RTL trace 再送入 NEMU 的异步 `difftest_evu_complete`、
  `difftest_evu_store` 接口：690 条指令、112 次 STORE、896 个 64B 物理写块
  通过，`pending=0`。没有跳过 flags 或使用浮点容差完成 Difftest。

独立精度检查使用 long-double `expl`，没有使用 RTL/NEMU 自身的 Exp2 输出
构造期望值。七组数据各有 512 个元素，得到以下最大误差：

| 指标 | 最大误差 |
| --- | --- |
| 指数值相对误差 | 1.64186475e-6 |
| 在线分母相对误差 | 8.7303498e-8 |
| 最终概率相对误差 | 1.64186611e-6 |
| 概率总和绝对误差 | 6.59646116e-8 |

最大值、所有中间广播 lane、正概率范围及 ±64 平移不变性也通过。
这些结果覆盖测试中的有界有限 FP32 输入，不能推广为任意输入/格式的误差上限。

## 重跑

先使用 NEMU 的 `riscv64-xs-evu-ref_defconfig` 配置构建 reference。已有对应配置
时直接在 NEMU 目录运行 `make NEMU_HOME="$PWD" git_commit= -j8` 即可。

在 XSAI_external_vector 根目录运行：

```sh
bash software/evu/scripts/check_softmax_ref.sh ../NEMU
```

脚本保存 `out/evu-softmax-difftest/reference.trace` 和 `rtl.trace`，运行高精度
本机参考、RTL 逐位比较、归约/flags 定向回归及异步 Difftest ABI 检查。

本地已使用 GNU 交叉工具链编译出测试 bin、elf 和反汇编：

```sh
NAME=online_softmax_evu_test make -C software/evu \
  AM_HOME="$PWD/../nexus-am" git_commit= ARCH=riscv64-xs LINUX_GNU_TOOLCHAIN=1
```

本地 nexus-am 使用 GNU 工具链，`LINUX_GNU_TOOLCHAIN=1` 选择现有的
`riscv64-linux-gnu-*`。NAME 通过环境传递，避免命令行 NAME 覆盖递归构建的
am/klib 库名；没有修改构建工具或测试的精度阈值。

本地没有 `build/emu` 可执行文件，本次没有完成完整核 EMU 运行。
组件级算术/flags/存储及 Difftest ABI 通过，不能替代 scalar retirement、CSR
时序和 HBL2 全系统验证。服务器需要同时重建 EMU 和 NEMU 后运行：

```sh
make git_commit= emu CONFIG=DefaultExternalVectorMatrixConfig EMU_THREADS=8 \
  ACCELERATOR_CDC_ENABLE=0
NAME=online_softmax_evu_test make -C software/evu git_commit= ARCH=riscv64-xs TOOLCHAIN=LLVM
set -o pipefail
./build/emu --diff ../NEMU/build/riscv64-nemu-interpreter-so -C 20000000 \
  -i software/evu/build/online_softmax_evu_test-riscv64-xs.bin \
  2>&1 | tee run_online_softmax_evu_diff.log
```

验收要求：七组 PASS、最终 `HIT GOOD TRAP`、无 Difftest mismatch、`pending=0`。
旧 EMU 的 flags 接线不会因仅更新 NEMU 动态库而改变。
