# EVU 文档索引

本文档对应 `DefaultExternalVectorMatrixConfig`：使用 Saturn 后端，默认 VLEN=4096、DLEN=256，外挂向量寄存器与核内 RVV 寄存器独立。

## 当前说明

| 文档 | 内容 |
| --- | --- |
| [VRF 组织与元素宽度](evu-vrf-element-width.md) | bank 组织、冲突处理、读写时序、支持的元素宽度及定向测试命令。 |
| [RegisterFile.scala 逐行讲解](evu-register-file-walkthrough.md) | 面向初学者的语法、接口、地址映射、逐行解释和读写示例。 |
| [虚拟内存接入](evu-virtual-memory.md) | LOAD/STORE 预检查、页映射、异常重试、权限限制及测试命令。 |
| [矩阵 Launch 与服务器验证](evu-launch-server.md) | 矩阵路径、参考模型支持范围、NEMU/EMU/guest 构建、验收条件和已知失败。 |
| [Online softmax Difftest 分析](evu-softmax-difftest.md) | 1-ULP 归约原因、flags 修复、逐指令比对、精度及重跑命令。 |
| [EVU CSR 与上下文隔离建议](evu-csr-architecture.md) | 每项状态的隔离边界、固定舍入、上下文所有权、异步读写顺序与 NEMU 约定；设计建议，尚未实现。 |
| [已移除指令与 Softmax 清理](evu-softmax-removal.md) | 保留的非法编码、Softmax 删除范围、HBL2 source 分配和旧测试清理。 |
| [Saturn 架构收敛](evu-saturn-convergence.md) | 单一后端、控制定序器、统一矩阵路径及 HBL2 source ID 约定。 |

## 尚未实现的方案

[复用 Saturn 计算资源做量化](evu-saturn-quant-reuse.md) 是历史设计建议。当前已删除 `VRF -> 独立量化 -> VRF` 路径及 DEQUANT/REQUANT/QUANT 指令，编码 `0x09/0x0b/0x4f` 保留为非法指令。保留的 `VRF -> 量化 -> CUTE` 和 `CUTE -> 反量化 -> HBL2` 仍各有一个 `EvuQuant` 实例，没有共享 Saturn 的算术单元。SETCFG/descriptor 仅服务于前一条路径的定点量化参数；特殊路径不再有 VRF 写回端口。

## 验证状态

以下汇总此前验证记录和同步 VRF 改造后的定向验证；完整核验证的限制仍然适用。

| 项目 | 已有结果及边界 |
| --- | --- |
| Scala 编译、定向组件测试 | Saturn 架构收敛后，`evuCheck.test.compile`、主工程 `xiangshan.test.compile` 和 5 个定向套件共 8 项测试通过。 |
| 移除量化写回 VRF | `evuCheck.test.compile`、`xiangshan.test.compile` 及 7 个套件共 14 项测试通过，覆盖非法编码、CUTE 输出反压/flush、VL 尾部、VRF 保持、饱和及矩阵反量化。NEMU 本机计算回归也通过；没有替代完整 EMU 验证。 |
| 同步 1RW VRF | 已覆盖同步返回、路由、位掩码和 bank 冲突；Saturn 是唯一 VRF 与执行后端。 |
| Saturn + Difftest CHIRRTL 展开 | 同步 VRF 改造后重新通过结构展开；没有证明完整 RTL 运行结果。 |
| native MMU 参考测试 | Softmax 删除后通过，覆盖 9 次物理写、跨页、重映射、异常重试及非法编码。 |
| native 矩阵参考测试 | 曾在首个异步 LOAD completion 检查失败，删除 Softmax 前的模型也复现。尚无修复后的通过记录，见服务器验证文档。 |
| 完整 Saturn 访存仿真 | `EvuVirtualMemorySpec` 由 `EVU_SERVER_RTL_TEST=1` 启用；最近本地检查因服务器开关未开启而取消，不算通过。 |
| 完整核 EMU + Difftest | 当前 EVU 改动尚无端到端通过记录。 |

旧的 3 个非 Saturn 集成用例已删除，因为其内部 VRF 和矩阵握手假设与当前设计不匹配。32 字节 TileLink 多拍运行时覆盖尚未完整替代，这个缺口仍然存在。

## 旧记录和传输清单

重复的 Launch 计划/进度、已删除 Softmax 的计划/进度文档已经移除；仍有用的接口约定、验证限制和运行命令并入上述专题文档。

本目录现有 `*-files.txt`、`*-sources.sha256` 和 `evu-virtual-memory.sha256` 属于此前增量传输包的快照。它们包含旧文件名和旧校验值，不能作为当前工作树的完整清单或校验依据。文档整理及同步 VRF 改造没有重新生成这些传输包。

向服务器同步当前代码时，需要传递完整的当前源码，并同步已经删除的源码和测试文件；只覆盖旧压缩包列出的文件会留下过时实现。构建产物、Git 元数据和子模块状态应按各仓库现有同步方式处理。
