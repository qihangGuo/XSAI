# EVU VRF 组织与元素宽度

## 存储组织

`DefaultExternalVectorMatrixConfig` 使用 Saturn 后端的共享 VRF。默认
VLEN=4096、DLEN=256，32 个向量寄存器共 16 KiB；每个寄存器分成 16 个
256 位 element group（`eg`）。每个 `eg` 拆为 4 个 64 位 lane 切片，
每个 lane 有 8 个 bank，共 32 个同步单端口 `SRAMTemplate` 实例，每个为
64 行 × 64 位，使用 8 位逐字节写使能。

完整 `eg` 的低 3 位选 bank，其余位选行。4 个 lane 对同一逻辑请求使用
相同 bank 和行号，并行访问后拼成 256 位。因而这是按 8 个 bank 编号
调度的逻辑数据通路，不能把 32 个存储对象理解成 32 个任意地址的
256 位访问端口。

源码逐行说明见 [RegisterFile.scala 讲解](evu-register-file-walkthrough.md)。

## 读请求与写回

- 各读接口组先通过 `RegisterReadXbar` 按 bank 路由并仲裁；每个 bank
  再在不同组的候选之间仲裁。普通数据读和掩码读访问同一份存储。
- `OldestRRArbiter` 优先选择带 `oldest` 标记的请求；多个标记请求按较小
  输入编号选择，没有标记请求时轮询。本模块不重新比较全局指令年龄。
- 第二级可见的同 bank、同地址候选可以合并，一次读取供多个请求者
  使用；同 bank 不同地址必须等待。组内尚未通过第一级的请求不参与
  这次合并。因此“一个物理读地址”可以对应多个合法的读握手。
- Saturn 执行序列器、特殊指令及矩阵参数读取分别收集已接受的操作数。
  未获准的请求等待，已收集的操作数不重复申请。前序写回尚未可见时，
  不能提前收集依赖数据；gather 索引也需先有效。
- 流水线写回没有 `ready`，通过 `PipeScheduler` 提前预约未来的 bank
  使用时刻；到达时优先处理。同 bank 多个流水线写同时到达会触发断言，
  不会由断言自动排队。
- LL 写使用 `valid/ready`，每个 bank 先进入单项缓冲，后续取得写机会。
  该缓冲切断 load 掩码读取与写接收之间的组合依赖。进入缓冲不等于
  已经写入存储。同 bank 优先级为流水线写、已缓冲 LL 写、读。
- Saturn 是 EVU 唯一的执行后端。控制路径不再保留独立的架构 VRF
  侧带或架构写队列；所有普通和特殊向量状态都通过 Saturn VRF 访问。

物理存储使用 `utility/src/main/scala/utility/sram/SRAMTemplate.scala`，
参数为 `UInt(8.W)`、`way=8`、`singlePort=true`，`useBitmask=false`，
默认读延迟 1 拍。8 个 way 表示同一行的 8 个字节，不是额外的 bank。底层
`SramProto.scala` 中的 `SramArray1P` 通过 `SyncReadMem.readWrite` 共用
一个读写端口。写入使用原生字节写使能，不组合读取旧值进行合并。

为兼容 Saturn，`VectorWrite.mask` 仍保留每数据 bit 一位的接口；VRF 要求每组
8 位全零或全一，转换成字节使能后写 SRAM。非整字节掩码会触发仿真断言，
不是合法的合成输入。当前 EVU custom 指令子集满足这个约束；Saturn 内部保留的
比较生成 mask、mask 逻辑等指令不能据此直接开放，需要先增加写前旧值合并或
多周期读改写及相关调度。普通数据的 v0 predication 可以展开成字节使能，
不因这次改动而要求位级 SRAM。

内部 `SramReadIO` 的 `req.fire` 表示物理读被接受，下一拍的 `resp.valid`
表示数据返回。两级路由保存获准的 bank 和接收者，同地址合并的请求者
可以同时得到响应。某 bank 本拍写入时不能接受新读；上拍读的响应仍可
在本拍返回，因为它不占本拍的存储访问端口。

