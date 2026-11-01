# EVU 与核内 RVV：纯向量 online softmax 性能分析

更新日期：2026-11-01。第 1–6 节记录原日志、比较口径及优化建议；
第 7 节记录对齐测试，第 8 节记录对齐结果和波前软件实现，第 9 节记录
普通 VMU 并发、小存储合并及软件候选的实现与待验证项，第 10 节记录
针对 warm 周期回退的部分写与 CPU 并发入口修正。

## 1. 当前测试测量什么

- EVU 测试：`software/evu/online_softmax_evu_test.c`。
- 核内测试：`nexus-am/tests/exp2test/tests/performance/vfexp2_softmax_online_4row_positive_scale_alpha_compare.c`。
- 核内实现：`nexus-am/tests/exp2test/vendor/xsai_blas_rvv/softmax_online_fp32_vfexp2_4row_positive_scale_alpha.c`。
- 历史结果：工作区 `tmp/doc/XSAI/softmax 优化.md` 第 4.8 节。
- 最新 EVU 结果：仓库根目录 `evu_softmax.log`。

默认输入为 FP32、`rows=32, cols=256, stride=256, scale=1`。每次输入共
8192 个元素，即 32 KiB。测试执行 online softmax 的一次状态更新：

```text
tile_max = FP32(max(input) * scale)
m_new = first ? tile_max : max(old_max, tile_max)
alpha = first ? 0 : exp2(FP32(FP32(old_max - m_new) * log2e))
scores[i] = exp2(FP32(FP32(input[i] * FP32(scale * log2e))
                     - FP32(m_new * log2e)))
sum_new = FP32(FP32(alpha * old_sum) + sum(scores))
```

输出为未归一化的指数值，以及每行一个 FP32 max、sum、alpha；没有 CUTE、
量化、PV 或最终归一化。两端应保持相同输入、初始状态和输出语义，但不要求
相同 VL、LMUL、归约树或汇编调度。不同求和顺序不保证逐 bit 相同。

当前 EVU 使用 `resident_pair2`：四行各两个 128 元素 chunk 保存在 VRF，
max/sum 归约按两行一组提交，中间穿插输入缩放或已完成概率的存储。四行原始
输入占 4 KiB；包括状态及中间值后会占用更多 VRF，不能将剩余容量全部当作
可直接扩大输入驻留的空间。

## 2. 最新实测与比较限制

| 模式 | 正式内核 cycles | warmup cycles | instret | elem/cycle |
| --- | ---: | ---: | ---: | ---: |
| EVU first=0 | 31592 | 31623 | 1112 | 0.259 |
| EVU first=1 | 28898 | 28910 | 848 | 0.283 |
| 历史核内 RVV first=0 | 12224 | 未在此表复用 | 未在此表复用 | 0.670 |

EVU 日志为 `check=0, drain=1`，结束于 `DONE` 和 GOOD TRAP；这表示当前运行
没有报告架构差异或致命状态异常，**不是本次已完成数值精度检查**。默认快速
模式不计算 FP64 参考，也不扫描数值误差；`EVU_SOFTMAX_CHECK=1` 才恢复检查。

相对上一轮 32736/30061 cycles，本轮分别下降约 3.49%/3.87%；相对最早的
40881/35228 cycles，分别下降约 22.72%/17.97%。最新 first=0 与历史核内
12224 cycles 的表面比值为 2.584，差 19368 cycles。

但 EVU 计时包含最终 `XV_FENCE` 和 `fence rw, rw`，历史 RVV compare 使用
直接 `rdcycle`、无结束 fence。**2.584 不是完成边界完全对齐后的最终比值**。
目前没有证据证明尾部排空能解释全部 19368 cycles，也不能自行估计扣掉这部分。

历史 RVV 反汇编还出现标量 `fmadd.s` 行和更新，而当前 EVU 显式分开乘、加。
必须统一或明确记录浮点融合策略后再比较；不能仅凭算法公式相同就宣称浮点
计算步骤完全相同。旧的本地 EVU 反汇编曾对应更早测试，不能代替最新镜像证明。

本次全程序为 228075 cycles，服务器 host time 为 223569 ms，约 3 分 44 秒。
四次内核的打印周期合计 121023；其余包括初始化、重置、计时框架和打印等。
取消参考和扫描主要降低整体仿真耗时，不应计为内核本身的加速。

## 3. 软件侧尚存的问题

### 3.1 紧凑容量不等于低启动成本

按默认 first=0 源码路径静态统计，每次内核有 293 条 EVU 访存指令，其中
165 条只访问一个 FP32，合计有效载荷只有 660 B；另有 128 条完整向量
load/store。这里是逻辑指令/字节统计，不是物理缓存行事务数或 DRAM 流量。

小访问包括旧 max、旧 sum、最终状态和常量。它们仍经过命令启动、HBL2 请求
及完成管理。压紧数组解决了重复保存的问题，没有消除每个数单独访问的往返。
当前合并器只在同一命令内复用缓存行，相邻行的独立状态命令不会自动合并。

