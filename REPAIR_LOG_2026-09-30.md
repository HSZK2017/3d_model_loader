# 修复日志：Model3D Loader API（2026-09-30）

审查报告见 [CODE_REVIEW_2026-09-30.md](CODE_REVIEW_2026-09-30.md)（正文保持审查当时的事实，未随后续修复改写）。
本文件是**唯一的修复记录**：P0–P5 六轮的全部结论、证据与未做项。

仓库**不含任何模型美术资源**：`models/` 只是本地开发用的第三方示例模型，已列入 `.gitignore`，
全新克隆不需要它也能 `gradlew build`（`linkSampleModels` 在源目录缺失时只打印一行并跳过，
语料测试在 `-Dmodel3d.corpus` 未指向目录时跳过）。

修订版本以**内容哈希**钉定（`src/main/java` + `src/test/java` + `src/verify/java`，129 文件）：

| 阶段 | 代码哈希 | 对应产物 jar |
|---|---|---|
| 审查基准 | `d49e7d43…` | —— |
| P0 | `936076d5…` | —— |
| P1 | `768751006b1c7fe88600f3f1b5f39daefc1f5ce7557cc0947c3e46ae2593ab3b` | —— |
| P2 | `4121c54e…` | —— |
| P3 | `07e7f8ce…` | `59FBD130…`（用户实际测过的那份） |
| P4 | `3e002158a7ec32711ed9d7ef2c33236e4c125dc599b78f2c27913615b9434def` | `7948DFD7…` |
| P5 | `cc9562aa7d5237ad9d6cbcb1d1264aa3837614f473f37ff7a04fe2483821eab1` | `F779CEB7…`（当前） |

| 阶段 | 范围 | 关键结果 |
|---|---|---|
| P0 | B1/B2/B8/B9（+B13、B4 半） | 客户端不再崩、6 GiB OOM 关闭、贴图路径不再抛异常、画面不再是透明黑 |
| P1 | B3/B4/B5/B6+B16/B10/B11/B14/B17（+F4） | 删 7 类死代码（主源码 −2,274 行）、`Mat4.compose` 缩放序、网络包边界与方向、GL 状态守卫、验证改指 live 路径 |
| P2 | B7/B12/B18/K1/T2/T3/R3+B15 | 按材质颜色/透明/双面、`texCoord` 告警、`textures` 别名、引用计数契约、**首次拿到可读实机画面**、热路径去分配 |
| P3 | C3/C4/K2/K4/F12/日志/注释 | 镜像归还给模型、`scale==1.0` 哨兵消除、API 示例可编译、134 行 live 死代码删除、文档收敛 |
| P4 | **用户报告①**：蒙皮透明、看到内部 | P2 回归：BLEND 关掉深度写入+剔除 → 修复（深度写入保留、剔除只由 `doubleSided`、两趟绘制）。另修 P1 的 `GlStateGuard` 原始恢复导致的 `GlStateManager` 缓存失真（**半透明从未生效**） |
| P5 | **用户报告②**：图层错乱、模型挡住前方的人 | 原始移植空洞：绘制时 `GL_DEPTH_TEST` 为 **false** → 修复（自己开启深度测试 + LEQUAL），量化：中轴红色像素最低点 661 → 417 |

---

## P0 —— 崩溃、容量与贴图路径

| id | 修复 | 文件 | 证据 |
|---|---|---|---|
| B1 | 顶点缓冲按**索引**数而不是顶点数分配 | `ModelDrawList.indexCount()`、`ModelCpuRenderPath.bufferCapacityFor` | 修复前实测 `BufferOverflowException`（`CpuVertexWriter.java:104`，24 容量 vs 36 次索引写）；修复后 `runClient` exit 0 + 80 tick |
| B2 | `u_color` 每材质上传 | `ModelCpuRenderPath` | 修复前从不赋值 → 片元恒为 `(0,0,0,0)` 透明黑 |
| B8 | 访问器先检查上限再分配 | `AccessorReader` | 修复前测试直接 OOM 杀死 Gradle worker（XML 显示 `tests="7" skipped="1"`） |
| B9 | 贴图路径不再抛异常 | `ModelLocation.inModelRootOrNull/inTextureRootOrNull` + 调用点 | —— |
| B13 | 无贴图材质绑 1×1 白色回退（此前绑纹理 0 = 不完整纹理 = 全黑） | `ModelCpuMesh` | 采样返黑 → 修复后按材质着色 |
| B4（半） | GPU 容量随模型变化时重建 | `ModelCpuRenderPath` | P1 补齐另一半 |

