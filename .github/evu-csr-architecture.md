# EVU CSR ABI 1：独立状态、软件接口与验收

本轮已修改 RTL、NEMU、Difftest 和软件接口。目标配置为
`DefaultExternalVectorMatrixConfig`，RV64、EVU VLEN=4096、DLEN=256。
本地已完成源码编译及主机参考模型回归；尚未执行修改后的 RTL 仿真、综合或
性能测量。用户要求本地不运行 EMU、不生成 Verilog、不生成 guest bin。

## 状态归属

EVU 独立维护配置、VRF、量化 descriptor、浮点异常、饱和标志和 ES。
EVU completion 不再写核心 FCSR/fflags、vxsat、FS/VS，也不再通过 vsDirty
清核心 vstart。标准标量浮点和核内 RVV 原有的共享关系保持。

首版舍入固定 RNE，起点固定零，不新增可写 efrm/evxrm/evstart，也不提供
元素级故障重启。量化舍入由 descriptor 的算术右移定义。Saturn 的归约拓扑、
Exp2 近似算法保持；CSR 隔离不代替数值精度验证。

地址空间和访问权限仍属于同一 hart：satp/vsatp/hgatp、PMP/PMA、MPRV、
SUM/MXR、特权级和 trap 入口共享。所有权或映射改变前排空在途 EVU 工作，
不复制一套 EVU satp。CUTE 的配置、矩阵寄存器和状态仍由其自身接口管理；
EVU VRF 上下文 helper 不负责保存 CUTE 上下文。

## 冻结的 18 个地址

所有 CSR 都是 RV64 的 64 位软件视图，未实现位读零。
原先草案的部分地址与 AME/Mbmc 冲突，已替换为下表；软件必须使用新头文件。

| 名称 | 地址 | 访问 | 有效字段 |
| --- | --- | --- | --- |
| evcfg | 0x8c0 | RW | AVL/VL[31:0]、VTYPE[39:32]、configured[40]、VILL[63] |
| efflags | 0x8c1 | RW | [4:0] NV、DZ、OF、UF、NX，采用标准 fflags 位序 |
| evxsat | 0x8c2 | RW | [0] 累计饱和 |
| eqdesc0..7 | 0x8d0..0x8d7 | RW | 每个 descriptor 51 位 |
| mevstate | 0xbc8 | M-mode RW | ES[1:0]、UEnable[2]、SEnable[3]；写 ACK[8] |
| evlenb | 0xcd0 | RO | 当前为 512 字节，由硬件参数产生 |
| evcap | 0xcd1 | RO | ABI[7:0]=1、descriptor 数[15:8]=8、SEW 能力[18:16]=7、固定 RNE[24]=1 |
| evstatus | 0xcd2 | RO | Busy[0]、ErrorValid[1]、TvalValid[2] |
| evcause | 0xcd3 | RO | [3:0] 首条未确认异步错误的原因 |
| evepc | 0xcd4 | RO | 原始出错命令的 64 位 PC |
| evtval | 0xcd5 | RO | 64 位故障地址；TvalValid=0 时读零 |

地址视图总计 18×64=1152 位；这是软件可见宽度总和，不能据此估计实际
寄存器面积。evcap/evlenb 是常量，其他 CSR 仅保存有效字段，另有命令元数据。

### evcfg

写入的 AVL 截为 32 位，读取返回实际 VL=min(AVL,VLMAX)。AVL=0 表示空操作，
UINT32_MAX 请求 VLMAX。VTYPE 低 8 位采用标准 RVV 编码：LMUL[2:0]、
SEW[5:3]、VTA[6]、VMA[7]。支持 e8/e16/e32 和 m1/m2/m4/m8、mf2/mf4/mf8；
非法 SEW、保留 LMUL=4、显式 VILL 写入使 VILL=1、VL=0、低 VTYPE=0。

configured=0 规范化为 legacy 模式（存储 VL=128、VTYPE=e32,m1）；此时各
指令使用自身的 SEW 和单寄存器最大长度。configured=1 时计算/访存使用
配置的长度与类型，SETCFG 的输出 SEW 保持由指令定义。