软件实验应以四行一组批量读取旧 max/sum，批量计算 max/alpha/sum，再批量
写回状态。可在短 VL 区间进行状态运算，用现有 gather/元素操作把第 i 行
状态提供给该行；标量结果的打包方式须检查现有指令支持。新增的元素提取、
打包、scratch 读写和 VL 切换必须计入内核，不能把这些成本移到计时区外。
常量初始化同样采用一致的计时约定，不能只对一端额外预加载寄存器。

### 3.2 长向量没有自动缩短归约关键链

核内 VLEN=128、LMUL=4 时每个向量为 16 个 FP32；逐列块先按位置更新
max/sum 累加器，最后归约 16 个元素。当前 EVU 两个 chunk 配对后归约
128 个元素，需要流式推进、部分结果合并和最终折叠。不能据此说 EVU 算术
工作量多了八倍，但依赖延迟及其暴露方式确实不同。

保留 VL=128 为基线，增加 VL=64/32 的分组累积实验：分别在组间做逐位置
max 或 FP32 sum 累积，最后归约短累加器，并与下一行独立工作交错。必须
计入更多向量指令、循环、配置及寄存器搬运；还要检查输入是否仍驻留、是否
发生重读。修改求和结合顺序后继续使用同一精度要求，不能默认逐 bit 不变。

## 4. RTL 侧原因与证据边界

### 4.1 HBL2 访问与逐命令串行化

核内使用 L1；32 KiB scores 的重读可能命中 L1，但实际命中率尚未测得。
EVU 普通访存走 HBL2，少读一遍数据不代表延迟必然更低。

`src/main/scala/xiangshan/evu/EvuTop.scala` 的入口条件为：

```scala
val memoryCanStart = !vectorMemoryCommand || !vmuLineBusy
```

下一条访存要等上一条命令在合并器中的状态排空；8 个 outstanding tag
主要用于一条命令内部，不能解释成同时推进 8 条访存指令。
`EvuVmuLineCoalescer.scala` 的缓存行匹配还要求同一个 `commandId`。
`memory_order_stall=0` 不证明没有串行化，外层入口可能已阻止请求进入。

建议先增加外层 memory-start 被阻塞及按命令类型分类的计数，再评估 2–4 个
访存上下文。必须处理独立命令的进度、翻译、错误、完成以及同地址顺序，
保留 RAW/WAR/WAW、flush、异常和 fence 语义；不能直接删除上述门控。
跨命令状态缓存行复用还需处理 CPU/EVU 一致性和失效，不是简单扩大合并范围。

### 4.2 实际功能资源不能只比较聚合宽度

`common/Parameters.scala` 中 SplitFP 分离了 FMA、比较、EXP、除法等发射
路径，但加、减、乘及 sum 归约仍共享一条 256-bit FMA 路径。核内乘法走
VFMA，加减和归约走 VFALU，资源分工不同。因此“两条 128-bit 对一条
256-bit”不能证明两端 online softmax 具有相同吞吐。

EVU 的宽 EXP2 还要等待缩放、减 max 和归约供数。应先测 register-only
EXP2、加/乘、归约及混合链的吞吐和延迟，再决定是否拆分加法/归约资源；
当前没有足够证据优先增加 EXP2、除法器或 VRF 物理端口。

### 4.3 归约上下文和入口队首阻塞仍存在

`backend/Backend.scala` 在分发时分配共享归约上下文；当前两个上下文都被
占用时，第三条归约会挡住 VDQ 队首，后续独立工作也可能无法继续分发。
按 FU 拆分队列和增加归约上下文不等于入口已经可以完全绕过阻塞。

| 累计计数 | 上一轮 | 最新 |
| --- | ---: | ---: |
| reduction contexts full | 8181 | 2495 |
| command/job slot unavailable | 7968 | 4216 |
| AMU issue blocked | 13710 | 8238 |
| VRF bank conflict | 464 | 1246 |
| VRF write blocks read | 670 | 1532 |
| VRF operand wait | 11038 | 12223 |
| FMA issue | 13440 | 13440 |
| FMA operand wait | 4240 | 3750 |
| FMA feedback wait | 1440 | 1440 |

这些统计覆盖 first=0/1 的预热及正式运行，并非某一次正式内核的区间差分。
两行分组减少了归约及命令反压，却增加了 VRF 竞争；各计数会重叠，不能
相加计算损失周期。`fma_operand_wait` 的条件也不只包含 SRAM 等待。
全程序 no-work 计数包含 CPU 初始化，不能用于直接计算内核 MFU。

RTL 优先改善入口阻塞向无依赖命令的传播，并验证仲裁、公平性和依赖正确性。
每个物理 VRF bank 继续保持 1RW；没有证据将 VRF 确认为当前最大瓶颈。