**教训**：`runClient` 是唯一能暴露 JVM 级故障的检查；`glGetError` 一直是 0，画错不等于报错。

---

## P1 —— 死代码、矩阵序、网络边界、状态守卫

| id | 修复 | 关键点 |
|---|---|---|
| B10 | `Mat4.compose` 改为缩放**列**（`r[c*4+i] *= scale[c]`） | 修复前是行缩放，`T*R*S` 不等价 |
| B11 | `ModelSyncPacket.MAX_ANIMATIONS = 256` + 解码异常 + 编码钳制；`NetworkHandler` 注册 `PLAY_TO_CLIENT` | 恶意包可让客户端分配任意列表 |
| B5 | `ClientModelManager` 用 `iterator.remove()` | 修复前边遍历边删（`ConcurrentModificationException` 风险） |
| B17 | `ModelLoadService.libraries()` 缓存单个 `ModelLibrary` | 修复前每次重建 |
| B4 | `onResourcesReloaded()` 调 `releaseGpuResources()` | 重载后旧程序不再残留 |
| **B6+B16** | 删 6 个死类：`client/gl/{GlMesh,GlProgram,AttributeSlots,GlTexture}`、`client/render/{ModelShaders,ModelRenderer}`；`ClientMeshCache` → `ClientTextureResolver`，`GlStateGuard` 转为 live | 主源码 94→88 文件、19,229→16,955 行；两个 GL 验证任务改指 live 路径（`ShaderCompileCheck` 编译 live 着色器、`MeshUploadCheck` 驱动 live mesh 路径） |
| B14 | `issue()` 包在 `try (GlStateGuard guard = …)` 内；守卫保存/恢复纹理单元 0–2（含 `resyncActiveTextureTracker`） | 修复前 GL 状态泄漏给"下一个画的人" |

**这一轮埋下了 P4 的坑**：守卫的**恢复**用了原始 GL 调用（详见 P4）。

---

## P2 —— 按材质渲染、告警、别名、契约、验收视点

| id | 修复 | 证据 |
|---|---|---|
| **B7** | 按材质上传 `u_color`/`u_alphaMode`/`u_alphaCutoff`，按材质设置混合、深度写、剔除 | `shaderCheck` 17 个 uniform 全 active；实机画面按材质 `baseColorFactor` 着色 |
| B12 | 读取 `textureInfo.texCoord`，非 0 时"每个不同 UV 集一次"告警 | 3 例测试；变异验证：还原修复 → 2 例红 |
| B18 | `model.json` 的 `textures` 别名生效（`ModelDescriptor.textureAlias`） | 4 例测试走真实解析器 |
| K1 | `ModelHandle.release()` 改 `== 0`；负计数记 error | 3 例测试：第二次 release 现在返回 false |
| T2/T3 | 验收视点：视距由 `DEFAULT_TARGET_BLOCKS×1.25` 推导、Y 夹取并记录、**服务端 `/tp @s ~ ~ ~ 0 0` 摆平相机**（客户端侧改角度不生效，实测）、截图 tick 后移；生成前 `/kill @e[type=model3d:test_model]` | `view is yaw=0.00 pitch=0.00` → 首张可读画面；聊天栏 `Killed 35 entities` |
| R3+B15 | 复用可增长的 `CpuVertexWriter`（不再每帧 malloc/free 上兆）、`ModelDrawList` 每帧只建一次、删除每顶点两个 JOML 对象 | 全量测试 + 实机运行 |

**教训（T2）**：真正的阻塞不是距离而是相机俯仰；客户端视向量只在服务端位置包到达后才更新，2 tick 不够；日志里必须打印**实际**视角，否则"相机没摆平"会被误读成"模型不可见"。