保留 SETVL/SETVLI，旧指令的 LMUL 符号/幅度转换为标准 VTYPE 编码。
它们更新同一份 CSR bank；不产生标量 rd 返回值。软件推荐使用 xv_setvl。
浮点只支持 e16/e32，宽化只支持 e8→e16、e16→e32，宽化后的寄存器组不能
超过 8 个寄存器。寄存器组必须对齐且不越界。归约的种子/结果为单寄存器。

QuantToCute 当前的 CUTE 流只覆盖一个物理 VRF 寄存器，因此首版拒绝
LMUL>1 的 QuantToCute；允许 m1 和分数 LMUL。否则旧分块计数器会回绕。
普通算术和 LOAD/STORE 保留 m2/m4/m8。跨 Saturn、special、矩阵参数读取的
hazard 掩码已按寄存器组扩展。

### descriptor

| 字段 | 位 |
| --- | --- |
| scale VRF index | [4:0] |
| bias VRF index | [9:5] |
| 有符号 zero | [41:10]，32 位补码 |
| 算术右移 shift | [47:42] |
| 输出 SEW | [49:48]，0/1/2 对应 8/16/32 位饱和界限 |
| valid | [50] |

非法 output SEW=3 的 CSR 写入规范化为零 descriptor。SETCFG 更新相同布局。
命令携带完整 descriptor 快照，执行中不跨时钟查询可变 CSR。

### enable、Dirty 和权限

ES：0 Off、1 Initial、2 Clean、3 Dirty。复位为 Off；写 Off 不清除 VRF 或
其他状态，只禁止使用。重新初始化数据是软件责任。写配置、flags、descriptor
和会修改上下文的命令保守置 Dirty；错误也置 Dirty。mevstate 是 ES 的唯一
软件配置源；mstatus/sstatus.XS 反映 ES，SD 包含 XS==Dirty，FS/VS 独立。

mevstate 仅 M-mode 可访问；其他计算/状态 CSR 及 EVU 指令要求 ES!=Off。
M-mode 无需 U/S 授权；S 要求 SEnable 和 mstateen0.C，U 要求 UEnable、
mstateen0.C、sstateen0.C。guest 首版不支持。能力常量查询不要求 ES 启用；
CSR 编码及核心通用 stateen 权限检查仍适用。非法访问产生正常 illegal trap。

## 顺序与完成协议

`NewCSR/EvuCSR.scala` 为权威状态 bank，Backend 预检查与命令快照读取同源
配置。配置变更、flags/descriptor/错误记录访问先排空；普通算术不增加逐条
屏障。完成事件携带本条 fflags/vxsat，按程序顺序累积到独立状态。

排空使用独立请求/响应通道，不需要先退休一条等待自身完成的 AMU 命令。
首版只允许一个在途 drain，队列保持顺序；未实现显式 generation 编号。
CDC 用双向 AsyncQueue，要求控制和 EVU 队列随同一复位域一起复位，不支持
仅复位一个端点并保留旧响应。共享队列/流水线复位不能用于恢复部分写副作用。

ACK 仅在命令、矩阵流、物理写响应和状态发布结束后返回。核心保存排空确认，
连续本地 CSR 访问复用确认；新退休命令/在途工作使确认失效。FENCE 的完成
可在状态发布结束后提供同样确认，避免随后读 flags 再跨域往返。

**当前 evstatus/evcause/evepc/evtval 的读取也排空，软件读 evstatus 会得到
Busy=0；它不是非阻塞轮询接口。** evlenb/evcap 不主动发 drain，但仍受核心
CSR 调度约束。当前 mstatus/sstatus 读写也保守排空；satp/vsatp/hgatp、
PMP/PMA 访问和 mret/sret/mnret 经过同一所有权边界。一般中断也等待 EVU
busy 消退。首版不支持多个地址空间同时提交。