## 5. 两端统一测试的具体建议

1. 保留默认 32×256、scale=1、相同输入和初始状态，first=0/1 分开报告；
   两端均采用“预热一次、完整重置、同步、开始计时、完整算子、结束同步、
   结束计时”。初始化、参考、打印都在区间外，算子必需的搬运和配置都在区间内。
2. EVU 保留 `XV_FENCE` 和 CPU fence；核内补充覆盖向量 load/store 的结束
   同步，并核对当前 RTL 的 fence/CSR 排序实现，不能只加编译器 memory clobber。
   用已有反汇编和服务器提交跟踪验证边界；读 cycle 的采样不等于指令退休。
3. 另设诊断采样报告结束同步前/后周期及差值，量出排空成本。中间 rdcycle
   本身有开销且会扰动调度，统一测法并记录探针影响；主结果不盲目扣除它。
   不能通过删除 EVU 完成等待来制造更好的周期数。
4. 两端使用相同工具链和 RNE；主对比可统一关闭隐式 FP contraction，
   再核对标量及向量指令，显式 FMA 不会仅因编译开关自动消失。保留相同
   精度阈值；快测可关闭参考，最终软件方案仍须单独通过数值检查。
5. EVU 对比当前四行驻留基线、批量状态、短归约三类改动，先逐项后组合。
   每个方案单独预热、重置；精简打印，仅给配置、完整 cycles、drain 和状态。
   以包含新增操作的实测总周期选择方案，不要求模仿核内 VL 和调度。

## 6. 测试是否不利于 EVU，以及是否应更换负载

两方面都成立：这份短行、状态密集、依赖较长的测试适合核内 L1 和标量/向量
协同，也暴露 EVU 的小命令成本、访存串行和归约分发问题。它不能展示 CUTE
直通优势，但它本来就是纯向量对比，不能因为 EVU 较慢而删除或更换主用例。

保留原形状作为横向基线，补充等价输入的 rows/cols 扫描，例如更多行以及
512/1024 列；两端都跑相同矩阵。较大输入可能展示长向量摊薄命令成本和容量
复用的价值，但更长归约、缓存层级变化和寄存器压力也可能使 EVU 更慢。
当前较大列数会进入 streaming fallback，需要明确记录；若新增驻留分块，
应核算整个寄存器布局，不能假设四条更长的完整行仍能放入现有 VRF。

另用寄存器内 EXP2/算术、纯 load/store、max/sum 归约等短测试区分计算、
供数和控制开销，再保留完整 online softmax 测量真实端到端依赖。单测吞吐
不能替代算子周期，也不能将 EXP2 当作 FMA 的两 FLOPs 来随意定义 MFU。

最后先统一完成边界与软件口径，再优化小状态、归约和 RTL。当前 difftest
配置要求核与加速器同钟，`rdcycle` 是核的周期计数；不能把这些周期直接
宣称为已实测的 1.5 GHz EVU 对 2.5 GHz RVV 性能。独立频率的时间对比需
另行验证时钟关系、跨域及频率约束。

## 7. 已实现的对齐测试与软件实验

### 7.1 保留主用例与历史测试

新增核内 `vfexp2_softmax_online_matched.c`，调用原四行 LMUL=4、正 scale、
向量 alpha 实现，不修改历史 compare/best 测试及 vendor kernel。EVU 修改
原 `online_softmax_evu_test.c`，最初保留四行驻留、`STATE_BATCH=0`、
`REDUCE_VL=128`。随后按用户要求将 cols=256 的默认候选改为
`STATE_BATCH=1, REDUCE_VL=128`，不更换 32×256 负载；非 256 列默认仍
关闭状态批处理，以保留 streaming fallback。原基线可通过编译参数选择。

两端共同使用 `nexus-am/tests/exp2test/include/online_softmax_bench.h` 的输入、
初始状态及数值阈值；两端都先生成模板，再通过 memcpy 完整重置。默认各测
first=0/1，预热一次，正式计时包含配置、计算、访存及结束同步。EVU 使用
XV_FENCE + CPU fence，RVV 使用 CPU fence。主周期字段为 `c`。默认 CHECK=0
仍是快测，不运行 FP64；CHECK=1 检查预热、正式及诊断调用的数值与边界。

新核内目标及 EVU 目标均使用 `-fno-fast-math -ffp-contract=off`，RNE。
新核内目标的 vendor kernel 也进入独立对象目录，避免复用历史融合乘加对象。
工具链版本仍需服务器使用者两端统一；本地语法检查不能证明服务器命令一致。

### 7.2 可选 EVU 方案

| STATE_BATCH | REDUCE_VL | 含义 |
| --- | --- | --- |
| 0 | 128 | 原四行驻留基线 |
| 1 | 128 | 四行状态批量处理，cols=256 的新默认候选 |
| 0 | 64 / 32 | 短归约实验 |
| 1 | 64 / 32 | 组合实验 |

