# EVU 工作上下文

更新时间：2026-11-13

## 当前目标与约束

优化外挂向量 EVU/Saturn 对 `[4, 512]` FP32 RMSNorm + NVFP4 分块量化的性能。算法包括逐行平方和与 `rsqrt(sum / 512 + eps)`、归一化，以及每 16 个元素的 `amax`、E4M3 block scale、缩放、E2M1 转换与打包。

用户明确要求：不在本地运行 EMU、不生成 Verilog、不生成 guest bin；RTL 性能必须在服务器 EMU 验证。继续工作时保留当前未提交修改，不做 reset/checkout 覆盖。

## Git 状态

- 仓库：`XSAI_external_vector`
- 分支：`feat/evu-optimization`
- 最近 checkpoint：`142923fc9 checkpoint(evu): preserve reduction and RMSNorm optimizations`
- 前一个基线：`0f9620bcd checkpoint(evu): preserve RMSNorm NVFP4 hardware and tests`
- 目前 checkpoint 之后有未提交 RTL、软件、测试和文档改动。

恢复工作先执行：

```sh
cd /home/qh/workspace/XSAI/workspace/code/XSAI_external_vector
git status --short
git diff --check
```

## 最新测试结果

日志：`XSAI_external_vector/rmsnorm.log`。本次是软件 `format=10 lane_sum=1 quant_rename=1`，**EMU 仍为旧 RTL**，未包含当前待验证的新 RTL。

| 阶段 | row_major | wave4 |
|---|---:|---:|
| `sumsq` | 499 | 492 |
| `scale_div_eps` | 201 | 未单独测 wave4 |
| `rsqrt_evu` | 312 | 293 |
| `rms_evu` | 910 | 1010 |
| `quant_evu` | 3983 | 共用同一量化调度 |
| `fused_nvfp4` | 5432 | 5575 |

数据覆盖 4 行共 2048 个 FP32 元素，每项一次。`[RN] PASS`、`errors=0`、`HIT GOOD TRAP`、Difftest `pending=0`。完整程序 `instrCnt=306,468`、`cycleCnt=424,002`，不是算子周期。

此前 format-8 的最好结果：RMSNorm 1037、量化 4006、完整算子 5608 cycles。当前相对旧最好结果分别减少约 12.2%、0.6%、3.1%。量化基本持平，完整算子最好为 5432。不要把 format-9 回退实验混进 format-8 基线。

## 软件改动

`software/evu/rmsnorm_nvfp4_evu_test.c`：默认 `EVU_RMS_LANE_SUM=1` 时，同一行四个 128 元素块逐位置累计平方和：首块平方初始化 accumulator，其余块用独立 FP32 乘法和加法累加，最后每行只做一次 `XV_FREDUCE_SUM`。四行横向归约从 16 次降为 4 次，不是总加法工作量减少 4 倍。没有合并成 FMA。`EVU_RMS_LANE_SUM=0` 恢复旧的软件归约调度。

`software/evu/include/rmsnorm_nvfp4_batch.h`：保持 VL=16、SEW=32、每 batch 16 块和量化公式/舍入语义；复用已消费的 v2/v3 作为后续 amax scratch，避免与 packing scratch 争用；重排常量寄存器以尝试避开特定 VRF bank 冲突。`EVU_RMS_QUANT_RENAME=0` 恢复旧寄存器调度。默认两个开关均为 1。

软件优化主要改善 RMSNorm；量化仅快约 0.6%，应视为基本持平。row_major 的完整融合比 wave4 快（5432 vs 5575），当前不要默认选 wave4。

## 待服务器验证的 RTL 改动

参数默认开启：`singleGroupSlide`、`crossInstructionPrefetch`、`extendedResultForwarding`，位于 `src/main/scala/xiangshan/evu/EvuParams.scala`。

- **单 DLEN 组内 slide 快路径**：在 `VectorFastPaths.scala` 中判定简单无 mask、vstart=0、SEW 合法且源/目标落在一个数据组的 slideup/slidedown；复用 VRF 读端口和参数化 shifter，绕过通用 SpecialSequencer/Gather/Compactor。复杂、跨组、mask/restart/slide1/越界继续旧路径；不新增指令、FP4 专用缓存，也不写死 64B。
- **跨指令操作数预读**：`Backend.scala` 和 `ExecuteSequencer.scala` 复用已有 collector，在当前指令尾部检查 queue head 并最多预读三个操作数组；保留 VAT、旧写者和指令类型约束。是 VRF operand lookahead，不是 cache prefetch 或任意 OOO issue。
- **完整组结果旁路**：将 full-EG forwarding 扩展至整数、FP divide、普通转换、XX8、MXFP 等路径；完整写掩码结果才可旁路，迭代单元可在写回反压下保持结果。partial、packed FP4、归约部分结果不旁路；保留 VAT 年龄与 completion 语义。