WAIT 只保证目标命令约定的完成，不替代矩阵写排空；矩阵后处理 flags 在
FENCE 或独立 drain 发布，并在执行侧消费一次。软件清 efflags 后，旧矩阵
flags 不会在下一次 drain 重现。

## 错误协议与边界

退休前可检查的权限、VILL、类型、寄存器组、descriptor、地址映射错误走核心
同步 trap。退休后的总线/执行错误保存第一条未处理错误；后续错误不覆盖。
普通浮点 NX/NV 等进入 efflags，不属于这里的异步错误。

普通向量错误保留命令 PC 和有效地址；矩阵错误保留原始 LAUNCH PC、原因和
失败 beat 地址，不把 FENCE PC 当作来源。访存单一 owner 和 job 关联避免
全局 sticky 错误归到其他命令。矩阵写失败后停止后续写出，同时消费有限结果
流至末尾以允许排空；这个故障路径仍需服务器定向验证。

M-mode 先排空、读取诊断，然后写 mevstate 的 bit8 确认错误，保留所需 ES/
授权低四位。ACK 只清错误记录，不清 flags，不撤销已完成的 VRF/内存/CUTE
写入，不复位或自动重跑。支持的恢复是软件处置已知有限任务、确认错误后
重新初始化/恢复上下文。无法终止的 CUTE 流、丢失总线响应或端点独立复位没有
软件 abort 协议，属于本版限制，不能承诺任意故障可恢复。

NEMU 不根据 DUT 的错误或结果自证正确。普通 LOAD/STORE 有独立故障注入
oracle `difftest_evu_inject_fault`；未注入的物理总线失败仍使 Difftest 报错。
矩阵外部总线故障尚无对应参考注入 oracle，不能声称这类故障已通过端到端
Difftest。正常矩阵计算、数据/flags、写保护与排空有主机回归。

## 软件配置和上下文

使用 `software/evu/include/evu_csr.h`：

```c
xv_enable_m(XV_ALLOW_S);       // M-mode；需要时另开 mstateen0.C
xv_setvl(128, 0x10);          // e32,m1；不改核心 vtype/vl
XV_CSR_WRITE(XV_EFFLAGS, 0);
XV_CSR_WRITE(XV_EVXSAT, 0);
// XV_LOAD / XV_FMUL / XV_FREDUCE_SUM / ...
XV_FENCE();
uint64_t flags = XV_CSR_READ(XV_EFFLAGS);
```

现有五个 EVU workload 启动入口已开启 mevstate；S-mode 虚拟内存测试同时
开启 mstateen0.C。旧 guest bin 必须重编，否则复位 ES Off 会触发 illegal。

`xv_context_save/restore` 面向 M-mode 和 VLEN=4096，保存完整 32×512=16 KiB
VRF，加配置、flags、饱和、八个 descriptor 和管理状态。helper 临时使用
e8,m1,VL512，逐寄存器搬运后恢复配置；不能仅保存当前 VL 的活动元素。
返回 -1 表示上下文关闭/能力不符，-2 表示需要处理错误，不能使用不完整镜像。
错误记录仅复制供诊断，RO 诊断寄存器不会被恢复。调度器还须管理 CUTE 上下文。

NEMU csrcpy 已覆盖 EVU CSR，不能据此推断旧 checkpoint 格式支持完整 EVU。
旧 checkpoint 未序列化 EVU VRF/在途任务，已对启用上下文加断言；Difftest
旧 replay 状态恢复同样在存在 EVU 状态/任务时拒绝，不静默丢失上下文。

## Difftest

EVU CSR snapshot 扩为 21×64 位：VL、完整 VTYPE、固定零 vstart、VLENB、
configured、efflags、evxsat、mevstate、status（排除 Busy）、cause、PC、
tval、8 个 descriptor、打包 evcfg。权威 CSR 快照从 NewCSR 输出，VRF 快照
仍来自 Saturn。FP 核心状态不再依赖 EVU completion；核心 CSR 比较覆盖 XS。