`EVU_SOFTMAX_STATE_BATCH=1` 在 VL=4 下批量加载旧 max/sum、更新状态及计算
alpha，批量保存三种状态。每行归约结果分散在不同寄存器；现有接口未提供
可直接用于本方案的单元素插入，因此用 E0 scratch store + VL=4 load 打包，
再 gather 提取各行 max。所有打包、提取、scratch 访问及 VL 切换都在计时内。
默认 first=0 的静态 EVU 访存指令从 293 降至 253，配置写从 1 增至 33；
first=1 的访存从 229 增至 237，所以不能预先认定批处理一定更快。
选择它作为下一轮默认候选，是优先减少 first=0 的小状态命令，并批量计算
alpha；短归约需要额外 gather、索引加载和更多配置切换，因此暂不叠加为默认。
这不是实测最优结论，最终仍以相同完成口径的服务器周期和正确性结果决定。
代码宏和 Makefile 默认值已同步；用户无需改源码，原编译命令即可选择该候选。

`EVU_SOFTMAX_REDUCE_VL=64/32` 保留四行完整输入驻留和 VL=128 的逐元素
计算，将两个 chunk 配对后的 128 元素通过 indexed gather 分组并做逐位置
max/sum 累积，再归约短累加器。索引加载、gather、额外加法及配置切换都计时；
sum 的结合顺序变化。短归约当前仅支持 cols=256；批状态支持现有 resident
形状。非默认配置拥有不同对象及镜像路径，服务器要使用对应镜像而非旧默认 bin。

### 7.3 排空诊断与 fence 的源码核对

默认 `EVU_SOFTMAX_DRAIN_DIAG=1` / `RVV_SOFTMAX_DRAIN_DIAG=1`：主计时
之后另重置并运行一次，报告 `pre`（结束同步前）、`full`（结束同步后）、
`drain=full-pre`，另报空闲同步探针 `empty`。主 `c` 内不插入中间 cycle 探针。
诊断增加一次算子运行，但不增加成功打印行数；后续快测可将 DIAG 设为 0。

drain 含读周期及控制开销，中间 rdcycle 还可能让部分存储提前排空；它是
受探针扰动的尾部区间，不是可从主周期直接扣除的纯访存惩罚。两端应按相同
测量模式比较，不能将诊断 full 与无探针的主 c 混在一起求加速比。

已核对当前核内 RTL 源码：

- `backend/decode/DecodeUnit.scala`：FENCE 及普通 CSR 指令带 noSpec/blockBack，
  FENCE 还带 flushPipe；cycle CSR 没有被 VL/VLENB 特殊解码覆盖。
- `backend/dispatch/NewDispatch.scala`：waitForward 指令等待 ROB 清空再进入。
- `backend/fu/Fence.scala`：等待 sbIsEmpty 才进入 fence 完成状态。
- `mem/MemBlock.scala`：向量存储经过 SQ/Sbuffer；fence flush 连接 Sbuffer；
  sbIsEmpty 来自 Sbuffer 和 uncache 的 empty。
- `mem/sbuffer/Sbuffer.scala`：flush.empty 还要求 SQ empty，缓存写响应后
  才清除对应有效项。完成指缓存路径接收/完成，不要求数据写回 DRAM。

这支持新结束同步的源码设计；没有在本地运行 emu 或生成新反汇编，尚不能
宣称服务器镜像已通过动态时序验证。服务器应保留新反汇编，核对最终向量
存储、fence 和 rdcycle 顺序，并结合提交/写响应跟踪确认实际结束边界。

### 7.4 本地验证范围

- `scripts/check_online_softmax_schedule.py`：62 组源码驱动调度检查，覆盖
  六种组合、first=0/1、不同 scale、短行和 stride padding；检查寄存器
  数据、score 仅加载一遍、输出覆盖及越界。使用理想 exp2 和顺序 FP32 sum，
  不替代 RTL/NEMU 检查，也不证明真实无序归约 bit 或周期。
- 36 组 `clang -fsyntax-only` 配置/编译单元检查，包含 checked/fast、
  diagnostic on/off 和核内 kernel；另核对 Makefile 配置与独立镜像路径。
- 未运行 emu、未编译 bin、未生成 Verilog；本次未修改 RTL 或 NEMU。

## 8. 对齐结果与 wave4x2 软件候选

本节是 2026-10-31 后续修改，覆盖第 7 节的默认软件选择。服务器对齐结果：

| first | EVU state=1/red=128 | RVV matched | EVU/RVV |
| --- | ---: | ---: | ---: |
| 0 | 30582 | 11822 | 2.587 |
| 1 | 29847 | 11463 | 2.604 |

主比较使用包含完成同步的 `c`。EVU drain/empty 为 53/52 cycles；核内 drain
为 95/88，empty 为 36/37。不能将探针差值当作精确存储惩罚，但没有证据
将约 1.9 万周期的主要差距归因于结束计时边界。两端均 check=0，未独立
检查数值精度；EVU 有 GOOD TRAP、difftest pending=0，无矩阵 beat。

