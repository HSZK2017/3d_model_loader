# 交接文档 — Model3D Loader（3D 模型加载 API 模组）

> **状态提示（2026-09-30 晚更新）**：本文档记录的是项目背景与开发历程，其中的进度与哈希
> **可能已过期**。当前事实以这几份为准：
> - [README.md](README.md) —— 对外说明与命令
> - [CODE_REVIEW_2026-09-30.md](CODE_REVIEW_2026-09-30.md) —— 审查发现的 B1–B18 / C / K / T / R 系列
> - [REPAIR_LOG_2026-09-30.md](REPAIR_LOG_2026-09-30.md) —— P0–P5 全部修复结论与证据
>
> 当前构建：`build/libs/model3d-1.20.1-1.0.0.jar` sha256 `F779CEB7…`；部署到游戏用
> `gradlew deployMod`（会重新哈希校验落地的那份）。"画面对不对"这个问题已经有了答案：
> 三次用户报告（蒙皮透出、图层错乱）都已定位并修复，详见修复日志的 P4/P5。
> 仓库**不含模型美术资源**：`models/` 是本地目录，已在 `.gitignore` 中。

- **日期**：2026-09-30
- **项目路径**：`E:\program\JAVA\3d_model_loader`
- **游戏实例**：`C:\Users\ASUS\AppData\Roaming\.minecraft\versions\1.20.1-Forge_47.4.10`
- **当前部署 jar**：见上方状态提示（用 `gradlew deployMod` 部署，避免旧 jar 混淆）

---

## 一、项目目标

做一个 **Minecraft 1.20.1 Forge 的 API 型模组**，把常见 3D 模型格式（GLB / glTF / OBJ）加载进游戏并播放模型自带动画。
必须注册一个**测试实体**证明 API 可用，实体**只能由命令生成**：

```
/testmodel loader <模型名> <x> <y> <z>
/testmodel look <模型名> [距离]     ← 生成在视线正前方（推荐）
```

不挂模型时用原版猪模型标示位置。

---

## 二、构建环境（每次构建都必须设置）

```powershell
$env:JAVA_HOME = "E:\Program Files\Java\jdk-17"
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"
cd E:\program\JAVA\3d_model_loader
```

| 项 | 值 |
|---|---|
| Forge | 47.4.16（用户实际游戏是 47.4.10） |
| MC / mappings | 1.20.1 / Parchment `2023.09.03-1.20.1` |
| 编译目标 | Java 17 |
| 构建 JVM | `org.gradle.java.home=E:/Program Files/Java/jdk-17`（**必须**，PATH 上的 JDK 26 无法驱动 ForgeGradle） |
| Gradle | 8.8 |

---

## 三、常用命令

```powershell
.\gradlew.bat build checkExtras           # 编译 + 单元测试 + 四项验证
.\gradlew.bat deployMod                   # 部署到游戏 mods\ 并校验哈希
.\gradlew.bat runClient -Pmodel3dClientTest=su-30_flanker -Pmodel3dClientTestDistance=30
.\gradlew.bat modelProbe --args="models/sukhoi_su-30_flanker_c.glb full"
.\gradlew.bat modelInspect --args="<模型路径> full"
.\gradlew.bat runServer -Pmodel3dSelfTest=model3d:animated_test
```

| 验证任务 | 作用 |
|---|---|
| `shaderCheck` | 在真实 GL 驱动上编译着色器，断言每个 uniform/attribute 名都激活 |
| `meshUploadCheck` | 真实驱动上传/绘制/删除网格（**不证明可见**，见第八节） |
| `commandParseCheck` | 命令语法（含拒绝用例） |
| `eventBusCheck` | `@Mod.EventBusSubscriber` 是否挂在正确的总线上 |
| `modelProbe` | **离线**读模型，验证顶点/索引/贴图引用是否完好 |
| `modelInspect` | 打印节点树、网格、材质、蒙皮、动画清单 |

---

## 四、模型放置位置

**`config/3dmodels/`**（模组自动创建并写入 `README.txt`）：

```
config/3dmodels/su30.glb            -> /testmodel loader su30 ~ ~ ~
config/3dmodels/My Plane.glb        -> 名字规范化为 my_plane
config/3dmodels/su30/model.glb      -> 文件夹即一个模型
config/3dmodels/su30/model.json     -> 可选设置
```