对外保留 Saturn 的 `VectorReadIO`：调用方仍在 `req.fire` 当拍取数据。
每个逻辑读端的 `RegisterReadCollector` 先发出内部请求，等相应数据返回
后再向调用方拉高 `ready`。每端最多一个在途读，持续请求时最高每两拍
完成一次；物理 bank 自身仍可每拍接受一次读。请求撤回、地址改变会丢弃
旧响应，复位会清除在途状态。前端 index/mask 和 load mask 的请求在
依赖未解除时不得提前发出，VRF 初始化期间也禁止发读请求。

这完成了同步 1RW 的 RTL 接口与时序适配，不等于已经绑定某工艺的 SRAM
硬宏。实际宏必须支持每 64 位数据的 8 位字节写使能和一拍读延迟；只有全字写
使能的宏不能直接替换，需要额外适配。调试快照仍是可选寄存器影子副本，
按实际字节使能更新。

## 支持的元素宽度

- 普通整数元素支持 8/16/32 位，浮点支持 FP16/FP32。
- 译码、vtype 配置及后端入口拒绝 SEW=64。WADD/WSUB/WMUL 的 SEW=32
  变体也被拒绝，因为其输出会成为 64 位元素。
- Saturn 功能单元的源、目的最大元素宽度为 32 位；EVU 专用参数为
  FLEN=32、ELEN=32。FP64 FMA、比较、除法/平方根和转换分支已移除。
- 保留 INT32 乘法的 64 位中间乘积、量化中间结果、64 位地址与标量接口，
  以及 64 位 lane 存储切片。它们不代表支持 INT64/FP64 元素指令。
- INT8→INT16、INT16→INT32、FP16→FP32 等有效宽度内的转换仍保留。
  Saturn 内部有转换单元，不代表已恢复被删除的独立 EVU CONVERT 编码。
- NEMU 的 EVU LOAD/STORE 入口拒绝 SEW=64；其参考子集不覆盖全部普通
  向量算术和量化指令，见 [虚拟内存说明](evu-virtual-memory.md)。

## 定向检查

以下套件用于定向验证。它们覆盖操作数依赖、
同 bank 竞争、读写互斥、分周期收集、写回停顿、非法宽度、保留宽度的
算术/转换与访存地址和字节掩码。

当前相关定向套件包括：
`EvuAraRegisterFileElaborationSpec`、`EvuSynchronousVrfSpec`、`EvuByteMaskVrfSpec`、
`EvuSaturnOperandSpec`、`EvuOperandCollectionSpec`、
`EvuControlSequencerSpec`、`EvuSaturnAddressSpec`。
真实 VRF 的逐字节写入和 Saturn 序列器集成用例使用 Verilator；
小规模读协议、断言等用例使用 Treadle 或 CHIRRTL。Saturn 解码需要把仓库的
`espresso` 加入 PATH。
Saturn + Difftest 整体 CHIRRTL 展开也已通过，仍有现有的展开告警。
`evuCheck.test.compile` 与主工程 `xiangshan.test.compile` 均已通过。

```sh
mill -i evuCheck.compile
PATH="$PWD/src/main/resources:$PATH" \
  mill -i evuCheck.test.testOnly \
  evu.EvuElementWidthSpec evu.EvuOperandCollectionSpec evu.EvuSaturnOperandSpec \
  evu.EvuAraRegisterFileElaborationSpec evu.EvuSynchronousVrfSpec evu.EvuDispatchAdapterSpec \
  evu.EvuByteMaskVrfSpec \
  evu.EvuControlSequencerSpec evu.EvuSaturnAddressSpec
```

CHIRRTL 展开和组件仿真不能替代完整核 EMU+Difftest，也不能证明同步 SRAM
硬宏的绑定与时序收敛。面积、时序和性能收益仍需综合及工作负载测量。