原实现确实执行 `XV_EXP2`，后端映射到 `VFExp2FU`，DLEN=256 对应四个
64-bit EXP2 实例，每个处理两个 FP32；峰值是每发射拍八个 FP32，而不是
一次物理计算完整 128 元素。日志 exp_issue=6168 也表明指数单元被使用。
加减乘和 sum 仍共享 FMA，比较、EXP2 独立。四行分批不是四套完整计算单元。

### 8.1 取消默认 scratch 打包与内部配置排空

`EvuCSRDefs.scala::needsDrain` 包含 cfg，核内 CSR wrapper 会等待
`csrIn.evu.drained` 才执行相关配置。原 state=1 路径一次内核配置 33 次，
不仅有 CSR 指令开销，还打断未完成工作。现在默认 state=0，用 E0 保留
每行状态和 scalar-source 运算，完整内核仅入口配置一次；无 scratch store/load
打包，无 gather，无内部 VL 切换。first=1 跳过旧状态读取和 alpha 运算。
不是认定逐行状态一定最快，而是先消除当前 ISA 下昂贵的打包/排空方案。
源码指令统计（不是周期或物理总线流量）如下：

| 路径 | first=0 访存指令 | first=1 访存指令 | 配置写 | gather |
| --- | ---: | ---: | ---: | ---: |
| 原 state=1 | 253 | 237 | 33 | 32 |
| 新 wave4x2/state=0 | 293 | 229 | 1 | 0 |

first=0 总访存指令反而增加 40 条：取消打包不能同时保留批量状态访问的
所有收益。alpha 从八条 VL=4 EXP2 变成 32 条 EXP2_E0，有效指数元素数
仍为 32。选择依据是取消内部排空并提前提供独立工作，不是所有计数都下降；
这一取舍必须由服务器完整 `c` 测量，而不能预先承诺加速。

### 8.2 四行计算、后四行预取、两行波前推进

默认新调度为 `sched=wave4x2`，配置 `EVU_SOFTMAX_PIPELINE=1`：

- v0..v7 / v8..v15 交替保存两个四行输入批次，最多 8 KiB 输入驻留，
  每个 score 只读一次、写一次。不在计时外预装任何输入或常量。
- v16..v19 按生命周期复用为 max scratch、max、指数 bias、sum scratch、sum；
  v20..v23 保存旧 max、alpha、单独舍入的 alpha*old_sum；v24/v25 为旧 sum
  临时值，v27..v31 为常量，v26 空闲。共使用 31 个架构寄存器名字，
  不表示每个寄存器所有字节都有效，也不要求填满 VRF。
- 前两行 max 归约之后推进其状态和指数，不先连续提交四条 max 归约；
  第二对行的 max 归约与第一对 EXP2/状态工作交错。头 chunk EXP2 先于
  尾 chunk 减法提交，给独立 EXP2/FMA 路径创造重叠机会。
- 在当前行工作间插入下一批输入 load；双缓冲不与当前输出寄存器冲突，
  同一物理 bank 仍由 RTL 保持 1RW 仲裁。VMU 跨命令串行限制仍存在，
  预取也可能阻塞后续命令；软件次序不等于已证实实际重叠。
- 保留原逐元素乘、减、EXP2及配对求和/归约语义，old_sum*alpha 后加新 sum
  仍分开舍入。输入、初态、精度阈值、预热/重置和完成边界不改。

新默认是下一轮优先测量的候选，不能在未运行服务器前宣称实测最优。
旧软件通过 PIPELINE=0 保留；STATE_BATCH=1 或 REDUCE_VL=64/32 会默认
关闭新波前调度，保留旧实验。Makefile 和 C 的默认选择一致，默认 bin 名称
仍为 online_softmax_evu_test-riscv64-xs.bin；不同配置拥有独立对象/镜像路径。

### 8.3 L1 与 HBL2 的作用

这确实可能是主要原因之一，但现有日志不能定量分摊差距。CPU 重置后
scores/状态已访问过，核内可经 L1 重读；EVU 即使少读一遍 scores，仍需
HBL2 往返，尤其单 FP32 状态和 scratch 访问很难摊薄每条命令启动延迟。
HBL2 访问不等于每次访问 DRAM；是否 L1 命中以及一致性响应的成本均需测量。

后续 RTL 优先增加外层 memoryCanStart 阻塞、每条访存延迟、按负载区间
差分的计数，再评估多 VMU 命令上下文、短状态访问及归约/独立工作绕阻塞。
不能仅凭 memory_order_stall=0 或全程序 FU no-work 推断无访存瓶颈或 MFU。

### 8.4 新调度的源级验证