**热加载已实现**：文件夹每秒轮询，新增/修改/删除文件自动生效，无需重启。

`model.json` 支持的键：`model` / `scale`（相对倍率）/ **`targetBlocks`（本模型最长轴目标格数）** / `pivot` / `autoAnimation` / `autoAnimationLoop` / `yawOffsetDegrees` / `textures`。

> 归一化默认把模型最长轴缩放到 **40 格**。苏-30 原始 279 单位长 → 缩放系数 `0.1432212`。
> 觉得太大就写 `{"targetBlocks": 12}`。

---

## 五、渲染路径的三次重写（关键历史，避免重走）

| # | 方案 | 结果 |
|---|---|---|
| 1 | **自绘 GLSL 蒙皮**（`GlProgram` + `GlMesh` + `ModelShaders`） | **真实客户端里产出 0 像素**。所有 GL 调用都成功、无错误、矩阵正确、属性启用、贴图已绑 —— 屏幕上什么都没有 |
| 2 | **原版顶点管线**（`VertexConsumer` + `RenderType`） | 能画出连贯几何体，但朝向/光照需要自己重新推导（姿态栈翻转 + 实例变换 + 法线矩阵），每一项错了都是数小时的故障 |
| 3 | **CPU 蒙皮 + 动态 VBO + 移植着色器**（当前） | 移植自 `ysm_epicfight_compat` 的 cpu_skin 路径 |

**方案 3 是移植，不是自创**（用户明确要求，见下）。相关类：

| 类 | 职责 |
|---|---|
| `ModelCpuRenderPath` | 绘制主流程：GL 状态序列、uniform、光照/雾、按材质分组绘制 |
| `CpuVertexWriter` | 24 字节/顶点交错写入 + 材质区间记录 |
| `ModelCpuMesh` | 单个动态 VBO + VAO（固定属性布局）+ orphaning 流式上传 |
| `ModelSkinning` | 蒙皮算术（位置/法线/方向）集中一处 |
| `InstanceTransform` | 实例的缩放/朝向/轴心 + 轴向修正 + 法线反转 |
| `EmbeddedTextures` | 把 GLB 内嵌图片注册进 `TextureManager` |
| `VanillaModelRenderer` | 入口：解析每材质贴图 → 调 `ModelCpuRenderPath` |
| `ModelRenderer` / `GlProgram` / `GlMesh` / `ModelShaders` | **方案 1 的遗留**，已不在绘制路径上，待删除 |

### 移植来源与适配

源：`E:\program\JAVA\epic mod suitable\ysm_epicfight_compat`
（用户说明：这是他的发布版模组，网格渲染经大量用户环境验证）

移植了：着色器对、24 字节顶点布局、属性指针、`GL_STREAM_DRAW` + orphaning 上传、GL 状态序列、光照/雾/覆盖层逻辑。

**唯一的适配**：`#version 330 core` → `#version 150 core`（MC 1.20.1 基线是 OpenGL 3.2）。
连带影响：
- `layout(location = N)` **在 GLSL 150 不可用**（编译报错 `'location' : not supported for this version`）→ 改为 `glBindAttribLocation` 在链接前固定槽位（等价且确定）。
- 源项目 GPU 路径用 **SSBO + compute（需 GL 4.3）**，MC 1.20.1 基线不支持，**不可移植**；CPU 路径（GL 3.3）才是正确选择。

---

## 六、当前状态（中断点）

### 已确认可用

| 项 | 证据 |
|---|---|
| 单元测试 | **204 通过，0 失败，1 有意跳过** |
| `checkExtras` | 四项全 PASS |
| 模型数据完好 | `modelProbe` PASS：苏-30 = 22 图元 / 24244 顶点 / 21585 三角形 / 5 个内嵌贴图引用全部有效 |
| 内嵌贴图注册 | 6/6（5 张模型内嵌 + 1 张白色回退） |
| 命令 | `/testmodel loader`、`look`、`reload`、`diag` 均可用 |
| 热加载 | 实测：客户端运行中写入新模型 → 秒级自动检测并加载 |
| 朝向 | 用户截图确认飞机**位置和朝向都正常**（`2026-09-30_13.31.22.png`） |
| 着色器 | 移植的着色器对编译链接成功 |