---

## P3 —— 技术债

| id | 修复 |
|---|---|
| C3 | 镜像轴改为**每模型**可声明（`model.json` 的 `"mirror"`），系统属性降为全局覆盖；默认仍 `xy` 并写明由来；非法值**抛错**而不是静默当 none |
| C4 | "未设置"改用专门哨兵 `TestModelEntity.UNSET_SCALE = 0`，`scale == 1.0f` 不再被当作未设置 |
| K2 | `ModelInstance` 的示例改成真实 API；坏的 `{@link}` 换成 `@see` |
| K4 | 入口类 javadoc 与启动日志改为 `data/` 优先、`assets/` 为客户端回退（服务器只服务 `data/`） |
| F12 | 删除 live 类里的死代码：`VanillaModelRenderer` 4 个死方法（134 行）、`InstanceTransform` 3 个死成员、空 `if`、5 个失效 import |
| 其它 | 恒为 `?` 的日志改为如实说明；指向已删类的 `{@link}`/注释改为现状 |

**未做（保留编号）**：`-Dmodel3d.invertNormals` 默认 `true` 与 C3 同形（有实测依据，故只记为同类待观察项）；深挖线索 F2/F5/F6/F7/F10–F18 未复核。

---

## P4 —— 用户报告①：机体蒙皮被透明化

**症状**：*"机体蒙皮被透明化了导致透过蒙皮看到了模型内部"*。

**缺陷 D1（P2 回归）**：P2 给 BLEND 材质写了 `depthMask(false) + disableCull()`。vanilla 能这么做是因为它**同时**把半透明排到最后并做前后排序；这里没有排序，代价不对称——一个"声明 BLEND 但贴图其实不透明"的材质（导出器常把整个模型标成 BLEND）不再遮挡任何东西，内部全部透出。
**修复**：所有模式保留深度写入；剔除只由 `doubleSided` 决定；**先 OPAQUE+MASK，再 BLEND 两趟绘制**。

**缺陷 D2（P1 埋下）**：`GlStateGuard.close()` 用**原始** GL 恢复 blend/depth/cull，令 `GlStateManager` 缓存与驱动失真，此后所有经 tracked API 的状态请求被**静默跳过**——**半透明从未生效**。
**修复**：恢复改走 tracked API（cull 的**面**仍用原始调用，因为 `GlStateManager` 不缓存它）。

**受控验证**（`run/config/3dmodels/{opaque,blend,alpha,zero}_cube/`：同几何，只差 `alphaMode` 与 alpha）：

| 夹具 | 修复前 | 修复后 |
|---|---|---|
| BLEND + alpha 1.0 | 扁平可透视的杂乱面片 | **实心，与 OPAQUE 对照组逐像素相同 `(85,30,25)`** |
| BLEND + alpha 0.35 | 与 alpha 1.0 一样不透明 | 半透明 `(139,141,175)` |
| BLEND + alpha 0.0 | —— | 墙体消失 |
| OPAQUE + alpha 1.0 | 实心 | 实心（无回归） |

**因果链是实验锁定的**：探针着色器读到 `v_color.a = 0.349`、`texColor.a = 1.0`、`u_color` alpha 0.35、`GL_BLEND=true`、`funcRGB=(770,771)`、`equationRGB=32774`——**每个值都对**，画面却不对；把同样的状态改用**原始**调用下发就立刻混合生效 → 定位到"tracked API 被缓存跳过" → 定位到守卫的原始恢复。

**回归检查 + 变异验证**：`MeshUploadCheck` 新增驱动读回检查（`a tracked disable/enable reaches the driver after a guard round trip` 等 6 项）；把守卫的 blend 恢复改回原始调用 → `RESULT: FAIL (1 check(s))` ✓ 检查承重。

**教训**：①"用 tracked API"必须覆盖**恢复**路径，而不只是设置路径；缓存型 API 的危险是双向的，且没有错误、没有日志。②我此前的对照验证被**遗留实体**污染（客户端仍持有上一轮实体，两个实体位置重合），一度得出错误结论；干净对照要么让遗留实体是同模型，要么 trace 单跑。