- 122 组源码驱动检查覆盖旧配置及新波前、4/8/12/32/36 行、两种 stride、
  三种 scale、first=0/1；新波前同时与旧 state=0 的源码解释结果逐 FP32 bit
  比较，检查寄存器复用、加载/输出覆盖和尾批不越界。
- 默认路径确认仅一次配置、无 scratch/gather、每个 score 恰好一次加载和
  一次存储、每行一次 max 和 sum 归约，EXP2 指令存在且数量正确。
- 另通过 12 组 fast/checked、first、diagnostic 组合的严格语法检查，
  并查询 Makefile 默认和旧实验选择；未编译 bin、运行 emu 或生成 Verilog。
- 解释器使用理想 EXP2 与顺序 FP32 sum，不证明真实 RTL 的近似 EXP2、
  无序归约 bit、并发正确性或周期；仍需服务器 difftest/精度检查。

## 9. 普通 VMU 连续性与小状态优化实现（2026-10-31）

本节更新第 4、8 节中描述的旧限制；历史测量不变。以下为已落地的实现，
不是已测得的周期收益，也不能据此宣称已解决全部 HBL2 或归约瓶颈。

### 9.1 两条普通访存命令的有界窗口

- `EvuParams.memoryCommandDepth` 默认 2，可配置 1..4。scoreboard 按 job
  记录仍未完成的访存，不再用一个 memoryLive 阻止所有后继访存。
- 删除外层“上一条 VMU 缓存行必须全部排空”的入口条件，但不删除依赖检查。
  普通 Saturn 指令保留 EG RAW/WAR/WAW；special/CUTE 仍有整寄存器保护。
  VMU 仍按地址处理 load/store 冲突，store 请求按程序顺序生成。
- page map、虚拟基址、首个错误地址/原因分别按 VAT 保存，请求 tag 保存
  原 VA、VAT 和本地 tag。物理地址在缓存行合并之前生成，避免不同命令的
  翻译或异常状态互相覆盖。metadata context 在对外完成之前不得复用。
  最后一个 store Ack 与 completion 同拍时，错误通过当拍旁路进入完成结果，
  不能等待 sticky 寄存器下一拍更新后才报告。
- 物理单位步长用完整地址范围判断依赖，包含跨页进位；strided 和启用
  page map 的访问对相反类型访存保守排序，防止虚拟别名绕过保护。因此
  “配置深度为 2”不等于任意两条命令都一定重叠。
- 核内 preflight 只解除对旧普通访存的等待，控制、状态发布及配置屏障仍
  保留。软件的 CPU/CUTE/EVU 共享数据所有权与同步要求不变。

### 9.2 写数据锁存、跨命令合并和真实 Ack

StoreSequencer/VMU 原有数据队列先安全取走 VRF 源数据；EG 读占用按其
原有时机释放，后继写可以继续。对外 Store 完成仍等 VMU 最后一个物理
Ack，不能以“数据进入队列”冒充存储完成。新增回归专门覆盖取走 store
数据后覆盖源寄存器、以及同地址 load 必须等待旧 store 的情况。

`EvuVmuLineCoalescer` 允许不同 job、同 epoch、同物理缓存行的**不重叠字节**
store 合并；所有逻辑 tag 都保留归属，收到一次物理响应后向各 owner 回传。
重叠字节即使数值相同也不合并，避免丢失可验证的先后写入语义。跨行请求
连第二条缓存行一起检查冲突。满行直接提交；部分行在相关冲突、缓冲压力、
drain 或 31 周期有界等待后主动提交，不能无限等“凑满”。这是收集合并等待
上限，不是总请求延迟上限。

FENCE/drain 等待全部 owner、adapter、router 和物理事务排空；flush 撤销
未发送工作，已发送事务继续回收响应和 tag，旧 epoch 的响应不能污染新
命令错误状态。每个物理 VRF bank 仍是 1RW，没有扩大容量或新增 SRAM 端口。
部分行仍走非原子的外部 read-modify-write，CPU/CUTE 不得无同步并发修改
同一缓存行。跨命令 load 缓存复用没有开启，以免复用 CPU/CUTE 写入前旧值。

物理 TL 总线继续使用 PutFullData；difftest store event 的 mask 则保留
实际逻辑写字节，NEMU 按每个字节最早未观察到的 store 归属核对整行数据和
保护字节。这不是把物理总线改成 PutPartial。参考模型还会向年轻 load
转发旧 pending store，并用可增长环形队列替代固定 64 项队列，避免 CPU
退休领先 EVU 完成时再次触发参考队列溢出；没有修改 guest 的周期或完成条件。

### 9.3 软件候选及其完整成本

保留默认 `wave4x2/state=0/red=128/loads4=0`，不为减少小请求而默认新增排空。
新增 `EVU_SOFTMAX_STATE_LOAD_BATCH=1`：每四行先 VL=4 加载连续 old max/sum，
通过 gather 分配到各行独立寄存器，再恢复 VL=128。old sum 暂借尚未预取的
输入 bank，每一对行消耗它以后才允许对应预取覆盖。没有 scratch 打包访存，
但所有 gather、配置切换仍在内核计时内。

