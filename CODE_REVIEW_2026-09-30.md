# 代码审查：Model3D Loader API（Minecraft 1.20.1 Forge 模组）

> **修复状态（2026-09-30 20:35 更新 —— 全部分级 + 三次用户报告修复）**
> - **P0**（[修复日志](REPAIR_LOG_2026-09-30.md)，`936076d5…`）：B1 缓冲容量、B2 `u_color`、B8 访问器先分配、B9 贴图路径崩溃（+B13、B4 容量过期一半）。
> - **P1**（[修复日志](REPAIR_LOG_2026-09-30.md)，`768751006b1c…`）：B3 文档、B4 释放、B5 迭代删除、B6+B16 删 7 类死代码并把 GL 验证指向 live 路径、B10 `Mat4.compose` 缩放序、B11 包边界与方向、B14 状态守卫（+F4）、B17 签名缓存。
> - **P2**（[修复日志](REPAIR_LOG_2026-09-30.md)，`4121c54e…`）：B7 按材质颜色/透明/双面、B12 `texCoord` 告警、B18 `textures` 别名、K1 引用计数、T2/T3 验收视点与清理、R3+B15 去分配。
> - **P3**（[修复日志](REPAIR_LOG_2026-09-30.md)，`07e7f8ce…`）：C3 镜像改为每模型可声明、C4 `scale==1.0` 哨兵、K2 API 示例、K4 入口文档、F12 删除 134 行 live 死代码、恒为 `?` 的日志、注释残留。
> - 最终门禁：`gradlew build checkExtras` 全 PASS；`gradlew test` **237 / 0 失败 / 1 跳过**；客户端验收（`animated_test`、`su-30_flanker`×2）均 exit 0 + 80 tick。
> - **P4（用户报告："机体蒙皮被透明化，透过蒙皮看到模型内部"）**（[修复日志](REPAIR_LOG_2026-09-30.md)，`3e002158…`）：报告成立，是 **P2/B7 引入的回归**——BLEND 材质被关掉深度写入与剔除，于是"声明 BLEND 但贴图不透明"的蒙皮不再遮挡，模型内部透出；修复为**所有模式保留深度写入 + 剔除只由 `doubleSided` 决定 + 先不透明后半透明两趟绘制**。排查中另发现 **P1/`GlStateGuard` 的第二个缺陷**：守卫用原始 GL 恢复 blend/depth/cull，令 `GlStateManager` 缓存与驱动失真，此后所有经 tracked API 的状态请求被静默跳过——**半透明其实从未生效**；改为经 tracked API 恢复，并新增 6 项驱动读回检查（变异体可使其变红）。受控 alpha 夹具（BLEND 1.0/0.35/0.0 与 OPAQUE）逐一验证：不透明蒙皮不再透出、0.35 半透明、0.0 不可见、OPAQUE 无回归。
> - **P5（用户报告："图层错乱，模型挡住站在镜头前的人"）**（[修复日志](REPAIR_LOG_2026-09-30.md)，`cc9562aa…`）：报告成立，是**原始移植就有的空洞**——绘制时 `GL_DEPTH_TEST` 是**关闭**的（trace 读回 `depthTest=false`），模型因此无视深度缓冲，压过地形与更近的实体、又被它之后画的几何压住 = "图层过高 / 不符合透视 / 它挡住人"。修复为**在 `issue()` 里自己开启深度测试并设 LEQUAL**（守卫恢复原状），量化验证：模型红色像素在屏幕中轴的最低点由 **y=661 → y=417**（不再压过地面）。另查明并纠正交付问题：**用户当时运行的是 15:53 的 P3 旧 jar**，新 jar 已部署到其 `mods/`（旧 jar 留备份）。
> - **下列正文保持审查当时的事实**（修订版本 `d49e7d43…`），未随之改写。未做项：BLEND 之间的前后排序、T3 遗留实体在客户端侧的清除时机、深挖中未经复核的线索（F2/F5/F6/F7/F10–F18）、深度测试关闭的**上游来源**（修复不依赖它，但未追出结论），以及需要用户侧确认真实模型的观感。

> **修订版本（冻结输入）**：本目录**不是 git 仓库**（`git rev-parse HEAD` → `fatal: not a git repository`），
> 因此修订版本以内容哈希钉定：`src/**` + `build.gradle` + `gradle.properties` + `settings.gradle`
> 共 **136 个文件 / 1,310,877 字节**，树哈希 **sha256 = d49e7d433110686097ed627198eb31c8fb3ff63b9a7b2b2c4e03fd210eaebb24**。
> 本报告写成后新增的唯一文件是本报告自身（`CODE_REVIEW_2026-09-30.md`），未修改任何被审查文件。
>
> **范围**：`src/main/java`（94 文件 / 19,229 行）、`src/main/resources`、`src/test/java`（20 个测试类）、
> `src/verify/java`、`build.gradle`、`README.md`、`HANDOVER.md`、`ERROR/**`、`run/**`（既有运行产物）。
> **未审查**：`models/` 中的第三方模型二进制、`.gradle/`、`build/` 产物内部、`tools/` 下的 Gradle 打包脚本细节。
>
> **执行过的命令（as-run 证据）**：
> `gradlew test`（exit 0；204 tests / 0 failures / 0 errors / 1 skipped）、
> `gradlew checkExtras`（shaderCheck / meshUploadCheck / commandParseCheck / eventBusCheck 均 RESULT: PASS，exit 0）、
> `gradlew modelProbe --args="src/main/resources/data/model3d/model3d/animated_test/model.glb full"`（1 primitive / 8 vertices / 12 triangles）、
> `gradlew runClient -Pmodel3dClientTest=model3d:animated_test`（**崩溃，exit 1**，见 B1）。
>
> **没有验证的**：画面的“好看程度”（光照/贴图/蒙皮视觉正确性）、几何朝向的人工判读、
> 以及任何需要另一台机器或另一份模型语料才能复现的行为。逐条标注证据等级。

---

## 结论（Verdict）

**不可发布。** 这个模组的**数据层**（glTF/GLB/OBJ 解析、动画采样、资源解析、错误矩阵）质量明显高于同类个人模组，
有 204 个真测试、离线探针、带负例的命令语法检查——这一半值得 7.5/10。
但**当前绘制路径（第三次重写的 CPU 蒙皮路径）在本次审查中被实测崩溃**，且崩溃点是模组**自带的测试模型**：