---

## P5 —— 用户报告②：图层错乱 / 模型挡住前方的人

**症状**：*"图层绘制的非常诡异，可能是图层过高，甚至会不符合透视的在人站在镜头前挡着它时实际上镜头中是它挡住人"*（第三人称截图：飞机与玩家互相穿插）。

**先排除的误会**：用户 `mods/` 里的 jar 是 **15:53 的 P3 构建**（`59FBD130…`），P4 的两个修复从未进入他的游戏。已把新 jar 部署到其 `mods/`（旧 jar 备份 `…jar.p3-backup-20260930-1553`）。

**真缺陷**：trace 读回驱动真实状态——**`depthTest=false`**（`GL_DEPTH_TEST` 关闭）。
模型因此完全无视深度缓冲：压过任何先画的东西（地形、先画的实体），自己写深度又挡住后画的几何 → "图层过高 / 不符合透视 / 它挡住人"。这是**原始移植就有的空洞**：CPU 立即绘制路径沿用了"批处理管线碰巧留下的状态"，而此前所有验收都是空世界里一个模型、没有遮挡物。

**修复**（`ModelCpuRenderPath.issue()` 进入守卫后的第一件事）：自己声明依赖的状态——

```java
GlStateManager._enableDepthTest();
GlStateManager._depthFunc(GL11.GL_LEQUAL);
```

**量化证据**：

| 项目 | 修复前 | 修复后 |
|---|---|---|
| 驱动状态 | `depthTest=false` | `depthTest=true`，`depthFunc=515 (LEQUAL)` |
| **模型红色像素在屏幕中轴的最低点** | **y = 661**（压进草地） | **y = 417**（被地形截断） |

新增驱动读回检查 `a tracked enable/disableDepthTest reaches the driver`。

**教训**：①"状态是谁的"要问两次——P4 修"恢复失同步"，P5 修"根本没设"；同一根因族：**CPU 立即绘制路径不能继承批处理管线的状态**（程序、纹理、混合、深度都要自己声明、交给守卫恢复）。②**修复必须交付到用户实际运行的那份构建上**。③遮挡类缺陷需要"有遮挡物"的验证。

---

## 全局未验证 / 未做

**未验证**
- **第三人称玩家遮挡**：P5 用地形遮挡验证（同一深度缓冲机制），但不是同一场景。
- **真实模型的观感**：本机两个 su-30 文件里只有座舱盖是 BLEND（其余 21/22 OPAQUE），"蒙皮透出"是在受控夹具上复现修复的。
- **深度测试关闭的上游来源**：未追出结论（修复方式不依赖来源）。
- **BLEND 之间的前后排序**：不存在；BLEND 与 BLEND、BLEND 与相机之间仍可能错序。
- **专用服务器**：`runServer -Pmodel3dSelfTest` 未跑。
- **B5 的并发路径**：无单测（代码复读 + 全量测试）。
- `assets/` 回退树未实机验证。

**未做（保留编号）**
- 深挖线索 F2/F5/F6/F7/F10–F18（各需一次独立复核）。
- `-Dmodel3d.invertNormals` 默认值（与 C3 同形，但有实测依据）。
- T3 遗留实体在客户端侧的清除时机（P4 观察到的现象，未定位）。
- 哨兵/策略残留与文档收敛的边角项。

## 最终状态（P5 交付）

- `gradlew build checkExtras` 全 PASS；`gradlew test` **237 / 0 失败 / 1 跳过**。
- `shaderCheck`：修改后的 `model_cpu.vsh/.fsh` 编译链接通过，17 个 uniform/sampler 全 active。
- `meshUploadCheck`：live mesh 路径 + 守卫往返 + **tracked 状态读回**（blend / depthMask / depthTest）全 ok。
- 客户端验收：`animated_test`、`su-30_flanker`、热重载写入，均 **exit 0 + 80 tick**；画面实心、有明暗、被地形正确遮挡。
- 交付产物：`build/libs/model3d-1.20.1-1.0.0.jar` sha256 `F779CEB7B4B4A467FE9506AA16426264B8B79B125C860A6E0F9314AD59FC4CDD`。