| 默认 32×256 源级统计 | first=0 访存指令 | first=1 访存指令 | first=0 配置写 | first=0 gather |
| --- | ---: | ---: | ---: | ---: |
| loads4=0，保留默认 | 293 | 229 | 1 | 0 |
| loads4=1，批量加载候选 | 245 | 229 | 17 | 64 |

状态输出仍使用 E0 store，不假设存在免费的跨寄存器打包；相邻无冲突写由
新硬件合并器尝试合并。硬件两命令窗口不保证两个状态 store 一定及时相遇，
必须看实际 merge、Put、partial 和周期。四行整状态运算/短归约旧配置仍可选。

### 9.4 区间计数和服务器验证

新增命令/tag 占用周期与峰值、物理 Get/Put 次数、普通 VMU 部分写、缓冲满、
Ack 等待、访存入口阻塞计数，并保留原窗口阻塞/合并计数。命令周期、tag
周期是“每拍在途数量之和”；Ack 等待只表示存在等待，不等于完全无法计算；
入口阻塞包含多种反压，不能当成纯 SRAM 或纯窗口阻塞。不同计数可重叠，
禁止相加当成周期分解。Get/Put 包含共享矩阵路径，纯 softmax 中没有 CUTE。

启用 difftest RTL 后，在 EMU 命令**所有选项末尾**附加：

```sh
-- +evu_mem_trace=1
```

每条已完成 XV_FENCE 打印一行 `[EVU-VMU]` 累计快照；按预热、重置同步、
正式结束同步的执行顺序识别内核前后快照并做差。`[OS]` 新增 `t=start:end`
报告原始 rdcycle 边界；它与 RTL `t` 的初始偏移不同，不能直接按绝对值匹配。
诊断调用也会产生快照，不能混入正式调用。区间还包含边界处少量控制开销，
不是原子采样的周期分解。峰值是自复位/flush 后累计峰值，不支持直接相减；
发生 flush 后计数也会清零，不能跨清零做差。该功能默认关闭，不增加 guest
CSR/ISA；目前并未实现区间 L1/L2 hit/miss、probe 或所有入口子原因计数。

先重编 RTL/EMU、NEMU reference 和测试，再按同一输入/完成边界比较：

1. 运行新 scoreboard/coalescer/adapter 回归，以及 `EvuMemoryWindowSpec` 的
   双命令翻译、乱序错误返回、源寄存器复用、同地址排序和 flush 测试；慢速
   全后端测试需设置 `EVU_LONG_TREADLE=1`。NEMU 原生回归也新增环形扩容和
   合并 store 归属、错误数据拒绝、相同值重复写案例。
2. 先跑 `vector_memory_path_evu_test` 的 store/copy/state4/state16/state64，
   建议启用 transport check；若完整测试触及周期上限，按 case/group 分开。
3. `online_softmax_evu_test` 先 CHECK=1 通过，再 CHECK=0 比较 loads4=0/1；
   非默认配置镜像带后缀，以 Makefile 打印的实际文件名为准。
4. 再跑 FlashAttention warm 正确性和 cold 周期，检查精度阈值、GOOD TRAP、
   difftest 无 mismatch 和末尾 pending=0，不能只看到某行周期输出就算通过。

本地已通过 Scala 主源码及回归源码编译、NEMU C 严格语法检查、182 组软件
源码调度解释检查（理想 EXP2、顺序 FP32 sum，wavefront 与旧调度逐 bit
对比）。按现有约束未执行 RTL 仿真、emu、guest bin 编译或 Verilog 生成。
因此硬件回归实际通过、真实 EXP2/归约精度和加速效果仍待服务器验证。

## 10. 部分写等待与 CPU 并发入口修正（2026-11-01）

### 10.1 新日志说明的两个独立问题

本轮修正前的 `evu_softmax.log` 为 first=0/1 的 35722/31035 cycles，
`vmu_command_peak=1`、`vmu_partial_writes=612`、`ack_wait=68910`，末尾
GOOD TRAP、difftest pending=0。命令窗口虽然配置为 2，实际峰值仍是 1。
这些累计计数不能相加，也不能把全部 Ack 等待认定为不可重叠的损失。

重点验收 warm 回退：first=1 的 warmup 记录由 28440 升至 31139 cycles，
正式 `c` 由 28405 升至 31035。first=0 的 warmup 则由 32676 升至 35805，
正式 `c` 由 32663 升至 35722。这些是前后日志记录，不代替保留同一 bin
校验和的严格对照。本轮保持软件、输入、预热和完成边界，优先修复新增的
等待机制，以新 EMU 的相同 warm 用例重新验收，不继续处理 checkpoint 构建。