### 最后一次运行（中断前）

```
Model3D: the CPU mesh shader compiled (port of the verified ysm_epicfight_compat cpu_skin path)
Model3D: CPU path drew 64755 vertices in 22 material group(s) for model3d:su-30_flanker
```

**顶点写入正常、22 个材质分组各发起了绘制。** 但**截图未取到**（命令在截图步骤前中断），所以：

> **⏸ 待验证：「画面是否显示正确的飞机」——这是下一步唯一要做的事。**

在此之前修复的一个真实 bug（同类问题可能还有）：`CpuVertexWriter` 一开始没有 `beginMaterial()` 调用，导致顶点写进了缓冲但**材质区间表为空**，日志显示 `drew 64755 vertices in 0 material group(s)` 而屏幕上什么都没有 —— 绘制调用一次都没发出。

---

## 七、下一步测试指令（恢复后照此执行）

### 1. 本机快速验证

```powershell
$env:JAVA_HOME = "E:\Program Files\Java\jdk-17"
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US -Dmodel3d.traceDraw=true"
cd E:\program\JAVA\3d_model_loader

# 准备模型（dev 环境读 run\config\3dmodels）
$dev = "run\config\3dmodels"
Remove-Item $dev -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $dev | Out-Null
Copy-Item "models\sukhoi_su-30_flanker_c.glb" "$dev\Su-30 Flanker.glb" -Force

Remove-Item "run\screenshots" -Recurse -Force -ErrorAction SilentlyContinue
.\gradlew.bat runClient -Pmodel3dClientTest="su-30_flanker" -Pmodel3dClientTestDistance=30 --console=plain *> run\t.txt
Get-ChildItem run\screenshots | Select-Object Name
```

看 `run\screenshots\*.png`。

**注意**：测试客户端会把相机放在玩家位置并朝视线方向生成模型。`-Pmodel3dClientTestDistance` 控制距离。
30 格对 40 格长的飞机比较合适；**太近会看到模型内部**（表现为"碎裂"，实际不是 bug）。

### 2. 游戏内确认（最终判定）

站平地、视线放平：

```
/testmodel look su-30_flanker
```

生成提示会报距离：
```
Model3D: spawned 'model3d:su-30_flanker' at ... 6.0 blocks away
```
超过 24 格会附警告。

请回答三件事：
1. **外形完整吗？**（完整飞机 / 碎片）
2. **朝向对吗？**（机头朝前、机腹朝下）
3. **光照和贴图对吗？**（迷彩涂装、有明暗层次）

### 3. 若朝向不对

**默认 `xy`（绕 Z 轴半周）**，可用系统属性逐轮试，无需重新构建：

```powershell
-Dmodel3d.mirror=none | x | y | z | xy | yz | xz
-Dmodel3d.invertNormals=false
```

| 设置 | 含意 |
|---|---|
| `z` | 绕 X-Y 平面镜像（单轴，会**打碎**几何体，实测） |
| `yz` | 绕 X 轴半周 |
| `xy` | 绕 Z 轴半周 —— **当前默认**，同时修"倒立"和"机头朝向" |

### 4. 若颜色不对

先跑 `modelProbe` 确认贴图引用，再看日志中 `registered embedded texture` 行（已提到 INFO 级，普通日志可见）。

---

## 八、调试手段与陷阱（重要）

### 可用的诊断开关

| 开关 | 作用 |
|---|---|
| `-Dmodel3d.traceDraw=true` | 每次绘制输出材质路径解析结果、顶点数、材质分组数 |
| `-Dmodel3d.traceVao=true` | 输出 VAO 属性装配实况（槽位/尺寸/步长/缓冲） |
| `-Dmodel3d.tracePixels=true` | 读回帧缓冲统计非背景像素（**旧管线遗留，新管线未接**） |
| `-Dmodel3d.traceRender=true` | 输出每次进入实体渲染器的调用 |
| `-Dmodel3d.mirror=` / `-Dmodel3d.invertNormals=` | 朝向/法线修正 |

### 无头测试环境（`runClient`）的两个坑 —— 我都踩过