关键实现文件还包括 `src/main/scala/xiangshan/evu/exu/ExecutionUnit.scala`、`SpecialSequencer.scala` 和 `common/Parameters.scala`。

新增/扩展的测试：`src/test/scala/evu/EvuGroupSlideSpec.scala`、`EvuIterForwardingSpec.scala`、`EvuIndependentOperandSpec.scala`、`EvuSlideSpec.scala`，及 `software/evu/scripts/test_rmsnorm_sumsq_schedule.py`。

## RTL 背景与瓶颈

- 默认 VLEN=4096、DLEN=512、MLEN=512，32 个向量寄存器、16 KiB VRF；FP32 每个 DLEN group 有 16 个元素。
- 8 个 64-bit lane，每 lane 4 个 64-bit 1RW SRAM bank，共 32 bank；execute queue 深度 8，dispatch 深度 4，reductionContexts=2。
- FP32 divide/sqrt 有 16 路迭代单元，不是单周期流水 divider；XX8/MXFP 是各自独立 16-lane、2-stage FU。
- chaining/OOO 已开，但 VL=16 短指令间的数据依赖、VRF 读响应和队列等待仍然存在。
- 已有 FP32 min/max tree：16→8→4→2→1，15 比较节点、4 个寄存级，只替代最终横向 fold。FP32 sum 没有专用加法树，仍依赖 partial accumulator 与共享 FMA 反馈。

日志中全程序累计性能计数（包含准备和检查、计数之间可重叠，不可相加成算子周期）：

```text
vrf_bank_conflict_cycles          669
vrf_write_blocks_read_cycles     1242
vrf_operand_wait_cycles          7895
vrf_read_response_wait_cycles    7488
execute_dependency_wait_cycles   8581
execute_operand_wait_cycles      5480
execute_permutation_wait         4201
execute_feedback_wait             855
reduction_full                   1276
vmu_ack_wait                    10437
div_issue                         140
fp_xx8_issue                       48
fp_xx8_idle_with_work             438
fp_mxfp_issue                     384
fp_mxfp_operand_wait              632
fp_mxfp_queue_wait                1571
fp_mxfp_idle_with_work            2327
```

主要瓶颈仍是 VRF 操作数响应、执行依赖、slide/排列与转换 FU 供数，不是单纯 bank conflict。idle-with-work 表示 FU 有待处理工作但没有连续可发射的操作数；这些是全程序计数，不是单独量化区间的精确分解。

## 已完成的本地验证

未运行本地 EMU，未生成 Verilog 或 guest bin。Python RMSNorm/NVFP4 与调度测试、lane-sum schedule 测试、默认和四种软件开关组合的 C 语法检查、`mill -i evuCheck.compile evuCheck.test.compile` 均通过。Scala 有既存 warnings，无新增编译错误。Chisel 仿真测试尚未执行；需在服务器验证。

## 服务器操作顺序

先用旧 RTL 保存 format-10 软件基线（当前 `rmsnorm.log` 已有），之后在服务器构建含本轮 RTL 的 EMU：

```sh
set -o pipefail
make emu CONFIG=DefaultExternalVectorMatrixConfig \
  ACCELERATOR_CDC_ENABLE=0 EMU_THREADS=8 \
  EMU_TRACE=fst -j16 2>&1 | tee xsai_build.log
```

再构建 guest：

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM \
  NAME=rmsnorm_nvfp4_evu_test
```

用匹配 NEMU 运行（`-i` 路径根据服务器当前目录调整）：

```sh
./build/emu \
  --diff /nfs/home/guoqihang/workspace/NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 \
  -i ../XSAI_external_vector/software/evu/build/rmsnorm_nvfp4_evu_test-riscv64-xs.bin \
  2>&1 | tee rmsnorm_new_rtl.log
```

比较：

```sh
python3 software/evu/scripts/compare_rmsnorm_runs.py rmsnorm.log rmsnorm_new_rtl.log
```

必须使用相同 format-10 guest 比较新旧 EMU，才能区分 RTL 收益。检查 `[RN] PASS`、`errors=0`、`HIT GOOD TRAP`、`pending=0`，并关注 `rms_evu`、`quant_evu`、两个 fused 项及 VRF/permutation/MXFP 计数器。还要回归旧 Softmax、FP divide、low precision、vector-add 和 slide。若新 RTL 无收益或回归，可逐项关闭 `singleGroupSlide`、`crossInstructionPrefetch`、`extendedResultForwarding` 定位。
