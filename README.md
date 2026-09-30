# Model3D Loader API

A Minecraft **1.20.1 Forge** mod that loads common interchange 3D model formats at runtime, plays the
animation data those files carry, and draws the result on ordinary entities.

It is an **API mod**, and this repository is **the loader only**: it ships no entity, no command, no
model files and no test fixture. The deliverable is a documented, testable surface that another mod
calls to attach a `.glb` or `.obj` to its own entity.

## This mod's responsibility, in one line

Given a model name, find the file, parse it, keep one copy of the result, and draw it for every
entity that carries it - on the client, through Minecraft's own vertex pipeline, with skinning and
animation on the CPU.

What that means concretely:

| In this repository | Not in this repository |
|---|---|
| glTF/GLB and OBJ/MTL parsers, the JSON reader, the matrix/vector maths | Any entity, any command |
| The scene and animation model, the animation runtime | Any model asset, any generated fixture |
| Model resolution: `config/3dmodels/`, `data/`, `assets/`, the classpath index | The acceptance runs, the diagnostics tooling |
| The client render path: CPU skinning, one streamed VBO per model, the shader pair | A worked example of *using* the API |
| The sync contract (`ModelCarrier`, `ModelSync`, the packet) | |
| The public API surface and its offline verification tools | |

The worked example is a **separate mod**: [Model3D Loader Test Mod](../model3d_testmod). It owns the
test entity, `/testmodel`, and the unattended acceptance runs, and it is what keeps this mod honest -
it consumes nothing but the public surface, so if it builds and runs, the API is enough to load,
animate and draw a model from outside.