原 coalescer 在深度大于 1 时不按命令结束排出部分行，可能额外等待约
31 拍。默认每个内核有 96 次 E0 状态存储，96×31=2976 cycles，与本轮
约 2600–3100 cycles 的回退量级相符；这是待验证的机制判断，不是精确归因。

同时用户报告：同一 checkpoint EMU 和同一 bin 在没有 CI 的服务器上
启动打印恢复快速。因此，先前极长的宿主耗时和慢速打印主要应从服务器
资源竞争排查，不能据此宣称新 RTL 出现死锁或面积成倍增加。内核 guest
cycles 的回退仍须独立验证。

### 10.2 按实际请求推进决定收集窗口

- 移除 coalescer 的全局 start/end 状态和按配置深度决定等待的条件。
  VMU 提供 `storePending`：存在有 Ack 容量的已缓存后继地址，或当前地址
  生成器确实可以推进，才保留收集机会。没有后继时，部分行可在接收后
  下一拍发出，不固定等 31 拍；后级总线反压仍可能延迟真正的握手。
- 已出现不能合并的有效请求、相关冲突、容量压力、drain、满行均触发
  排出。保留超时作为真实后继数据被延迟时的进展保护；在短 VRF 供数
  间隔中仍可合并同一命令的两个半行。无效请求上的残留地址不参与排出判断。
- 不提前发布逻辑完成：所有合并 owner 仍等物理 Ack，flush 继续回收
  已发出事务。同 job load 复用范围、地址与 epoch 冲突保护保持原语义。

### 10.3 保留译码屏障，分离标量退休与外部完成

普通 load/store 及 E0 形式继续具有 `noSpec` 和 `blockBack`。新增
`RobEnqueueBarrier` 保存当前后向屏障类型：普通 EVU 访存在标量 ROB
清空后允许下一条指令进入，不再额外等待旧访存的最终物理 Ack。
WAIT/FENCE、配置及其他后向屏障仍等 EVU 完成和状态传播后再释放。
这使下一条非投机 preflight 可以与旧命令外部执行重叠，后端仍限制最多
两条普通访存；并不保证每次软件运行都能填满窗口。

同步增加以下保护，避免只放松入口却破坏地址空间与异常语义：

- CPU FENCE、SFENCE/HFENCE、FENCE.I 在原 store-buffer 完成条件之外，
  也等待 AMU 中尚未完成的已退休 EVU 命令和状态排空；沿用原 fence 状态机。
- satp/vsatp/hgatp、权限/状态、环境控制、PMP 和相关 EVU 配置沿用 drain
  认证；补齐 VSstatus/Hstatus、环境配置、mseccfg 和 DRET 的保护。
- ROB 的中断、同步异常和 flush-pipe 发布等待**已退休** EVU 工作及状态
  排空。新增 AMU `evuCommittedBusy` 包含已退休但尚未下发的命令，排除
  未退休的故障指令，避免 preflight fault 等待自身外部完成造成死锁。
- 每命令 page map、错误及 tag/epoch 所有权、同地址依赖和 EG RAW/WAR/WAW
  保持原保护；CPU redirect 不撤销已退休 EVU 命令。VRF 容量及每 bank
  的物理 1RW 端口不变，本轮没有更改软件算法或 guest 指令编码。

### 10.4 验证与下一轮观测

本轮使用定向 Treadle 测试和 Scala 编译，不在本地运行整机 EMU、编译
guest bin 或生成 Verilog。服务器重建 EMU 后，仍需先通过 add、异常/
配置/FENCE 回归，再运行同一个 softmax bin 和 FlashAttention 正确性测试。
对照正式内核 cycles、实际命令峰值、store merge、Put/partial 及区间计数；
不能以打印恢复速度或配置深度代替功能、精度及实际并发的验证。
新 RTL 配已经修复参考队列扩容、pending store 转发及合并写核对的
NEMU_evu；原 checkpoint NEMU 保留给 checkpoint 组合对照，本轮未再修改
NEMU 源码。软件 bin 可以复用，重建的是 RTL/EMU 和所需的参考库。

新增性能回归按两条命令窗口连续发送 96 条孤立 FP32 状态写；每条逻辑
写都等物理 Ack，检查接收后的收集空等为 0，不再因窗口深度多等 31 拍。
该微回归只验证收集器机制，不模拟真实 HBL2 延迟或完整 softmax；整机
warm 是否恢复约 2.8 万 cycles 必须以新日志为准。

本轮验证结果：Scala 主源码及定向测试源码编译通过，30 项 Treadle 回归
通过（coalescer 12、scoreboard 4、地址预检 5、物理写完成 2、EVU 控制
屏障 1、AMU 完成 4、ROB 屏障/译码 2），`git diff --check` 通过。
地址预检的非法超长输入测试改为现有最大 LMUL 范围 `8*vlenBytes+1`，
替代已属于合法长度的固定 513 B；没有修改地址预检的生产限制。
未运行完整后端慢测及整机回归，不能将上述单元测试当作整机周期或精度结果。