```
java.nio.BufferOverflowException
  at com.model3d.loader.client.render.CpuVertexWriter.vertex(CpuVertexWriter.java:104)
  at com.model3d.loader.client.render.ModelCpuRenderPath.fill(ModelCpuRenderPath.java:248)
  at com.model3d.loader.client.render.VanillaModelRenderer.draw(VanillaModelRenderer.java:148)
  at com.model3d.loader.client.render.RenderTestModelEntity.render(RenderTestModelEntity.java:136)
Description: Rendering entity in world
Suspected Mod: Model3D Loader API (model3d), Version: 1.0.0
```

这条崩溃可由**算术证明**：顶点缓冲按 `唯一顶点数 × 3` 分配，而填充循环**按索引数**写入顶点。
自带 fixture 是 8 顶点 / 12 三角形 = **36 个索引**，容量只有 `8 × 3 = 24` → 第 25 个顶点必然越界。
也就是说：**任何索引化良好的网格（立方体、球体、任何共享顶点的模型）都会崩客户端**，
只有“索引数 ≤ 3×唯一顶点数”的三角形汤模型（如仓库里的苏-30）才侥幸不崩。

同时，README 断言该验收命令“80 tick 无故障”、`checkExtras` 覆盖“模组真实 GLSL”，
而**项目自己的产物反驳了这两条**：`run/crash-reports/` 里今天已有 4 份同一栈的客户端崩溃报告，
`shaderCheck` 编译的是**已被废弃**的着色器（`ModelShaders`），当前在用的 `model_cpu.vsh/fsh` **没有任何自动检查覆盖**。

此外，并行深挖（后经本人逐条复核）又找到**两个同类阻断项**：解析层用 150 字节的文件就能触发 6 GiB 分配
（`OutOfMemoryError` 绕过 `catch (RuntimeException)`），绘制层对**任何含大写字母或空格的贴图路径**会抛
`ResourceLocationException` —— 而 README 恰好把 `Textures/Glass_Cockpit.jpeg` 写成“必须能解析”的例子。
变换层还有一个被整套测试**结构性看不见**的缺陷：`Mat4.compose` 缩放的是行而不是列，算出来是 `S·R` 而非 `T·R·S`。

**综合评分：4.4 / 10**（数据层 6.5，渲染层 3.0，API 层 5.0，文档与验证可信度 5.0）。
修掉 B1/B2/B8/B9 与 M1 后，总分约 7/10。**本分数比初稿的 5.0 低**：初稿只覆盖了本人第一手读到的部分，
并行深挖返回并复核后新增了 2 个 Critical 与 1 个 High，详见“并行深挖的发现（已复核）”。

---

## 阻断项（Blockers）