1. **窗口太小 → GUI 缩放炸掉 → Game Menu 点不进去**
   已修：`build.gradle` 里客户端测试参数改为 `--width 1280 --height 720`。
   另加了**自动屏蔽暂停菜单**（`ClientSelfTest.onScreenOpening`），失焦时不再弹出遮挡画面。
   我一度把"菜单点不进去"误判成"客户端挂死"，浪费了大量时间。

2. **模型会下坠 / 生成到虚空**
   - 实体是 `PathfinderMob`，**有重力** → 已改为 `setNoGravity(true)`，标记物待在放置点。
   - **Y 太低会落到基岩层下方的虚空，那里没有光照 → 全黑。** 我一度把这当成"法线反转"的证据，改了光照代码 —— 是错的。
   - 测试时确认 Y 在地表之上。

### 我犯过的错误类型（供参考，避免重复）

| 错误 | 教训 |
|---|---|
| 拿**被日志级别过滤掉的 DEBUG 日志**当作"代码没执行"的证据 | 日志级别要先确认 |
| 用**自己配置不全的测试**得出"代码有 bug"的结论（两次） | 测试报错先怀疑测试 |
| 把**调用成功**当作**画面上可见**来汇报 | 唯一证据是截图/肉眼 |
| 把**"菜单点不进去"**判定为**"客户端挂死"** | 先看自己的参数 |
| 把**虚空无光照**当成**法线反转** | 先把模型放到有光的地方 |

**结论：这个项目里"日志显示正常"和"玩家看得见"之间反复出现巨大鸿沟。任何关于可见性的结论，必须以截图或用户肉眼确认为准。**

---

## 九、待办清单（按优先级）

1. **【最高】确认画面** —— 上面第六节那个"22 材质分组已绘制"是否真的显示为飞机
2. 删除方案 1 的遗留代码：`ModelRenderer`、`GlProgram`、`GlMesh`、`ModelShaders`、`GlStateGuard`
   （注意：`ClientMeshCache` 仍被 `EmbeddedTextures`/贴图解析引用，需一并梳理）
3. `VanillaModelRenderer.renderTypeFor` 等方案 2 的遗留方法已无调用者，可清理
4. 补齐文档：README 里渲染路径的说明需按方案 3 重写
5. 把 `README.md` 的"验证状态"章节更新为方案 3 的事实

---

## 十、关键文件索引

```
build.gradle                                  构建 + 全部验证任务 + deployMod
gradle.properties                             jdk-17 / forge 47.4.16 / mod_id=model3d

src/main/java/com/model3d/loader/
  Model3D.java                                模组入口
  format/                                     模型解析（glTF/GLB/OBJ + 自带 JSON 读取器）
  scene/                                      ModelScene / Node / Mesh / Primitive / Material / Skin / Image / Animation
  animation/                                  动画采样与姿态
  api/                                        ModelInstance / ModelHandle / ModelScale / ModelBounds
  resource/                                   ModelLocation / ModelLoadService / ModelLibrary(热加载) / ExternalModelPaths
  common/entity/TestModelEntity.java          测试实体（无重力、不可召唤）
  common/registry/ModEntities.java            实体注册（MobCategory.MISC）
  common/command/TestModelCommand.java        /testmodel loader|look|reload|diag
  client/render/                              ★ 渲染路径（见第五节表格）
  client/ClientModelManager.java              实例缓存 + 动画时钟
  client/ClientSelfTest.java                  客户端验收（自动进世界/生成/截图/退出）

src/main/resources/assets/model3d/shaders/model_cpu.vsh|fsh   ★ 移植的着色器
src/test/java/                                204 个单元测试
src/verify/java/                              shaderCheck / meshUploadCheck / commandParseCheck / eventBusCheck / ModelProbe / MeshUploadCheck
README.md                                     完整文档
ERROR/渲染问题诊断报告.md                        方案 1 失败的完整诊断记录
```

---

## 十一、交接时的一句话总结

模型解析、贴图、命令、热加载、实体注册**全部已验证可用**；渲染路径已完成第三次重写并**移植了用户验证过的算法**，最后一次运行日志显示"22 个材质分组已发起绘制"，但**画面尚未确认**。

**下一步只需做一件事：跑一次 `runClient` 取截图，确认飞机是否显示正确。**
