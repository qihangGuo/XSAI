# EVU Saturn 架构收敛

`DefaultExternalVectorMatrixConfig` 现在只有 Saturn 一种 EVU 执行后端。
`EvuParams.useSaturnBackend`、非 Saturn 算术执行器、独立架构 VRF 侧带和
顶层本地矩阵量化 fallback 均已删除。

## 当前模块边界

- `EvuTop` 无条件实例化 `EvuVectorBackend`。
- 普通向量算术和 LOAD/STORE 进入 Saturn 的 dispatcher、issue queue、
  sequencer、execution unit、VMU 和 banked RegisterFile。
- `Exp2` 进入 Saturn FP issue queue，通过独立的六级 `VFExp2FU` 执行，
  复用普通读寄存器、元素组推进、mask 和写回机制。支持 IEEE FP16/FP32，
  不启用 BF16；e8/e64 请求按非法指令完成。现有 xv.exp2 软件编码不变。
- FP32 `FpQuantToCute`（0x50）由 `EvuSpecialSequencer` 管理，使用两组
  操作数缓存和三级弹性 `EvuFpQuant`，直接写 CUTE，不回写 VRF。
  CUTE 结果流反量化采用当前 A/B scale chunk 缓存、两级算术流水和两个输出槽。
  旧定点 QuantToCute/SETCFG（0x4c/0x4e）及通用 `EvuQuant` 已删除；
  原 VRF DEQUANT/REQUANT/QUANT（0x09/0x0b/0x4f）继续按非法指令处理。
- `Launch`、`Wait`、`Fence`、`SetVli` 和 `SetVl` 由
  `EvuControlSequencer` 串行管理；它不再执行普通向量算术或访存。
- CUTE matrix beat 统一经过 `MatrixVectorBridge -> EvuSpecialSequencer ->
  EvuMatrixDequant`，顶层不再保留第二套本地 `EvuQuant` 矩阵路径。

计算指令在最后一个元素组写回时释放 VAT。浮点异常按 VAT 累计，
在完成通知产生时保存，因此完成接口阻塞时也不会丢失早期元素组的异常。
Exp2 按有效元素过滤异常，支持非零 vstart，保留 mask/prestart/tail
范围之外的原目的寄存器值；vstart >= VL 和 VL=0 均正常完成而不写 VRF。
其他算术指令仍保留 Saturn 原有的 vstart=0 限制。

自定义 `SetMask` 已移除，原编码 `0x4d` 保留并按非法指令处理。
Saturn 的 v0 掩码机制仍保留，已打包的掩码可通过普通 LOAD 写入 v0。
后端支持通过 `aux[1:0]` 控制算术指令是否使用掩码，但主核普通 EVU
指令尚未提供该控制，当前软件指令默认不使用掩码。

## HBL2 source ID

顶层 HBL2 客户端通过一个两输入访存仲裁器处理 Saturn 和 CUTE matrix
output：

```text
0 .. N-2: Saturn LOAD/STORE
N-1:      CUTE matrix output
```

内部 owner bit 中，`0` 表示 CUTE matrix output，`1` 表示 Saturn。CUTE
output 继续作为物理地址生产者，不经过 Saturn LOAD/STORE 的命令页映射。

## 验证入口

```sh
mill -i evuCheck.test.compile

PATH="$PWD/src/main/resources:$PATH" \
  mill -i evuCheck.test.testOnly \
  evu.EvuControlSequencerSpec evu.EvuOperandCollectionSpec \
  evu.EvuSaturnOperandSpec evu.EvuSaturnAddressSpec \
  evu.EvuExp2Spec evu.EvuSaturnExp2Spec
```

完整 Treadle 顶层测试展开较慢，不能替代服务器上的 EMU+Difftest。