| # | 严重度 | 位置 | 缺陷 | 可观测后果 | 证据 |
|---|---|---|---|---|---|
| B1 | **Critical (P0)** | `client/render/ModelCpuRenderPath.java:132`、`client/render/ModelDrawList.java:122`、`client/render/ModelCpuRenderPath.java:194` | 顶点缓冲按 `drawList.vertexCount() * 3` 分配，填充按**索引数**写顶点 | 索引数 > 3×唯一顶点数时 `BufferOverflowException` → 客户端崩溃（`ReportedException: Rendering entity in world`） | **红跑**：本次审查执行 `runClient -Pmodel3dClientTest=model3d:animated_test`（2026-09-30 14:15:28）崩溃，crash report `run/crash-reports/crash-2026-09-30_14.15.28-client.txt`，栈含 `Suspected Mod: model3d`；项目自身另有 3 份同栈报告（`run/crash-reports/crash-2026-09-30_13.40.42 / 13.41.39 / 13.43.02-client.txt`）。算术：`modelProbe` 实测 fixture = 8 顶点 / 12 三角形 → 36 索引；容量 `8*3=24` < 36 |
| B2 | **Critical (P0)** | `assets/model3d/shaders/model_cpu.vsh:82` + `model_cpu.fsh:56`，`ModelCpuRenderPath.java:71,370` | `u_color` 只取了 uniform 位置，**从未 `glUniform4f` 上传**；GL 默认值为 `(0,0,0,0)` | `v_color = mix_light(..., u_color)` → `(0,0,0,0)`；`color = texColor * v_color` → 片元恒为透明黑。当前路径**不可能画出正确颜色** | 引用：`locColor` 全仓库仅出现 2 次（声明 + 取位置），无上传点；本次运行 harness 在崩溃前 2 tick 抓到的截图 `run/screenshots/2026-09-30_14.15.26.png` 里，4.61 格外**看不到任何模型**（日志称该实例 `scale=40.0 blocks/unit`） |
| B3 | **High (P1)** | `README.md` “Verifying it / What has NOT been verified”、`build.gradle:36-67` | 文档声称 `runClient -Pmodel3dClientTest` “joins a world … completes the full draw … for 80 ticks without faulting”，并称 `shaderCheck` 编译“模组真实 GLSL” | 使用文档的人会得到与文档相反的结论，并把“已验收”当事实 | `run/ported2.txt`、`ported3.txt`、`ported4.txt` 三个运行日志均以 `> Task :runClient FAILED` / `BUILD FAILED` 结束；`ShaderCompileCheck.java:81` 编译的是 `ModelShaders.vertexSource()`（遗留管线），不是 `assets/model3d/shaders/model_cpu.vsh` |
| B4 | **High (P1)** | `client/render/ModelCpuRenderPath.java:428` | `disposeAll()` **没有任何调用者**；`ClientMeshCache.clear()`（重载/卸载时调用）只清理遗留管线 | 每个模型 id 的 VAO/VBO 在会话内永不释放；热重载复用**旧容量**，把 B1 再次武装（模型改大即崩） | 全仓库 grep：`disposeAll` 仅出现在定义处；`ClientModelManager.onResourcesReloaded()` 只调 `meshCache.clear()` + `ModelLoadService.clearClient()` |
| B5 | **High (P1)** | `client/ClientModelManager.java:335` | 在 `entries.entrySet().iterator()` 迭代过程中调用 `entries.remove(mapped.getKey())` | `HashMap` fail-fast：下一次 `iterator.next()` 抛 `ConcurrentModificationException`，从 `advance()` 逃逸到 `RenderLevelStageEvent`（`ClientEvents.java:93`）。本应“降级成猪”的容错路径反而打断渲染帧 | 代码：同一方法 312 行用的是正确的 `iterator.remove()`，335 行不是 |
| B6 | **High (P1)** | `client/render/ModelRenderer.java`（840 行）、`ClientMeshCache.java`（750 行，仅一半在用）、`GlProgram/GlMesh/ModelShaders/GlStateGuard/AttributeSlots` | 第一次重写的整套 GL 管线仍留在生产源码里，且 `ClientModelManager.java:98` 仍 `new ModelRenderer(meshCache)`；`renderer()` 无任何调用者 | 6 个类 / **1,868 行 = 主源码 19,229 行的 9.7%** 是不可达代码；`meshUploadCheck`/`GlMeshBufferCapacityTest` 为**死代码**做验证，绿灯因此具有误导性 | grep：`gpuFor` 唯一调用者是 `ModelRenderer.draw:136`；`.renderer()` 全仓库 0 次调用 |
| B7 | **High (P1)** | 同 B2/B6 的文档面 | `README.md` 物料表声称支持 `alphaMode`（含 BLEND）/`doubleSided`/`normalTexture`/`occlusionTexture`/`emissiveTexture`/`metallicRoughnessTexture`，而当前路径一律不生效 | 其他模组按文档接入会得到“贴图/透明/双面都不对”的模型，且没有任何报错 | `ModelCpuRenderPath.java:306` 把 `u_alphaMode` **硬编码为 1**；`model_cpu.vsh:88` `v_cullable = 0.0`（永不背面剔除）；fsh 只采样 `Sampler0/1/2`（基色/overlay/光照图）。`VanillaModelRenderer.renderTypeFor()`（唯一按 alphaMode/doubleSided 分派的地方）无调用者 |
| B8 | **Critical (P0)** | `format/gltf/AccessorReader.java:185`（该检查在 `:197`） | 无 `bufferView` 的访问器，**先分配数组、后才检查 16M 上限**；分配前唯一的闸门是 `count*components ≤ Integer.MAX_VALUE-8` | 一个约 150 字节的文档（`count: 536870912` + `VEC3`）即执行 `new float[1610612736]` ≈ 6 GiB → `OutOfMemoryError`；OOM 是 `Error`，`ModelLoadService.java:537-548` 只捕 `ModelParseException/IOException/RuntimeException`，于是**绕过“坏模型降级为无模型”的契约**，直接打断加载/资源重载 | 本人逐行复核 `AccessorReader.java:177-201`：`:178` 只挡 `> Integer.MAX_VALUE-8`，`:185` 无条件分配，`:197` 才是 `budget > MAX_UNBACKED_ELEMENTS`。类注释 `:29-30` 恰恰声称“先检查后分配” |
| B9 | **Critical (P0)** | `client/render/ClientMeshCache.java:550` → `resource/ModelLocation.java:143` → `util/Ids.java:36` | 贴图相对路径未经小写/字符白名单清洗就构造 `ResourceLocation`；1.20.1 的该构造函数对 `[a-z0-9/._-]` 之外的字符**抛异常** | **任何材质引用含大写字母或空格的文件贴图都会崩客户端**（异常从 `RenderTestModelEntity.render` 的 try/**finally**（无 catch）逃逸 → `ReportedException: Rendering entity in world`）。README 第 147-150 行恰好把 `Textures/Glass_Cockpit.jpeg` 列为必须能解析的例子 | 本人复核调用链：`ClientMeshCache.java:546` 的 `normalise()`（:563-578）只做 `\`→`/`、去 `./`、把 `..` 换成 `__`，**不小写**；`:550` 用它构造 location 时即抛，所以 `find()` 内 `:591-596` 的“大小写不敏感重试”**永远不可能执行**（这正是注释 `:571-576` 里作者已意识到 ResourceLocation 会拒绝非法路径、却只处理了 `..` 的那一处） |
| B10 | **High (P1)** | `math/Mat4.java:116-125` | 注释写 `Translation * Rotation * Scale`（glTF 标准序），代码却把**行**乘以 scale：`r[c*4+i] *= scale[i]`，得到 `S·R` | 非等比缩放且旋转轴与缩放轴不平行时，顶点被乘错缩放系数（+90°绕 Y、S=(2,1,4) 时 (1,0,0) 落到 (0,0,-4) 而非 (0,0,-2)）；影响每个节点的静止姿态（`ModelNode.java:66`）与每一帧动画 | 本人复核：`TransformDecomposition.java:17,54-59` 明确按**列长**定义 scale（R·S 约定），`Mat4.transform`/`mul` 与测试都用列向量 M·v；`GltfFeatureTest.decomposesNodeMatrices` 用的三个矩阵（:311-315）上左 3×3 全是**对角**，两种顺序在该 fixture 上数值相同 → **测试结构性看不见**；`GltfCorpusTest.java:127-130` 更是把 `compose(...)` 与 `restLocalTransform`（其定义就是 `compose`，`ModelNode.java:66`）相比 → 恒真 |
| B11 | **High (P1)** | `common/network/ModelSyncPacket.java:68-69` + `common/network/NetworkHandler.java:32-33` | 解码时 `int count = buffer.readVarInt(); new ArrayList<>(count);` **无上限**；且用 5 参数 `registerMessage` 注册，无方向约束 | 一个 5 字节的包即可让服务端预分配最高 2^31-1 项的 `ArrayList`（OOM），或把负数直接变成 `IllegalArgumentException`；解码在 netty 线程、`handle()` 的侧向判定之前执行 | 本人复核调用点（`decode:68-69`、`register:32-33`）；“5 参数重载 = 不做方向检查”一节依据 Forge 47.4.16 源码（该深挖的 rung-2 证据：`Optional.empty()` 时 `validatePacketDirection` 恒真） |
| B12 | **Medium (P2)** | `format/gltf/MaterialReader.java:145` | `textureInfo.texCoord` **从不读取**，请求 TEXCOORD_1/2 的槽位静默采样 UV0 | 贴图取错 UV 集且**没有任何日志**；`GltfSceneBuilder.java:38-39` 的类注释声称这种情况“degrade with a warning”，实际不生效；`ModelMaterial.java:22` 自己记录参考语料的 `pbr_sukhoi_su-30.glb` 正是 texCoord 1/2 | 本人复核全仓库 grep：`texCoord` 只出现在注释里（`ModelMaterial.java:20,22`、`GltfSceneBuilder.java:38`、`GltfCorpusTest.java:241`），无任何读取代码 |
| B17 | **High (P1)** | `resource/ModelLoadService.java:227`（`libraries()` → `checkForChanges`） | 每次调用 `libraries()` 都 `new ModelLibrary(configModels)`，而签名缓存是**实例字段**（`ModelLibrary.java:319-329`） | 缓存恒冷 → 每次检查都做一次完整 `Files.walk` + 逐文件 size/mtime；调用者 `CommonEvents.java:74`（**每个服务端 tick**）与 `ClientEvents.java:89`（**每一帧**，渲染线程）。类注释 `ModelLoadService.java:250-251` 声称的“大多数时候只是比一个 long”与实际相反 | 本人复核全仓库 `new ModelLibrary(` 调用点：main 中仅 `:227` 与 `:238`；`signature()` 的缓存字段在实例上（`ModelLibrary.java:319-329`） |
| B18 | **Medium (P2)** | `resource/ModelDescriptor.java:227-228` | `model.json` 的 `textures` 别名表被解析、被 `toString` 统计，**没有任何消费者** | README 第 206 行承诺“Lets you repaint a model without editing it”，实际改色后仍显示原贴图且无任何提示——正是这套代码处处避免的“配置被静默忽略” | 本人复核 grep：`textureAliases` 只出现在字段声明、构造参数、赋值、`toString`（`aliases=`+size）与该 accessor 自身；全链路无调用点 |

---

## 按维度列出的发现

### 1. 正确性（Correctness）— 3/10

- **C1（=B1，Critical）** 顶点缓冲容量按 `唯一顶点数 × 3` 估算，写入按索引数。
  `ModelCpuRenderPath.java:132` `mesh = ModelCpuMesh.create(drawList.vertexCount() * 3);`
  `ModelDrawList.java:122` `vertexCount += drawable.primitive().vertexCount();`
  `ModelCpuRenderPath.java:194` `for (int i = 0; i < indices.length; i++) {`
  **最小修复**：容量取自索引总数（给 `ModelDrawList` 加 `indexCount()` 并改用它）；
  或让 `CpuVertexWriter` 在写满时按 2 倍扩容。
  **验收标准**：`gradlew runClient -Pmodel3dClientTest=model3d:animated_test` 跑到
  `Model3D client test: 80 ticks with the entity spawned and no fault` 且退出码 0，日志无 `BufferOverflowException`。
  当前该命令**失败**。
- **C2（=B2，Critical）** `u_color` 未上传，片元恒为透明黑。最小修复：绘制前
  `GL20.glUniform4f(locColor, 1f, 1f, 1f, 1f)`（或把 `u_color` 从着色器里删掉，让光照乘 1.0）。
- **C3（High）** `InstanceTransform` 的全局默认 `-Dmodel3d.mirror=xy`，即**每个模型默认被绕 Z 轴转半圈**。
  与 README 的每模型 `yawOffsetDegrees` 是同一件事的两套策略；一个调试开关被提升为默认值，
  意味着“模型朝向不对”的修复被固化进了所有人的默认路径。
  `InstanceTransform.java:86-91`。
- **C4（Medium）** `ClientModelManager.configure()` 把 `scale == 1.0f` 当作“未设置”：
  `ClientModelManager.java:261` `if (!(scale > 0.0f) || scale == 1.0f)`。
  合法地被设为 1.0 块/单位的模型会被静默替换。哨兵值混用（“未设置”和“等于 1”共用一个值）。
- **C5（Medium）** 热重载后 `MESHES` 复用旧容量（见 B4），模型变大即触发 B1。
- **C6（Low）** `VanillaModelRenderer` 中 `drawn`(145)、`skinned`(141)、`nodes`(143)、`scratch`(144)、
  `pose`(133) 等局部变量在委派给 `ModelCpuRenderPath` 之后已无用途。

### 2. 契约（Contracts）— 4/10

- **K1（Medium）** `ModelHandle.java:91` `return references.decrementAndGet() <= 0;`
  文档说“true 表示这次释放掉的是最后一个引用”，但 `<= 0` 让**重复释放**同样返回 true：
  1→0 返回 true，0→-1 再返回 true。调用方按文档释放 GPU 资源就会被二次释放/在他人仍在渲染时驱逐。
  最小修复：`return references.decrementAndGet() == 0;`，并在 `<= 0` 时记录一次错误（契约违例可见）。
- **K2（Medium）** API 类注释里的示例**编译不过**：
  `ModelInstance.java:28` `instance.setRootTransform(scale, yOffsetRadians);`、
  `:30` `instance.models()` 均不存在（真实 API 是 `setScale`/`setYawOffset`/`nodes()`）；
  `:57` 的 `{@link #setRootTransform}` 是坏链接。对一个“API 即产品”的模组，这是产品缺陷而非笔误。
- **K3（Medium）** README 的物料/渲染能力表与当前实现不符（B7）；README 同时把“渲染路径”描述成
  已废弃的 vanilla `VertexConsumer` 方案（`VanillaModelRenderer` 类注释仍写“it writes vertices into a
  `VertexConsumer` and lets vanilla own the program”，而实际实现调用自绘 GLSL 的 `ModelCpuRenderPath`）。
- **K4（Low）** `Model3D.java:36` 注释称“Model files live under `assets/` because that is the tree a
  dedicated server also loads”，与 README 实测结论（服务器只加载 `data/`，`assets/` 服务器看不见）**正好相反**。
- **K5（Low）** `ClientSetup.java:86-88` 每次启动打印 “GPU skinning, 128 joint matrices”，
  与当前 CPU 蒙皮路径和 `model_cpu` 着色器（无关节 uniform）矛盾。

### 3. 安全（Security / 不可信输入）— 7.5/10

这一维度**整体是好的**，且是本项目最扎实的一半：

- 正确做法：`AbstractModelSource.normalize()`（:225-254）先把 `\\` 归一为 `/`、**再**解码百分号转义、
  再逐段解析 `..`，越界即返回 null；`open()` 拒绝并给出可读原因。
- `Uris.unsupportedReason()`（:111-133）拒绝空串、绝对路径、UNC、带 scheme 的 URL，并在**解码后**判定。
- 解析层的错误矩阵覆盖了索引越界、稀疏访问器、`byteStride`、16 位索引上限、非三角图元、
  重复关键帧时间等（`GltfErrorTest` 等，24 个用例）。
- **S1（Low，重复策略）** `Uris.escapesRoot()`（:136）只按 `/` 切分，对 `..\` 视而不见；
  下游 `AbstractModelSource.normalize()` 会拦住它，所以今天不构成漏洞，
  但**同一策略两处实现、语义不同且没有一致性守卫**——将来任何一处被改动都会变成真漏洞。
  最小修复：让 `Uris.escapesRoot` 复用 `normalize()` 的判定，或直接删除前者。
- **S2（Low）** 未经核实：`TestModelCommand` 的权限等级、`ModelSyncPacket` 的收发方向与字段边界
  属于委派深挖范围，本次审查未亲自逐行确认（见“覆盖与未覆盖”）。

### 4. 资源（Resources）— 4/10

- **R1（=B4，High）** `ModelCpuRenderPath.disposeAll()` 无调用者：live 路径的 VAO/VBO 永不释放；
  按模型 id 累积。苏-30 一个模型的 VBO 约 `72732 × 24 B ≈ 1.7 MB`，重载/切换地图都不回收。
- **R2（Medium）** 静态 `MESHES` 与静态 `program` 在资源重载后**不复位**：
  `compileFailed`/`program` 一旦设定就不再重编，`ClientModelManager` 的类注释却声称
  “a reload may have changed the GLSL sources, so keeping the old program would make the reload
  silently do nothing”——该理由对**在用**的那个 program 并未实现。
- **R3（Medium）** 每帧 `ModelCpuRenderPath.fill()` 内 `new org.joml.Vector4f(...)`/`Vector3f`（:241,:243）
  以及 `VanillaModelRenderer.draw()` 内 `new float[4]`、`new ResourceLocation[materials.length]`、
  每帧重建 `ModelDrawList`（:86）——渲染热路径上的分配，逐顶点 1~2 个对象。
- **R4（Low）** `InstanceTransform.normalMatrix()` 与 `mirrorAxes()` 无调用者；
  `ModelCpuRenderPath.java:164-167` 是一个**只有注释的空 if 块**。

### 5. 并发（Concurrency）— 5/10

- 值得肯定：渲染线程契约写得清楚且有强制（`RenderSystem.assertOnRenderThread()` 出现在
  `advance()`、`onResourcesReloaded()`、`ModelCpuMesh.create()`、`ensureCompiled()`）；
  `pendingReload` 用 `volatile` + 帧边界消费，方向正确；`ModelInstance` 明确声明“非线程安全、仅渲染线程”。
- **N1（=B5，High）** `advance()` 在迭代中 `entries.remove(...)` → `ConcurrentModificationException`
  逃逸到渲染事件。最小修复：改 `iterator.remove()`（本方法 312 行已经是这么写的）。
- **N2（Medium）** `ModelHandle` 的计数器是 `AtomicInteger`，但 `acquire/release` 与
  `ModelLoadService` 的 map 操作不构成原子事务：计数归零与驱逐之间没有同步，
  类注释却说“Not thread-safe by itself beyond the counter”，把“计数器线程安全”误当成“生命周期线程安全”。
- **N3（未验证）** 热重载轮询线程与服务端命令线程的交互属于委派深挖范围，未亲自确认。

### 6. 可测试性（Testability）— 6/10

- 强项：解析/动画/数学层不依赖任何 Minecraft 类，可在纯 JUnit 下运行；
  `modelProbe`/`modelInspect` 是**同一份解析器**的离线入口；`commandParseCheck` 带**负例**；
  `eventBusCheck` 用静态属性检查总线误挂；`deployMod` 部署后**重新哈希**校验；
  fixture 由 `TestModelGenerator` 生成而非提交二进制。
- **T1（High）** 最关键的绘制路径**没有任何自动检查覆盖**：
  `shaderCheck` 编译 `ModelShaders`（死代码）、`meshUploadCheck` 上传 `GlMesh`（死代码），
  live 路径的着色器与 `CpuVertexWriter` 容量无人验证 → 204 个绿灯测试全部通过，客户端仍然崩。
  这是“绿灯不可信”的典型结构：测试覆盖的是被替换掉的那一半。
- **T2（Medium）** 验收 harness 自身漂移：`ClientSelfTest.DEFAULT_DISTANCE = 5.0`，
  其注释仍写“A model is scaled so its longest axis measures scale blocks - **4 by default**”，
  而默认归一化早已是 **40 格** → 5 格外相机在模型内部，harness 截出来的图**看不见模型**（本次实测正是如此）。
- **T3（Medium）** 验收运行不干净：测试世界 `New World` 会持久化每次 spawn 的实体，
  本次日志里一次会话同时存在 8 个历史实例（entity 15/19/20/21/22/23/24/25），
  运行结果因此依赖“之前跑过几次”，不是独立可重复的测量。
- **T4（Low）** 唯一跳过的测试 `ObjParserRealFileTest.parsesEveryRealObjInTheModelDirectory`
  在语料缺失时跳过（JUnit 报告中可见 `<skipped/>`，非静默通过），可接受；
  但项目文档把它计入“1 有意跳过”，实际语义是“语料不在本机时无覆盖”。

### 7. 清晰度（Clarity）— 5/10

这是最矛盾的一维：**文字质量极高**（几乎每个决策都写了原因、失败史、被否决的方案），
但**文字与代码的漂移是全项目性的**，且在“是 API 模组”这一前提下代价最大。

- **CL1** README 大量段落描述的是第一/第二次重写的渲染路径；`VanillaModelRenderer`、
  `RenderTestModelEntity`、`ModelRenderer` 的类注释各自描述不同世代的实现（同一事实四份说法）。
- **CL2** 死代码 6 类 1,868 行（9.7%），且 `ClientModelManager.java:89-98` 的注释仍把
  `ModelRenderer` 说成“still owns the GPU cache, the shader program and the texture uploads”。
- **CL3** `RenderTestModelEntity.java:143` 的日志文案 “ModelRenderer drew nothing”、
  `:147` “ModelRenderer asserts on GL and a failed shader compile throws” 指的是已不在路径上的类。
- **CL4** 类注释中可编译的示例、可达的 API、真实的行为三者不一致（K2/K5/K4）。
- 结论：注释的**意图**是资产，注释的**时效**是负债；本项目缺一次“以代码为准重写文档”的收敛，
  而 HANDOVER.md 第九节自己也列了这件事（“补齐文档 / 删除方案 1 遗留代码”）。

---

## 撤回（Retracted）

- 初读时怀疑 `Uris.escapesRoot()` 对 `..\` 的盲区可造成目录穿越（Windows 下读模型目录外文件）。
  追到 `AbstractModelSource.normalize()` 后**撤回为“重复策略（S1）”**：该函数先把 `\\` 归一为 `/`
  再逐段解析 `..`，越界返回 null，实际拦截成立。
- 初读时怀疑 `run/hs_err_pid*.log`（`EXCEPTION_ACCESS_VIOLATION` in `Unsafe.getLong`，渲染线程）
  是当前路径的缺陷。核对时间戳后**撤回**：两份日志均为 2026-09-29 17:43/17:45，属第一次重写的遗留，
  当前路径（9-30 13:39 之后）没有出现同类 JVM 级故障。
- 初读时怀疑 `run/crash-reports/crash-2026-09-29_*.txt`（`Exception ticking world`，
  fastutil `Long2LongOpenHashMap` NPE）与本模组有关。**撤回**：栈内无任何 `com.model3d` 帧，
  现有证据不支持归因。

---

## 站得住的部分（What is right）

- **解析层**：glTF 2.0（含 GLB 容器、稀疏访问器、`byteStride` 交错、归一化整数、TRIANGLE_STRIP/FAN
  转列表、CUBICSPLINE 的 3 值/关键帧语义、>4 骨骼影响取前四并重归一化）与 OBJ/MTL
  （负索引、平滑组、扇形三角化、`map_Bump`/`-s`/`-o` 选项、引号内空格、CRLF/BOM）实现完整，
  并有逐条对应测试；`GltfErrorTest` 的 24 个拒绝用例是本项目最有价值的部分。
- **错误哲学**：解析失败**返回 null 并记录原因**，从不把异常抛进渲染帧；
  `ClientModelManager.instanceFor()` 用 try/catch 把配置失败降级为“猪标记”，
  注释明确写了“an entity whose aircraft failed to load is still visible”。
  这是正确的取舍，也是它值得继续做下去的理由。
- **不可信输入**：越界、绝对路径、UNC、scheme、百分号解码顺序、JSON 嵌套上限、整数精确性
  都有显式处理与测试。
- **验证设施**：`commandParseCheck`（负例）、`eventBusCheck`（总线误挂——这正是它第一次真崩溃的根因）、
  `deployMod`（哈希校验“部署的 jar 就是构建的 jar”）、`TestModelGenerator`（fixture 可重生成）
  都是**同类模组里罕见的自觉**。这些设施的存在使 B1/B2 这类缺陷**可以被便宜地补上测试**：
  容量只需一个“索引数 vs 容量”的纯 JUnit 用例，着色器只需让 `shaderCheck` 也编译 `model_cpu.*`。
- **资源释放的意图**：`ModelCpuMesh.dispose()`、`CpuVertexWriter.free()`、`GlStateGuard`（遗留）
  都写了对称的释放点；缺的只是**把它们接到重载/卸载事件上**（B4）。

---

## 测试套件可靠性（Cut C 审计）

| 审计 | 结论 | 证据 |
|---|---|---|
| 确定性 | **未建立**：只跑了 1 次全量（204/0/0/1）。套件为纯 JUnit、无定时器/线程随机源，理论抖动面小，但“重复运行同结果”未被本审查证实 | `gradlew test` exit 0；`build/test-results/test/*.xml` 汇总 suites=20 tests=204 failures=0 errors=0 skipped=1 |
| 顺序无关性 | **风险低但未做换序实验**。唯一跨测试共享状态是 `Corpus.java:36-37` 的 `private static boolean resolved / Path root`（首个调用者决定 JVM 内的语料根）；另有一处墙钟依赖断言（`ModelLibraryTest` 与 `ModelLibrary.java:69` 的 1 秒缓存） | 深挖报告的 B3 段落；本人未独立复跑 |
| 空洞断言 / 覆盖死代码 | **有发现（3 处）**：① `WindingProbe::measure` **没有任何断言**，只有 `System.out.printf`，且语料缺失时直接 `return`，却被打成 PASS（本次运行的输出里确有 `WindingProbe > measure() PASSED`）；② `GltfCorpusTest.java:127-130` 拿 `Mat4.compose(...)` 与 `ModelNode.restLocalTransform` 比 —— 后者定义就是同一个 `compose` 调用（`ModelNode.java:66`），**恒真**，因此 B10 这个真实缺陷整套测试看不见；③ `GlMeshBufferCapacityTest` 专门保护已不在生产路径上的 `GlMesh` 容量算术，而 live 路径的同类算术（B1）无人测试 | 本人复核：`WindingProbe.java` grep 无 `assert`；`ModelNode.java:66` 与 `GltfCorpusTest.java:127-130`；`GlMeshBufferCapacityTest` 2 例 PASS + B6 调用链 |
| 能力（能红吗） | **对最关键的缺陷：不能**。把模型换成任何索引/顶点比 >3 的网格，204 个测试全绿而客户端崩（B1）；把 `compose` 的行列缩放改回去，测试也全绿（B10） | B1 的红跑与绿灯并存；B10 的 fixture 是对角矩阵 |
| 静默跳过 | **无伪装成通过的跳过**：唯一 skipped 在 XML 中显式可见；但 `model3d-corpus.properties`（`build.gradle:547` 指向的说明文件）并不存在（`src/test/resources` 为空目录），语料缺失时 3 处 `assumeTrue` 会静默降级为 skip 而非失败 | `<skipped/>` in `ObjParserRealFileTest.parsesEveryRealObjInTheModelDirectory`；深挖报告 B2 段落 |
| 计数口径 | **文档多算一个**：`HANDOVER.md:126` 写“**204 通过**，0 失败，1 有意跳过”，而 204 是**含跳过**的总数，实际是 **203 通过 / 204 总数 / 1 跳过** | 本人解析 20 个 XML：tests=204 failures=0 errors=0 skipped=1 |

**套件裁决**：作为**数据层**的回归网，它是可信且偏强的；作为**这个模组对外承诺的“能画出来”**的证据，
它**不具备证明力**——因为它验证的是被替换掉的渲染器。README 的“Verifying it”一节把这两件事混为一谈，
这是本项目最需要修正的认知，而不是代码。

---

## 覆盖与未覆盖（Coverage and non-coverage）

- **已审查（第一手，含引用与运行）**：`client/render/*`（CPU 路径全链）、`client/gl/*` 的调用者关系、
  `api/ModelInstance|ModelHandle|ModelScale`、`resource/AbstractModelSource|Uris`、
  `format/gltf/Uris`、`Model3D.java`、`ClientSetup/ClientEvents/ClientModelManager/ClientSelfTest`、
  `model_cpu.vsh/fsh`、`RenderTestModelEntity`、`VanillaModelRenderer`、`ModelDrawList`、`ModelCpuMesh`、
  `CpuVertexWriter`；`build.gradle` 全部验证任务；`README.md`/`HANDOVER.md` 的论断；
  `run/**` 的 4 次客户端运行日志、6 份崩溃报告、2 份 JVM fatal log、1 张实测截图。
- **未审查（第一手）**：`format/obj/ObjParser.java`（1,200 行）逐行、`format/gltf/GltfSceneBuilder.java`（38 KB）
  逐行、`format/json/*` 逐行、`animation/*` 与 `math/Mat4` 逐行、`common/command/*`、
  `common/network/*`、`common/entity/TestModelEntity`、`resource/ModelLoadService`（35 KB）逐行。
  这些文件**标注为 Not reviewed**：本次派出的并行深挖在交付时限内没有返回结果，
  因此本报告**没有**对这四块给出任何结论——上面提到它们的地方，依据只是测试清单、
  接口签名或调用点，已逐条标注。这也意味着：**评分中的“安全 7.5”“可测试性 6”是基于
  已读部分（`Uris`、`AbstractModelSource`、测试清单、验证任务）给出的下界判断，不是对整块的确认。**
- **本机无法复现/未验证**：视觉正确性（需人眼）；第三方语料（`models/` 中的苏-30 之外）的行为；
  真实专用服务器的验收运行（本次未跑 `runServer -Pmodel3dSelfTest`）；
  以及“同一次运行重复 3 遍看是否抖动”的确定性实验（时间预算内未做，故上表据实写“未建立”）。

---

## 并行深挖的发现（已复核）

本审查同时派出四路只读深挖（解析层 / 渲染层 / API 与资源层 / 动画与测试套件）。**只有本人复核过、能在本报告中引用行号与代码的条目才被采纳**，复核方式标注在每条的“复核”里。

**已采纳（本人复核）**

- **B8 / B9 / B10 / B11 / B12**（详见阻断表）：访问器“先分配后限流”、贴图路径未清洗即构造 `ResourceLocation`、
  `Mat4.compose` 缩放序错误、网络包 `count` 无上限且无方向约束、`texCoord` 从不读取。
- **B13（Medium）无贴图材质实际画成黑色，而两处日志声称“白色”。**
  `ModelCpuMesh.java:140` `GlStateManager._bindTexture(modelTexture == null ? 0 : modelTexture.getId());`
  —— 绑定 GL 纹理 0（不完整纹理）采样得到黑色；而 `VanillaModelRenderer.java:122-124` 打印
  “drawing it white (…)”，`EmbeddedTextures` 也自称“falls back to the missing-texture pattern”。
  同时 `VanillaModelRenderer.java:105` 的 `ResourceLocation fallbackTexture = embedded.white();` 是一个**未被使用的局部变量**。
  **复核**：本人先前的完整阅读与本次编辑均基于原文；两条日志与代码矛盾可直接对照。
- **B14（Medium）live 路径从不恢复 GL 程序绑定。**
  `ModelCpuRenderPath.java:273` `GlStateManager._glUseProgram(program);` 之后没有任何 guard/恢复
  （类里唯一的 `GlStateGuard` 属于已废弃管线）。原版 `ShaderInstance.apply()` 在 `programId == lastProgramId`
  时会**跳过重绑定**，于是后续原版批次可能带着本模组的 program 绘制、并把原版 uniform 写进本模组的槽位。
  **复核**：模组侧“无保护、无恢复”这半边由本人读码确认；原版 `ShaderInstance`/`GlStateManager` 的缓存行为
  依据深挖引用的 47.4.16 源码（rung 2），本人未离线反编译核对。
- **B15（Medium）热路径分配与重复构建。** 每次绘制 `new CpuVertexWriter(mesh.capacity())`
  （`ModelCpuRenderPath.java:142`，苏-30 即每次 72732×24 ≈ **1.7 MB 原生内存分配/每帧/每实体**）；
  每个顶点两个 JOML 对象（`:241,:243`，64755 顶点 ≈ 13 万对象/帧/实体）；`ModelDrawList.build()` 每帧
  被构建**两次**（`VanillaModelRenderer.java:86` 与 `ModelCpuRenderPath.java:124`）。
  **复核**：四处调用点本人均已读到。
- **B16（Low/流程）验证方向倒置。** `README.md` 把 `meshUploadCheck` 当作渲染路径已验收的证据，
  而它上传/绘制的是 `GlMesh`/`GlProgram`（7 个死类之一）；真正在绘制、且**已经崩过**的
  `CpuVertexWriter`/`ModelCpuMesh`/`ModelCpuRenderPath` 在 `src/test` 与 `src/verify` 中**零引用**。
  **复核**：本人 grep 的引用计数（`GlMesh` 测试/验证 3 处；live 五类 0 处）与深挖一致。

**未采纳 / 存疑（列出以示边界）**

- 深挖称 `Sampler2`（光照图）从未绑定。本人读到的序列是
  `GL13.glActiveTexture(GL_TEXTURE0+2)` → `lightTexture().turnOnLightLayer()`，而该调用在原版中会通过
  `bindForSetup` 绑定到**当前活动单元**，因此很可能确实绑定了。**本人无法在不运行游戏的情况下判定**，
  故不作为发现，也不计入评分。
- 深挖的以下条目本人未逐条复核，**不作为本报告的结论**（但行号与引文完整，值得按序跟）：
  **High 级线索**：`PackModelSource.java:75` 同类 `ResourceLocation` 构造崩溃（会把整个模型置为 null，而不是回退到小写重试）；
  `ModelDescriptor.java:127-128` 的 `pivot` 缺类型检查 → 一个手滑的 `model.json` 让模型**永久不可加载**，
  与 `ModelLoadService.java:696-698` 和 README 第 208-209 行的承诺相反；
  `ModelSyncPacket`/`ModelHandle` 生命周期与文档不符（F6/F7）。
  **Medium/Low 级线索**：`TestModelCommand.java:273` 用 animation[0] 覆盖描述符的 `autoAnimation` 具名选择；
  `ExternalModelPaths.java:21` 与 README 声称 `<gamedir>/model3d/` 也会热重载，但 `libraries()` 只返回一项；
  `ModelLibrary.java:355-357` 的 `catch (IOException)` 接不住 `Files.walk` 的惰性迭代异常（`UncheckedIOException`）；
  `PackModelSource` 对同一问题问两遍（`data`/`assets` 顺序并非由该循环决定）；
  `ObjParser` 面角 O(k²)、`ModelScene` 递归深度、`JsonParser` DOM 放大、`ImageTable` 双重解码、
  `AnimationReader` 旋转键未归一化、`AnimationState.setTime` 的 NaN 入口、`ModelSkinning` 无测试、
  `ModelBounds`/`Ids.lookupPath`/`ModelScale.forScene` 属死 API、`Model3D.java:35-36` 入口注释仍写 `assets/` 等。
  抽取这些需要各自的一次独立复核，**本报告不为它们背书**。

---

## 修复顺序（Fix order）

**P0（本次必须改，否则一切都是空谈）**
1. **B9**：贴图路径在构造 `ResourceLocation` 前清洗（小写 + 字符白名单，复用 `EmbeddedTextures.java:168-183` 已有的做法），
   或在 `resolveBaseColorId` 内捕获并回退到 `find()` 的小写重试。
   验收：一个材质引用 `Textures/Glass.png` 的 fixture 能加载并绘制，渲染循环不再抛 `ResourceLocationException`。
2. **B1/C1**：容量取自**索引总数**（`ModelDrawList` 增 `indexCount()`；或 `CpuVertexWriter` 可增长/写满即安全放弃）。
   补一个纯 JUnit 用例：对 8 顶点/36 索引的 fixture 断言 `capacity >= indexCount`。
   验收：`gradlew runClient -Pmodel3dClientTest=model3d:animated_test` 跑到第 80 tick 并 exit 0（当前崩溃）。
3. **B2/C2**：绘制前上传 `u_color`（至少 `(1,1,1,1)`；按材质用 `baseColorFactor` 更好）。
4. **B8**：先判 `accessor.has("bufferView")`，再决定是否分配；无 view 且超 `MAX_UNBACKED_ELEMENTS` 时抛既有的解析错误。
   验收：`count=536870912` 的无 view 访问器在 `-Xmx512m` 下得到 `ModelParseException`，而不是 `OutOfMemoryError`。

**P1（本轮）**
5. **B10**：`Mat4.compose` 改为缩放列（`r[c*4+i] *= scale[c]`），并补一个**非对角**旋转 + 非等比缩放的往返用例
   （现有 fixture 全是对角矩阵，永远测不出来）。
6. **B11**：`count` 加上界（拒绝负数与超大值）并改用带 `NetworkDirection.PLAY_TO_CLIENT` 的注册重载。
7. **B5/N1**：`entries.remove` → `iterator.remove()`。
8. **B4/R1**：把 `ModelCpuRenderPath.disposeAll()` 接到 `ClientModelManager.onResourcesReloaded()`；
   热重载时按新 `indexCount` 重建 mesh（否则 B1 会以“模型改大了”的形式复发）；`EmbeddedTextures.releaseAll()` 同样接线。
9. **B17**：`libraries()` 只构造一次 `ModelLibrary`，让签名缓存真正生效（当前是每 tick/每帧一次全目录 `Files.walk`）。
10. **B14/B13**：用（现成但未被使用的）`GlStateGuard` 包住 `issue()` 的 GL 序列，并恢复纹理单元；
    无贴图材质绑定那张 1×1 白图，而不是绑定纹理 0；同时改掉说“白/缺失图案”的两条日志。
11. **B6/B16**：删除 7 个遗留类（`ModelRenderer`、`GlMesh`、`GlProgram`、`GlStateGuard` 之外的 `GlTexture`、
    `AttributeSlots`、`ModelShaders`，以及 `ClientMeshCache` 的 GL 半边）；随后**把 `shaderCheck`/`meshUploadCheck`
    指向 `model_cpu.*` 与 `CpuVertexWriter`**。
12. **B3/K3**：README 的“Verifying it / 能力表 / 渲染路径说明”按当前实现重写；
    删掉与产物矛盾的“80 ticks without faulting”“真实 GLSL”“GPU skinning”“客户端会丢弃 GPU mesh”等表述。

**P2（下一轮）**
13. B7：把 `alphaMode`/`doubleSided` 真正接进 CPU 路径（每材质设置混合/剔除、按材质上传 `u_alphaMode`），
    否则把 README 的能力表降级为“未实现”。
14. B12/B18：`texCoord` 与 `model.json` 的 `textures` 别名要么实现、要么从文档删除（当前是“解析了但没人读”）。
15. K1 引用计数：`== 0` + 违例日志；并把 `ModelHandle`/README 里“最后一次释放会释放 GPU 资源”的说法改成事实。
16. T2/T3：验收 harness 按 40 格默认重算视距（或按模型实际 extent 自适应），每次运行前清理测试实体。
17. R3/B15：热路径去分配（复用 `Vector3f/Vector4f` 与 writer、`ModelDrawList` 每帧只建一次）。

**P3（技术债）**
18. C3/C4/CL1/CL2/CL4/K2/K4/K5/B16/F12：文档与代码对齐、删除死方法与空 `if`、修好 `{@link}` 与示例、
    消除 `scale == 1.0` 与 `mirror=xy` 两处哨兵/策略混用、修掉 `ModelLoadService.java:287` 恒为 `?` 的日志。

---

## 评分

| 维度 | 分数 | 一句话依据 |
|---|---|---|
| 正确性 | **2.5 / 10** | 自带 fixture 必崩；大写/空格贴图路径必崩；`u_color` 未上传；`Mat4.compose` 缩放序错误；物料/透明/双面声明与实现不符 |
| 契约 | **4 / 10** | 引用计数可重复释放；API 示例编译不过；README 能力表与实现分叉；坏 `model.json` 并非文档承诺的“忽略后仍可加载” |
| 安全 | **5.5 / 10** | 目录穿越/越界防护写得好且有测试，但**两条不可信输入可直达 OOM/异常**：访问器先分配后限流（B8）、网络包 count 无上限且无方向约束（B11） |
| 资源 | **4 / 10** | live 路径 GL 对象永不释放（B4）；热重载复用旧容量；每帧热路径分配 |
| 并发 | **5 / 10** | 线程契约写得清楚并强制，但 `advance()` 的迭代删除会把容错路径变成异常 |
| 可测试性 | **5 / 10** | 数据层测试网强、工具链自觉；但**最关键的绘制路径与蒙皮算术零覆盖**，一个无断言探针被计为“通过”，两处关键断言是恒真/盲区 |
| 清晰度 | **5 / 10** | 注释质量罕见地高，同时与代码的漂移是全项目性的（含 9.7% 死代码、注释与实现的缩放序矛盾） |
| **综合** | **4.4 / 10** | 数据层 6.5、渲染层 3.0、API 层 5.0、文档与验证可信度 5.0；**P0/P1 修完后约 7/10** |

**一句话**：这是一个“上半身很专业、下半身刚移植完还没走通”的模组——
它的问题不是不会写代码，而是**把“已通过的检查”当成了“已验证的功能”**：
204 个绿灯测的是被替换掉的渲染器，`checkExtras` 编的是被废弃的着色器，
而真正在画模型的那 400 行，在本次审查里崩了 1 次、在项目自己的产物里崩了 3 次。
把 B1/B2 修掉、把验证指向真正在跑的代码，这个项目立刻配得上 7 分以上。