Implementing the API is one interface and two calls: implement `ModelCarrier` on your entity, then
render with `ClientModelManager.get().instanceFor(entity)` and
`ClientModelManager.get().vanillaRenderer().draw(...)`. See
[Using the API from another mod](#using-the-api-from-another-mod) for the whole recipe.

---

## What it does

| Capability | Status |
|---|---|
| glTF 2.0 binary (`.glb`) | yes |
| glTF 2.0 text (`.gltf` + external `.bin`/images) | yes |
| Wavefront OBJ + MTL | yes |
| glTF skeletal animation playback (STEP / LINEAR / CUBICSPLINE) | yes |
| CPU skinning (4 influences per vertex) | yes - more than 4 influences are reduced to the 4 heaviest and renormalised, see [Not supported](#not-supported) |
| PBR-ish materials: base colour, metallic/roughness, normal, occlusion, emissive | base colour texture, `baseColorFactor`, `doubleSided` and the alpha modes are applied; metallic/roughness, normal, occlusion and emissive are parsed and carried on `ModelMaterial` but not sampled - see [Material support](#material-support) |
| Alpha modes OPAQUE / MASK / BLEND | yes, per material, with the material's own `alphaCutoff`; no back-to-front sort between blended surfaces - see [Not supported](#not-supported) |
| glTF morph targets (`targets` / the `weights` animation path) | **no** 鈥?out of scope, see [Not supported](#not-supported) |

Zero hard dependencies beyond Forge: no Assimp, no LWJGL add-ons, and nothing added to the
dependency list. The glTF/OBJ parsers, the model maths (`math/Mat4`), the animation runtime and the
CPU skinning are all in this mod, which is why the parsers can run in a plain JUnit test with no
game. One qualification, because it used to be stated absolutely: the render path uses
**Minecraft's own bundled JOML** (`org.joml.Matrix4f`/`Vector3f`) to apply the pose stack to each
vertex - a library that is already on the classpath, not a new dependency, but not this mod's code
either.

---

## Seeing a model in game

This mod cannot spawn one: it has no entity and no command, deliberately. Install it together with a
mod that has a carrier and use that mod's command - for instance the companion test mod, whose
[README](../model3d_testmod/README.md) documents `/testmodel loader`, `/testmodel look`,
`/testmodel reload` and `/testmodel diag`, and whose client acceptance run is the end-to-end check of
this API.

The rest of this document is about the loader: what it reads, where it looks, what it does with the
result, and how to call it from your own mod.

---

## Texture formats

Embedded textures - the bytes inside a `.glb`/`.gltf` - are decoded by this mod through a JDK
`ImageIO` fallback into an RGBA image (`ImageDecode.toNativeImage`). That fallback exists
because Minecraft's own decoder, STB-backed `NativeImage.read`, is PNG-only 鈥?handed a JPEG it
fails 鈥?and because it fails on some of the large PNGs exporters emit, so the fallback is a genuine
second path rather than a redundant one.

So `.png`, `.jpg`/`.jpeg`, `.gif` and `.bmp` all work as embedded images inside a `.glb`/`.gltf`
(`ImageDecodeTest` feeds the decoder real encoded bytes of each). A texture *file* beside a model
is resolved to a resource path and bound through Minecraft's own texture manager, so it is decoded
by the game rather than here.

Two things are worth knowing when a model looks wrong:

- **A material with no texture is drawn white, not black.** A model whose materials name no image,
  or whose named texture resolved to nothing, is bound to a registered 1x1 white texture rather
  than to texture object 0, which is not "no texture": every sample would return black and the
  model would draw as a silhouette however correct its geometry is. It is white rather than the
  material's colour because the live draw uploads the shader's colour multiplier as neutral white;
  a per-material `baseColorFactor` is parsed and carried on `ModelMaterial` but is not applied yet
  (see [Material support](#material-support)). A material that *names* a texture which does not
  resolve is reported at WARN, naming the material and the path, so a miss is not silent.
- **Decoding a large texture costs time once.** Measured: a 4096脳4096 JPEG takes about 416 ms, a
  2048脳2048 about 106 ms. It is paid once, when the model's textures are first decoded, not per
  frame, so it shows up as a hitch the first time a model appears.

## Adding a model

**Drop a model into `config/3dmodels/`.** The mod creates that folder on first launch and writes a
`README.txt` inside it saying the same thing. Nothing needs restarting 鈥?a file added, edited or
deleted while the game runs is picked up within about a second, on both a client and a dedicated
server (the client additionally drops its loaded models, so the changed file is re-parsed and drawn
on the next frame).

The layout is deliberately forgiving. All of these work:

```
config/3dmodels/su30.glb                 ->  model name "su30"
config/3dmodels/My Plane.glb             ->  model name "my_plane"
config/3dmodels/su30/model.glb           ->  model name "su30"
config/3dmodels/su30/model.json          ->  optional settings for that model
config/3dmodels/su30/textures/*.png      ->  textures beside the model
config/3dmodels/jets/su30.glb            ->  model name "jets"
```

The model name is what a mod built on this loader passes to it - a spawn command's argument, an
entity's synced model id, a `<namespace>:<name>` in a pack.

The rule is one sentence: **a folder containing a model file is one model, named after the folder;
a model file that no folder claims is one model, named after the file.** Everything under a model
folder belongs to it, so the model and its textures stay one entry.

Supported files are `.glb`, `.gltf` (with its `.bin` and images beside it) and `.obj` (with its
`.mtl`). File names are normalised into model ids 鈥?lower-cased, anything outside `[a-z0-9/._-]`
replaced by `_` 鈥?because a file name may legally contain characters a resource id may not.
`My Plane.glb` becomes `my_plane`. Two files normalising to the same name are both reported rather
than silently merged, since the alternative is a model that mysteriously never loads.

The three places a model can come from, in priority order:

1. `config/3dmodels/` 鈥?the folder above. First, so the documented answer to "where do I put this"
   is always right.
2. `<gamedir>/model3d/<namespace>/<name>/` 鈥?the original namespaced layout, still scanned, so
   models already placed there keep working. Use it when you need a namespace other than `model3d`,
   which a folder under `config/3dmodels/` cannot express.
3. `data/<namespace>/model3d/<name>/` inside a mod jar, datapack or resource pack, with
   `assets/<namespace>/model3d/<name>/` tried as a fallback.

Everything in a model directory is resolved relative to it: a `.gltf` finds its `.bin` and its
images, and an `.obj` finds its `.mtl`, which in turn finds its textures. Path resolution is
case-insensitive and tolerates the folder names exporters invent (`Textures/Glass_Cockpit.jpeg`
resolves against `textures/glass_cockpit.jpeg`), reporting every fallback it took. A reference
that escapes the model directory 鈥?`../../../etc/passwd` 鈥?is refused, not followed: a model file
is untrusted input.

### Why `data/` comes first, and why that is not cosmetic

`assets/` is a **client** concept. A dedicated server builds its resource manager with
`PackType.SERVER_DATA`, which serves `data/` and nothing else. Measured through a live
`ResourceManager` on a dev server: `listResources("loot_tables")` = 1091 entries,
`listResources("recipes")` = 1174 鈥?and `listResources("models")` (the `assets` tree) = **0**.
A model stored under `assets/` therefore loads on the client and is invisible to the server,
which breaks the one property that justifies server-side resolution: a mod's spawn command
validating a name against the real file set, on the server, instead of leaving the client to fail
silently - or worse, to render the fallback.

### `files.txt` (generated)

A classloader cannot enumerate a directory, and a ForgeGradle development server has been observed
to enumerate a mod resource it then refuses to open 鈥?`listResources("model3d")` returned the model
file while `getResource` for that same location came back empty. So pack-hosted models carry a
generated index, `files.txt`, one path per line, written by the `generateModelIndex` Gradle task.
It is generated rather than hand-maintained because a stale index lists a texture that no longer
exists, and that failure looks like a parser bug.

Nothing to do by hand: dropping a model into `src/main/resources/data/<ns>/model3d/<name>/` and
building indexes it automatically. The external `<gamedir>/model3d/` path needs no index at all.

**Limitation this implies:** a pack-hosted model whose file has an unconventional name must name it
in `model.json`, because probing is limited to the descriptor plus `model.glb`, `model.gltf`,
`model.obj`. Arbitrarily-named loose files are discoverable only through the external directory
path. Use `<gamedir>/model3d/` while developing.

### `model.json` (optional)

Only needed when the file cannot speak for itself.

```json
{
  "model": "source/Su30 export version 2024_9_28.glb",
  "scale": 1.0,
  "pivot": [0, -0.5, 0],
  "yawOffsetDegrees": 180,
  "mirror": "none",
  "autoAnimation": "spin",
  "autoAnimationLoop": true,
  "textures": {
    "textures/glass-cockpit_5.jpeg": "textures/glass.png"
  }
}
```

| Field | Meaning |
|---|---|
| `model` | Which file to load when the directory holds several. Without it the loader prefers `model.glb`, then `model.gltf`, then `model.obj`, then any recognised file shallowest-first. |
| `scale` | A **multiplier on the automatic normalisation**, not an absolute size. The default normalisation brings the model's longest axis to **40 blocks**; `"scale": 0.5` means 20 blocks, `"scale": 2` means 80. Rejected if ≤ 0. |
| `targetBlocks` | The longest axis to normalise to, in blocks, **for this model alone**. Replaces the 40-block default instead of multiplying it, so a model that should be mob-sized can say `6` without knowing what the default is. Rejected if negative; omit it to use the default. |
| `pivot` | Model-space offset of the pivot, `[x, y, z]` in model units. |
| `yawOffsetDegrees` | `180` for a model authored facing +Z (the glTF convention) to face the way Minecraft entities are drawn. |
| `mirror` | Axis letters to negate before placing the model: any of `x`, `y`, `z`, or `none`. Omit it to use the loader's default (`xy`, a half turn that corrects a model exported upside down *and* facing backwards - one fault, since two negated axes are a rotation). A value that names no axis is rejected rather than ignored. `-Dmodel3d.mirror=…` overrides every model, for comparing two settings on one build. |
| `autoAnimation` | Animation to start on spawn. A model's first animation is often a static pose, so picking by name beats picking by index. |
| `autoAnimationLoop` | Default `true`. |
| `textures` | Path replacements, keyed by the path *as written in the model file*. Lets you repaint a model without editing it. |

A malformed `model.json` is reported and then ignored 鈥?the model still loads by
auto-detection rather than becoming unloadable because of one bad character in a side file.

### Sizing: why the default is 40 blocks, not 4

Interchange formats carry no unit. A glTF aircraft is authored in metres or centimetres, an OBJ
exported from a modelling package may be in anything, and the sample in this repository has a
fuselage **279 units** long. At scale 1 that is 279 blocks: it swallows the render distance and gets
frustum-clipped into invisibility, which reads as "nothing rendered". So the loader normalises - it
scales a model so its longest axis measures `targetBlocks` - and `scale` is then a relative
adjustment on top.

The default target was originally **4 blocks**, argued from Minecraft's own scale: a pig is about 1
block long, a player 1.8 tall, an iron golem 2.7. The comparison was sound and the conclusion was
wrong, because models are not mobs. The Su-30 at a 4-block target came out **4.0 by 0.38 by 0.62
blocks** - a long, extremely flat aircraft. From any normal distance that is a sliver, and from the
downward angle a player's camera usually has it is almost exactly edge-on, so it was invisible in
practice while being drawn perfectly.

That cost a great deal of misdiagnosis: the model uploaded, its textures decoded, its matrices were
correct and all 22 of its primitives were drawn every frame, while its measured on-screen extent was
single-digit pixels. Every report of the problem described "the model did not load". **A default that
makes correct work look broken is the wrong default, whatever the numbers say about pigs.**

40 blocks makes the same model a landmark - plainly an aircraft, and still small enough to see whole
from a few blocks away. A model that should be mob-sized says so per model, without touching code:

```json
{
  "model": "prop.glb",
  "targetBlocks": 2
}
```

The effect is measurable rather than a matter of opinion: with tracing on
(`-Dmodel3d.traceDraw=true`) the log reports the model's on-screen extent in normalised device
coordinates, where `-1..1` is the viewport. The same Su-30 five blocks from the camera measures
`0.42 x 0.93` at the 40-block target, against `0.07 x 0.09` at 4 blocks.

---
## Using the API from another mod

The public surface is:

| Package / type | What it is for |
|---|---|
| `com.model3d.loader.api` | `ModelCarrier`, `ModelSync`, `ModelHandle`, `ModelSummary`, `ModelInstance`, `ModelScale`, `ModelBounds` |
| `com.model3d.loader.resource.ModelLoadService` | loading, releasing, and the folder/layout accessors |
| `com.model3d.loader.client.ClientModelManager`, `client.render.VanillaModelRenderer` | the client entry points: the instance per entity, and the draw |
| `com.model3d.loader.tools` | offline tools: model inspection, the synthetic-fixture generator, and the event-bus checker (`EventBusCheckTool` - point it at your own compiled classes; a subscriber on the wrong bus is never called, silently) |
| `com.model3d.loader.Model3D` | the mod id and its logger |

Everything else - `format`, `scene`, `math`, `json`, `animation`, `client.gl`, and `resource` apart
from `ModelLoadService` - is **internal shape**: its classes are reachable but their types are not
part of the contract, and they change between releases. `ModelHandle#scene()` is the one such type
still exposed, marked as internal at its declaration; `ModelHandle#summary()` is the supported way to
read a model.

### 1. Implement `ModelCarrier` on your entity

```java
public class MyModelEntity extends PathfinderMob implements ModelCarrier {
    @Override public boolean hasModel()          { return modelId() != null; }
    @Override public ResourceLocation modelId()  { /* your synced data */ }
    @Override public String animationName()      { return null; }   // null = descriptor default
    @Override public boolean isAnimationLooping(){ return true; }
    @Override public float modelScale()          { return /* synced, 0 = not set */ 0.0f; }
    @Override public String[] animationNames()   { return /* synced list */; }
    @Override public void applyModelDescription(ResourceLocation id, float scale, String animation,
                                                boolean loop, List<String> animations) {
        /* store what the server sent; this is the client-side handler of the sync packet */
    }
}
```

That is the whole contract, and implementing it is what makes the rest automatic: the client creates
an instance on first draw, advances the animation, applies the scale and pivot, swaps the instance
when the model changes, releases it when the entity is removed, and re-describes the entity to a
player who starts tracking it.

### 2. Attach a model, on the server

```java
ModelHandle handle = ModelLoadService.INSTANCE.acquireServer(
        server.getResourceManager(), new ResourceLocation("yourpack:your_model"));
if (handle != null) {
    float scale = ModelScale.forHandle(handle);              // blocks per model unit
    List<String> animations = handle.animationNames();       // names, in file order
    ModelSummary summary = handle.summary();                 // counts + size, no internal types
    float radius = ModelScale.boundingRadiusBlocks(handle);  // for your culling box
    myEntity.setModel(handle.name(), scale, animations);
    handle.release();                                        // the server holds only the description
}
```

`acquireServer` returns `null` on absence or parse failure and logs the reason. There is no exception
path by design: "this entity's model is broken" must not take down the frame that draws it.

`ModelHandle` is reference-counted and shared: two hundred entities flying the same aircraft hold one
copy of the mesh. `acquire` adds a reference and `release` drops one; the last release is what lets
the model be evicted.

### 3. Render it, on the client

Register one renderer for your entity type (mod bus, `Dist.CLIENT`), then draw through the API:

```java
ModelInstance instance = ClientModelManager.get().instanceFor(entity);
if (instance == null) {                 // no model, or it failed to load: your fallback
    super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    return;
}
poseStack.pushPose();
try {
    // The placement convention a Minecraft entity renderer expects. The model's own scale, pivot and
    // yaw offset are already in the instance, so they must not be applied here as well.
    poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw));
    poseStack.scale(-1.0F, -1.0F, 1.0F);
    ClientModelManager.get().vanillaRenderer().draw(instance, poseStack, entity.modelId(),
            buffer, packedLight, OverlayTexture.NO_OVERLAY);
} finally {
    poseStack.popPose();               // in a finally-block: an unbalanced stack corrupts the frame
}
```

Two calls, and you never touch the scene, the vertex layout, the shader or the GL state - the render
path owns all of it, including saving and restoring what it changes.

### 4. Sync

`ModelSync.send(carrier, player)` describes a carrier's model to one player. The API already does it
when a player starts tracking a `ModelCarrier`, so call it yourself only when the model changes after
the entity was first sent. The other side is `applyModelDescription` on your carrier.

### 5. Sizing

`ModelScale.forHandle(handle)` is blocks per model unit: automatic normalisation (longest axis to
`DEFAULT_TARGET_BLOCKS`, 40) times the descriptor's multiplier. `longestAxisBlocks(handle)` and
`boundingRadiusBlocks(handle)` are that scale already applied, which is what a culling box or a
spawn-distance check wants. `handle.summary()` returns a `ModelSummary`: nodes, meshes, primitives,
triangles, materials, skins, images, `longestExtent`, whether it is skinned, and each animation's
name, track count and duration.

### Worked example

The companion test mod in `../model3d_testmod` is the reference implementation. Its entity implements
`ModelCarrier`, its renderer is the block above, and its unattended acceptance run is what proves
that this surface is enough to load, animate and draw a model from outside this mod.

---

## Material support

What the live draw applies today, per material:
- the **base colour texture** (`baseColorTexture` / MTL `map_Kd`), or the registered 1x1 white
  texture when the material names none;
- **`baseColorFactor`** (and MTL `Kd`) as the shader's colour multiplier, uploaded per material
  group - so a tinted material is tinted;
- the **alpha mode**, per material: `OPAQUE` ignores alpha (a fully transparent texel is still
  discarded), `MASK` discards below the material's **`alphaCutoff`** (default 0.5), and `BLEND`
  enables blending, in a second pass after the opaque materials. All modes keep depth writes on:
  a material that declares BLEND but whose texels are opaque is common (exporters mark whole
  models BLEND), and with depth writes off every such surface stops occluding, so the model's own
  interior shows through its skin. The cost is the other half of the usual trade - geometry drawn
  after a blended surface is hidden behind it - which is why blended materials go last;
  The draw also **enables the depth test itself** (measured: it is invoked with `GL_DEPTH_TEST`
  off), so the model is occluded by terrain and by entities standing in front of it instead of
  painting over them;
- **`doubleSided`**, by turning GL culling off for that material (and on for the others) - for
  every alpha mode, so a single-sided translucent skin is still culled;
- the **overlay texture** (`Sampler1`; bound and sampled, but the entity renderer passes
  `OverlayTexture.NO_OVERLAY`, so the red hurt flash does not appear today);
- the **block + sky lightmap**, so the model is lit by the light where it stands, and **linear fog**
  against Minecraft's own fog start/end/colour/shape.

Parsed and carried on `ModelMaterial` - readable by API consumers, not applied by the live draw
yet: `metallicFactor`/`roughnessFactor` and `metallicRoughnessTexture` (and MTL `Ns`),
`normalTexture` + `normalScale` (and MTL `map_Bump`/`bump`/`normal`), `occlusionTexture` +
`occlusionStrength`, `emissiveTexture`/`emissiveFactor` (and MTL `Ke`), and
`textureInfo.texCoord` above 0 (a material that asks for `TEXCOORD_1` or `TEXCOORD_2` is warned
about once per file and sampled from `TEXCOORD_0`, because the mesh carries one UV set).

From MTL, parsed into those same fields: `Kd` into `baseColorFactor`, `Ke` into `emissiveFactor`,
`Ns` into `roughnessFactor` via `clamp(sqrt(2 / (Ns + 2)), 0, 1)`, `d`/`Tr` into the alpha (with
`d < 1` selecting BLEND on the material), `map_Kd` into the base colour texture, and
`map_Bump`/`bump`/`normal` into `normalTexture`. Of those, `Kd` (tint), `d`/`Tr` (alpha mode),
`map_Kd` and the normal map's *presence* reach the live draw; the normal map is not sampled yet.
Texture paths with option flags (`-s 1 1 1 -o 0 0 0 file.png`), spaces and Windows backslashes are
parsed correctly.

`model.json`'s `textures` map is applied: a key is the path *as written in the model file*, and the
value replaces it before resolution, so a model can be repainted without editing it.

Not honoured, and reported rather than silently dropped: `map_Ka`/`map_Ks`, `map_d`, `illum`, and
glTF morph targets.

## Not supported

- **The material features that are parsed but not applied.** Metallic/roughness, the normal,
  occlusion and emissive textures and factors, and `texCoord` above 0 all stop at `ModelMaterial`;
  see [Material support](#material-support).
- **glTF morph targets** (`targets` on a primitive, and animation channels whose target path is
  `weights`). The data is parsed past, not applied.
- **`KHR_materials_*` extensions**, except that an extension only listed in `extensionsUsed` is
  ignored with a warning; an extension in `extensionsRequired` fails the load by design, since
  loading it without the extension would silently change the model.
- **Back-to-front sorting for BLEND materials.** A BLEND material blends, and blended materials are
  drawn after the opaque ones, but they are not sorted among themselves or against the camera, so two
  blended surfaces of the same model can still be wrong against each other. Depth writes stay on for
  them either way - see [Material support](#material-support) for why.
- **More than one skin per model.** `skins[0]` is applied; extra skins render in rest pose, with a
  one-time warning.
- **More than four influences per vertex.** Further `JOINTS_n`/`WEIGHTS_n` sets are read, but only
  the four heaviest influences are kept and renormalised, so the rest are dropped.
- **More than 65536 vertices in one primitive.** The index buffer is 16-bit; such a primitive
  fails closed with a clear message rather than being silently truncated.
- **Line and point primitives.** `l`/`p` in OBJ are skipped.

---

## Verifying it

Most of this mod can be checked without a display, and it is worth knowing which half is which.

```powershell
$env:JAVA_HOME="E:\Program Files\Java\jdk-17"
.\gradlew.bat build                                  # compiles, runs the test suite, builds the jar
.\gradlew.bat test                                   # parsers + animation, plain JUnit, no game
.\gradlew.bat checkExtras                            # the three verification tasks below
.\gradlew.bat deployMod                              # copy the jar into the game's mods/, hash-verified
.\gradlew.bat modelInspect --args="path/to/model.glb full"
```

This repository ships **no model assets** - it is the loader, not the cargo. There is no `models/`
directory in a fresh clone: the build does not need one (the corpus tests skip unless
`-Dmodel3d.corpus` points at a directory), and inspect/probe take whatever path you give them. For
local testing, drop your own files into a `models/` folder at the repository root: it is in
`.gitignore`, so they stay out of the repository and out of any pull request. The companion test mod
has a task that exposes such a folder to its dev runs as `model3d:su30`.

`deployMod` copies the built jar into a Minecraft instance and **verifies the copy by re-hashing what
landed**, defaulting to this machine's instance:
`gradlew deployMod -Pmodel3dGameDir="E:/.minecraft/versions/1.20.1-Forge_47.4.10"`. It exists because
the build output and the game are different places, and a stale jar there is indistinguishable from
a broken fix 鈥?a session running last hour's code reports last hour's failures and reads exactly
like the fix not working. That happened during development: a crash report was investigated as a new
bug when the deployed jar was half an hour older than the fix. The task prints the source and target
hashes so "did my change get there" is a fact rather than an assumption.

`checkExtras` covers the three things a compile and a test suite cannot: the shaders, the mesh upload
and the event-bus wiring. `build` deliberately does not depend on it - they need a GL driver and a
full Minecraft classpath, and a build that failed on a machine lacking either would be worse than one
that leaves them opt-in.

`shaderCheck` compiles the live shader pair the draw path loads
(`assets/model3d/shaders/model_cpu.vsh` and `model_cpu.fsh`) against a real OpenGL driver in an
invisible window, and asserts that every uniform, sampler and attribute name the live draw sets or
binds by name is actually active in the linked program 鈥?because a shader that fails to compile, or
a uniform the compiler optimised away, produces no runtime error at all: the setter gets `-1` and
silently does nothing, and the symptom is a model that does not appear. Exit code `0` pass, `1` fail
with the driver log, `2` when no GL context can be created, which is reported as **not** a pass.

`meshUploadCheck` goes one step further along the same path: in a real context it compiles the live
program through the renderer's own entry point, writes and uploads a triangle with the real vertex
writer and the live attribute layout, draws it with `glDrawArrays`, deletes it and checks
`glGetError` - and it round-trips the state guard the draw runs inside, reading the driver's state
back rather than trusting the guard's own bookkeeping. It lives in the renderer's package on
purpose, so it uses the live layout constants, writer and program instead of re-declaring its own
copy of them. This exists because a mesh upload in the retired custom-GL loader once
**shipped an off-by-one**: its buffer-name array was sized for the vertex attributes only, and the
index buffer was then written into it, so every upload wrote one past the end and the client crashed
on the first model it drew.
It compiled, the shaders linked, the server booted, the command succeeded, the entity spawned; only
a client drawing a mesh could show it, and the resulting stack pointed into the catch block, making
an off-by-one in the happy path read like a cleanup bug. The live path's capacity arithmetic is
pinned by `CpuVertexWriterCapacityTest`, which carries the same kind of control: the old
`uniqueVertices * 3` sizing really does throw `BufferOverflowException` on the shipped fixture,
while the current `indexCount()` sizing holds every vertex the fill loop writes - so the fix cannot
be "improved" into a guess.

The command grammar check is **not here**: this mod has no commands to check. It lives with the mod
that owns the tree (the companion test mod's `commandParseCheck`), and it still pins the failure that
put it there - a namespace-qualified model name used to fail because Brigadier's
`StringArgumentType.string()` stops reading at a colon, while unqualified names worked.

`eventBusCheck` verifies that every `@Mod.EventBusSubscriber` subscribes to events its declared bus
actually carries. This is the check that would have prevented the first crash report:
`EntityRenderersEvent.RegisterRenderers` is an `IModBusEvent`, but the class registering the entity
renderer declared `bus = FORGE`. Forge registers a subscriber on **one** bus, so the event never
arrived, the entity's renderer was never registered, and the client died on the first frame that drew
the entity with `NullPointerException: ... "entityrenderer" is null`. Nothing else could see it: the
mod compiled, the server booted, the command succeeded, the entity spawned.

`modelInspect` runs the **same parser classes the mod runs** against a real file and prints the
node tree, meshes, materials, skins and animation inventory 鈥?so a parser change is verifiable in
seconds, and when the numbers look wrong in game it separates "the file was read wrong" from "the
file was drawn wrong". Add `full` to dump every node and primitive.

### The in-game acceptance runs

They are not here either, for the same reason: they need an entity and a command. The companion test
mod owns them, and they are this API's end-to-end check - the client run joins a world, spawns a
model, enters the live draw path and holds it for 80 ticks without a fault, and the server run loads
a model through the real `ResourceManager` on a dedicated server and exits with a verdict. Run them
from that project; its [README](../model3d_testmod/README.md) has the commands.

What they cover that nothing here can: the four checks above are static or single-statement, and a
render path that faults *between* two statements passes all of them.

### What has NOT been verified

Stated plainly, because "compiles" is not "works":

- **Nothing has been seen rendered by a human.** The shader pair compiles and links on a real driver
  with every name the draw uses active (`shaderCheck`), the CPU mesh path uploads, draws and deletes
  with no GL error (`meshUploadCheck`), and a real client enters the draw path and survives 80 ticks
  (the test mod's acceptance run). What none of that covers is how the result *looks*: lighting,
  texturing, alpha ordering and the visual correctness of skinning are human judgements. One is worth
  naming on its own - **whether a surface reads as correctly lit in daylight** is unconfirmed; the
  lightmap fetch and the two light directions are exercised, the picture is not.
- **The `assets/` fallback for pack-hosted models.** Every model this project hosts lives under
  `data/`, and no automated run exercises a model found through the
  `assets/<namespace>/model3d/<name>/` fallback tree. A dedicated server cannot reach it at all - its
  resource manager serves `data/` only - which is the reason the order is `data/` first.
- **The external `<gamedir>/model3d/` path inside a game**: covered by the loader's directory branch
  and by unit tests, not by an automated run.
- **The corpus tests** need third-party assets that this repository does not ship
  (`-Dmodel3d.corpus=<dir>`); without them they skip, and a green run then says nothing about the
  large real-world models they exist for.