增加 completion vxsat 和 drain 事件，参考端要求 v2 completion/drain ABI。
checker 在标量执行前处理物理写/completion/drain，标量提交后比较 CSR；
只有 completion/drain 的周期也推进检查。CSR bank 事件延迟与原有
DiffInstrCommit(delay=3)/completion 对齐。不能混用旧 RTL、旧 Difftest
生成头和新 NEMU。现有完整 Difftest 仍要求 EVU 同频；CDC 控制需单独验收。

## 性能影响

没有修改算术算法或增加算术流水级，不要求每条运算后读 CSR；完成 flags
通过原 completion 返回。开销主要是控制边界保守排空、CSR 调度、命令快照/
队列加宽和任务切换。有效确认/FENCE 复用减少重复往返，但没有测得周期数据，
不能承诺零性能损失。保存加恢复的 VRF 流量合计 32 KiB。

服务器应同参数对比固定配置 softmax 核函数周期，以及逐块改 VL/descriptor、
频繁读 flags 的控制密集情形。初始化、打印、标量精度 checker 单独计时；
频率、面积和功耗需综合测量，不从主机参考模型运行时间推断。

## 已完成的本地检查

- `make comp`：最终主/测试 Scala 源码编译通过；未进行 elaboration。
- NEMU `make -j8 CCACHE=`：参考动态库编译通过。
- 主机计算回归：3 宽度×3 轮 vector-add、896 FP16/FP32 标志隔离、整数/
  量化/归约、CSR 真指令和首次错误/ACK、CSR csrcpy 检查通过。
- m8 e8/e16/e32：4096 字节跨页、8 个 VRF、物理 RMW 首尾保护和非法组检查通过。
- online softmax：7×512 元素通过，最大相对 exp 误差 1.64186475e-6，
  sum 8.7303498e-8，输出 1.64186611e-6，max|sum(p)-1|=6.59646116e-8。
- 既有 RTL trace 的重放通过：每种标量历史 flags 690 条指令、112 次 store、
  896 物理 beat；这是旧 trace 验证，不是新 CSR RTL 仿真结果。
- 矩阵主机回归：3 次 launch、6144 result beat、6144 RMW store，检查 FENCE/
  独立 drain、提前完成拒绝、一次发布和清标志后不重复发布。
- MMU 主机回归：跨页、重映射、fault/retry、9 次物理 store。
- guest 源码仅做 `-fsyntax-only`；C++ checker 使用 /tmp 中临时 ABI 更新头做
  `-fsyntax-only`。后者不是实际 Difftest 生成布局或链接/运行验收。

## 服务器验收（本地未执行）

从匹配的三个 checkout 更新后，在服务器重新构建参考、RTL 和 guest：

```sh
cd NEMU
make git_commit= riscv64-xs-evu-ref_defconfig
make -j8 CCACHE= git_commit=
cd ../XSAI_external_vector
make emu -j16 EMU_THREADS=8 CONFIG=DefaultExternalVectorMatrixConfig
make -C software/evu AM_HOME="$PWD/../nexus-am" git_commit= ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=csr_context_evu_test
make -C software/evu AM_HOME="$PWD/../nexus-am" git_commit= ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=online_softmax_evu_test
```

使用服务器生成的实际 bin 路径运行 `build/emu -i <bin> --diff
../NEMU/build/riscv64-nemu-interpreter-so`。至少运行 csr_context、softmax、
vector_add、exp2、quanti8mma、virtual_memory；要求正常 GOOD TRAP、无
mismatch，精度输出符合测试阈值。新增 csr_context 检查核心 FS/VS Off、
核心 FRM=RDN/vstart37 时 EVU 固定 RNE、独立 flags 和完整 32 VRF 保存恢复。

另外定向验证在途配置/flags 清除、SEW/LMUL 组冲突、用户/监督权限、矩阵写
失败来源、总线背压、排空/FENCE 确认复用和 CDC 共复位；记录实际核函数周期。
完整 Linux/长时间回归和新 RTL Difftest 尚待服务器执行，不计入已通过结果。
