# Radiance: Minecraft 1.21.4 → 26.2 migration

Minecraft 26.2 is the first **de-obfuscated** release line: Mojang ships official
names directly, so Fabric Loom performs **no remapping** (no Yarn, no
intermediary, no Mojang mappings file). The mod was written against **Yarn /
1.21.4**, so every Minecraft reference must be translated to the official 26.2
names, and the heavily-rewritten render pipeline must be re-integrated.

## Status

| Layer | State |
| --- | --- |
| Build system (Gradle, Loom, deps, Java 25) | ✅ Done & verified — project configures, MC 26.2 provisioned/decompiled |
| Access widener | ⏳ Reduced to a valid `official`-namespace header; original Yarn entries parked (see below) |
| Source — leaf files (no render coupling) | In progress — 8 migrated & compiling clean |
| Source — render/blaze3d-coupled files | ❌ Not started — needs re-architecture against the new GPU API |

`bash ./gradlew build` provisions and decompiles MC 26.2 successfully; it fails
only in `compileJava` on un-migrated source files.

## Build-system changes (done)

- **Gradle wrapper** `8.14.1` → `9.5.1` (8.x cannot run on Java 25 — "Unsupported
  class file major version 69").
- **`gradle.properties`**: `minecraft_version=26.2`, `loader_version=0.19.3`,
  `loom_version=1.17.12`, `fabric_version=0.153.0+26.2`, added `sodium_version`
  /`iris_version`; removed `yarn_mappings`.
- **`build.gradle`**:
  - Loom plugin id must be the canonical **`net.fabricmc.fabric-loom`**. The legacy
    short id `fabric-loom` rejects the de-obf path with
    `Configuration 'mappings' has no dependencies`.
  - **Removed the `mappings ...` line entirely.** De-obfuscated → no mappings.
    (`loom.officialMojangMappings()` also fails: "Failed to find official mojang
    mappings for 26.2".)
  - `modImplementation` → **`implementation`**. With no remapping Loom does not
    register the `modImplementation` configuration.
  - Removed the `remapJar` / `remapSourcesJar` task tweaks (those tasks no longer
    exist); `jar` / `sourcesJar` now produce the final artifacts.
  - Java `21` → **`25`** (`release 25`).
  - Added the Modrinth maven (`https://api.modrinth.com/maven`) + `sodium`/`iris`.
- **`fabric.mod.json`**: added `"java": ">=25"`.
- **`radiance.mixins.json`**: `compatibilityLevel` `JAVA_21` → `JAVA_25`.

## Naming scheme

26.2 official names are essentially Mojmap **packages** with some Mojang-cleaned
class names — and a few that differ from classic Mojmap, so **verify every symbol
against the decompiled jar**, don't assume. Notably 26.2 keeps `Identifier`
(in `net.minecraft.resources`, *not* `ResourceLocation`).

Decompiled reference sources:
`./gradlew genSources` →
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.2/*-sources.jar`

## File partition (140 files)

- **21 "pure"** — no `net.minecraft`/`com.mojang` imports → compile as-is (Vulkan
  JNI proxies, config DTOs, shader definitions, `MixinPlugin`, some `*Ext` ifaces).
- **52 "leaf"** — Minecraft imports but no rewritten-render coupling → mechanical
  per-symbol translation (this table).
- **67 "render/blaze3d-coupled"** — need re-architecture (see below).

## Subsystems that were rewritten/deleted (the hard part)

These classes the mod hooks **no longer exist**; the mixins targeting them must be
redesigned, not renamed:

| Gone (Yarn) | Replaced by |
| --- | --- |
| `client.gl.ShaderProgram`, `client.gl.CompiledShader`, `client.gl.GlUniform`, `client.gl.ShaderLoader` | `com.mojang.blaze3d.pipeline.RenderPipeline` + `com.mojang.blaze3d.opengl.{GlProgram,Uniform}` + `com.mojang.blaze3d.systems.GpuDevice` (UBO-based) |
| `client.render.chunk.ChunkBuilder`, `SectionBuilder` | new section render dispatch under `client.renderer.chunk.*` |
| `client.render.WorldRenderer` | `client.renderer.LevelRenderer` |
| `client.render.RenderPhase` / `RenderLayer$MultiPhase` system | `client.renderer.rendertype.RenderType` + `RenderPipeline` state |
| Font: `client.font.{FontStorage,GlyphAtlasTexture,BitmapFont,TrueTypeFont,UnihexFont,RenderableGlyph,BuiltinEmptyGlyph}` | `client.gui.font.{FontSet,FontTexture,...}`, `client.gui.font.glyphs.{BakedGlyph,SpecialGlyphs,...}`, providers under `client.gui.font.providers.*` |
| Block color `client.color.block.BlockColorProvider` | rewritten to `client.color.block.{BlockTintSource,BlockTintSources}` (API change) |
| `NativeImage.InternalFormat` | removed (texture upload moved to the `GpuDevice`/`GpuTexture` path) |

**Reference implementations** for the new pipeline (both ship as deps already):
Iris `26.2` branch (https://github.com/IrisShaders/Iris) and Sodium `mc26.2`
(https://github.com/CaffeineMC/sodium) — they do the same deep render integration.

## Verified method renames (seen so far)

| Yarn | 26.2 official |
| --- | --- |
| `StringIdentifiable.createCodec(E::values)` | `StringRepresentable.fromEnum(E::values)` |
| `StringIdentifiable#asString()` | `StringRepresentable#getSerializedName()` |
| `TranslatableOption` (interface) | removed — option enums now hold `int id` + `Component caption` (`ByIdMap` / `Component.translatable`) |
| `NativeImage#getColorArgb/setColorArgb` | `getPixel` / `setPixel` |
| `NativeImage#getFormat()` | `format()` |
| `ColorHelper.getAlpha(c)` / `getArgb(a,r,g,b)` | `ARGB.alpha(c)` / `ARGB.color(a,r,g,b)` |
| `TextRenderer#getWidth(String)` | `Font#width(String)` |
| `MinecraftClient#runDirectory` (field) | `Minecraft#gameDirectory` (field) |
| `GameOptions#language` (field) | `Options#languageCode` (field) |
| `LanguageManager#getLanguage()` | `LanguageManager#getSelected()` |
| `RenderableGlyph` (interface) | `com.mojang.blaze3d.font.GlyphInfo` |
| `ParticleTextureSheet` | `client.particle.ParticleRenderType` |
| `ResourceReloader#reload(Synchronizer, ResourceManager, Executor, Executor)` | `PreparableReloadListener#reload(SharedState, Executor taskExecutor, PreparationBarrier, Executor reloadExecutor)` — manager moved to `SharedState#resourceManager()`; `Synchronizer#whenPrepared`→`PreparationBarrier#wait` |
| `NativeImage.InternalFormat` (RED/RG/RGB/RGBA) | **removed** — only `NativeImage.Format` (RGBA/RGB/LUMINANCE_ALPHA/LUMINANCE) remains; the GL-internal-format path moved to `GpuDevice`/`GpuTexture` |

## Verified class mapping (Yarn → 26.2 official)

<!-- Each target confirmed to exist in the 26.2 merged jar. -->

```
net.minecraft.util.Identifier                       net.minecraft.resources.Identifier
net.minecraft.text.Text                             net.minecraft.network.chat.Component
net.minecraft.util.Util                             net.minecraft.util.Util
net.minecraft.util.StringIdentifiable               net.minecraft.util.StringRepresentable
net.minecraft.util.collection.IdList                net.minecraft.core.IdMapper
net.minecraft.util.math.BlockPos                    net.minecraft.core.BlockPos
net.minecraft.util.math.ChunkSectionPos             net.minecraft.core.SectionPos
net.minecraft.util.math.Vec3d                       net.minecraft.world.phys.Vec3
net.minecraft.util.math.ColorHelper                 net.minecraft.util.ARGB
net.minecraft.block.BlockState                      net.minecraft.world.level.block.state.BlockState
net.minecraft.world.BlockRenderView                 net.minecraft.client.renderer.block.BlockAndTintGetter
net.minecraft.world.LightType                       net.minecraft.world.level.LightLayer
net.minecraft.registry.Registries                   net.minecraft.core.registries.BuiltInRegistries
net.minecraft.particle.ParticleEffect               net.minecraft.core.particles.ParticleOptions

net.minecraft.client.MinecraftClient                net.minecraft.client.Minecraft
net.minecraft.client.option.GameOptions             net.minecraft.client.Options
net.minecraft.client.option.SimpleOption            net.minecraft.client.OptionInstance
net.minecraft.client.option.InactivityFpsLimit      net.minecraft.client.InactivityFpsLimit
net.minecraft.client.WindowEventHandler             com.mojang.blaze3d.platform.WindowEventHandler
net.minecraft.client.WindowSettings                 com.mojang.blaze3d.platform.DisplayData
net.minecraft.client.util.Window                    com.mojang.blaze3d.platform.Window
net.minecraft.client.util.Monitor                   com.mojang.blaze3d.platform.Monitor
net.minecraft.client.util.VideoMode                 com.mojang.blaze3d.platform.VideoMode
net.minecraft.client.world.ClientChunkManager       net.minecraft.client.multiplayer.ClientChunkCache

net.minecraft.client.font.TextRenderer              net.minecraft.client.gui.Font
net.minecraft.client.font.BakedGlyph                net.minecraft.client.gui.font.glyphs.BakedGlyph
net.minecraft.client.gui.tooltip.Tooltip            net.minecraft.client.gui.components.Tooltip
net.minecraft.client.gui.widget.ClickableWidget     net.minecraft.client.gui.components.AbstractWidget
net.minecraft.client.gui.widget.CyclingButtonWidget net.minecraft.client.gui.components.CycleButton
net.minecraft.client.gui.widget.OptionListWidget    net.minecraft.client.gui.components.OptionsList
net.minecraft.client.gui.screen.option.GameOptionsScreen   net.minecraft.client.gui.screens.options.OptionsSubScreen
net.minecraft.client.gui.screen.option.VideoOptionsScreen  net.minecraft.client.gui.screens.options.VideoSettingsScreen

net.minecraft.client.particle.Particle              net.minecraft.client.particle.Particle
net.minecraft.client.particle.ParticleManager       net.minecraft.client.particle.ParticleEngine
net.minecraft.client.particle.ParticleTextureSheet  net.minecraft.client.particle.ParticleRenderType
net.minecraft.client.resource.VideoWarningManager   net.minecraft.client.renderer.GpuWarnlistManager

net.minecraft.client.texture.NativeImage            com.mojang.blaze3d.platform.NativeImage
net.minecraft.client.texture.AbstractTexture        net.minecraft.client.renderer.texture.AbstractTexture
net.minecraft.client.texture.TextureManager         net.minecraft.client.renderer.texture.TextureManager
net.minecraft.client.texture.NativeImageBackedTexture net.minecraft.client.renderer.texture.DynamicTexture
net.minecraft.client.texture.ReloadableTexture      net.minecraft.client.renderer.texture.ReloadableTexture
net.minecraft.client.texture.Sprite                 net.minecraft.client.renderer.texture.TextureAtlasSprite
net.minecraft.client.texture.SpriteContents         net.minecraft.client.renderer.texture.SpriteContents
net.minecraft.client.texture.SpriteAtlasTexture     net.minecraft.client.renderer.texture.TextureAtlas
net.minecraft.client.texture.MipmapHelper           net.minecraft.client.renderer.texture.MipmapGenerator
net.minecraft.client.texture.SpriteLoader           net.minecraft.client.renderer.texture.SpriteLoader
net.minecraft.client.texture.atlas.DirectoryAtlasSource          net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister
net.minecraft.client.texture.atlas.SingleAtlasSource             net.minecraft.client.renderer.texture.atlas.sources.SingleFile
net.minecraft.client.texture.atlas.UnstitchAtlasSource           net.minecraft.client.renderer.texture.atlas.sources.Unstitcher
net.minecraft.client.texture.atlas.PalettedPermutationsAtlasSource net.minecraft.client.renderer.texture.atlas.sources.PalettedPermutations

net.minecraft.resource.Resource                     net.minecraft.server.packs.resources.Resource
net.minecraft.resource.ResourceManager              net.minecraft.server.packs.resources.ResourceManager
net.minecraft.resource.NamespaceResourceManager     net.minecraft.server.packs.resources.FallbackResourceManager
net.minecraft.resource.ReloadableResourceManagerImpl net.minecraft.server.packs.resources.ReloadableResourceManager
net.minecraft.resource.ResourceReloader             net.minecraft.server.packs.resources.PreparableReloadListener
net.minecraft.resource.ResourceReload               net.minecraft.server.packs.resources.ReloadInstance
net.minecraft.resource.ResourcePack                 net.minecraft.server.packs.PackResources
net.minecraft.resource.ResourceFinder               net.minecraft.resources.FileToIdConverter
net.minecraft.resource.InputSupplier                net.minecraft.server.packs.resources.IoSupplier
net.minecraft.resource.metadata.ResourceMetadata    net.minecraft.server.packs.resources.ResourceMetadata
```

Library imports unchanged: `com.mojang.logging.LogUtils`,
`com.mojang.serialization.Codec`, `com.mojang.datafixers.util.Pair`
(Yarn's `net.minecraft.util.Pair`), `net.minecraft.util.Unit`.

## Texture-tracking subsystem (GpuTexture re-architecture)

The mod's core GL→Vulkan bridge intercepted texture creation/upload to capture the
**int GL texture id** and feed it to its Vulkan backend. In 26.2 the GL texture
model is gone:

- `AbstractTexture` no longer has `int glId` / `bindTexture()` / `setFilter()` /
  `setClamp()` / `clearGlId()`. It now holds a `protected GpuTexture texture`
  (`com.mojang.blaze3d.textures.GpuTexture`), a `GpuTextureView`, and a `GpuSampler`.
- A `GpuTexture` is **backend-agnostic and created lazily by the `GpuDevice`**. On
  the OpenGL backend it is a `com.mojang.blaze3d.opengl.GlTexture`, whose
  `glId()` returns the GL name. (`GlTextureView.glId()` also exists.)
- `NativeImage.upload(...)` is gone — uploads go through `GpuDevice` /
  `CommandEncoder.writeToTexture`.
- `TextureManager.registerTexture(...)` → `register(Identifier, AbstractTexture)`.

**The primitive (implemented):** resolve the GL id from the backing `GpuTexture`:

```java
this.texture instanceof GlTexture gl ? gl.glId() : 0   // 0 = not yet created / non-GL backend
```

This is now provided by the two `AbstractTextureMixins`:
- `vulkan_render_integration/AbstractTextureMixins#radiance$getGlIDUnsafe()` (throws
  if unavailable) — the accessor the ~20 `getGlId()` call sites should switch to via
  `((IAbstractTextureExt)(Object)tex).radiance$getGlIDUnsafe()`.
- `vanilla_resource_tracker/AbstractTextureMixins#radiance$glId()` (returns 0 if
  unavailable) — base helper for the tracking mixins.

**Architectural shift required:** because the `GpuTexture` does not exist at
registration/upload time, the mod must **resolve the GL id lazily** (when the Vulkan
importer actually needs it) instead of eagerly capturing it in
`TextureTracker.textureID2GLID` at register/upload. Concretely:

- `TextureManagerMixins` / `SpriteAtlasTextureMixins` / `NativeImageBackedTextureMixins`
  / `ReloadableTextureMixins` / `SpriteContentsMixins` injected at `NativeImage.upload`
  to stamp a `targetID` — **obsolete**; replace with lazy `radiance$getGlIDUnsafe()`
  at the consumption sites, or a new inject at the `GpuDevice.writeToTexture` path.
- `TextureTracker.textureID2GLID` (Identifier→glId) **done** → `id2Texture`
  (Identifier→`AbstractTexture`); `TextureTracker.Texture` **done** → built from
  `GpuFormat`. Metadata capture **done**: `TextureUtilMixins` now hooks
  `GpuDevice.createTexture` RETURN (was `TextureUtil.prepareImage`).
- **Vulkan-substitution core — done (import model):**
  `vulkan_render_integration/TextureUtilMixins` now hooks `GpuDevice.createTexture`
  RETURN and imports the created `GlTexture` into Vulkan by `glId()` (via
  `TextureProxy.prepareImage(GpuFormat,…)`), instead of cancelling+substituting GL
  allocation. The shared handle is the real `GlTexture#glId()`.
- **Pixel-upload path — done:** `vulkan_render_integration/CommandEncoderMixins`
  (new) hooks `CommandEncoder.writeToTexture(GpuTexture, NativeImage, …)` HEAD and
  mirrors the upload to Vulkan via `TextureProxy.queueUpload`, keyed by
  `GlTexture.glId()`. Uses public `NativeImage.getPointer()`/`getPixelBytes()` (both
  now public), so no fragile `NativeImage` shadows are needed. (Was: `vri/NativeImageMixins`
  hooking the removed `NativeImage.uploadInternal`.)
- **PBR auxiliary textures — done:** `vrt/NativeImageMixins` (targetID/identifier/aux
  storage), `vri/NativeImageMixins` (`radiance$alignTo`/`getPointer`/aux-close;
  `getColor`/`setColor`→`getPixel`/`setPixel`, `pointer`→`pixels`, `getChannelCount`→
  `components`, `getAlphaOffset`→`alphaOffset`) and `AuxiliaryTextures` itself (allocate
  via `TextureProxy.prepareImage`, upload via `TextureProxy.queueUpload`,
  `applyToCopy`→manual fill, `findResources`→`listResources`, `Resource.open`,
  `AtlasSource.RESOURCE_FINDER`→`SpriteSource.TEXTURE_ID_CONVERTER`, `Identifier.of`→
  `fromNamespaceAndPath`). `AuxiliaryTextureReloader` moved to
  `PreparableReloadListener.reload(SharedState, …)`.
- **Sampler (filter/clamp) — done:** `vulkan_render_integration/ReloadableTextureMixins`
  hooks `ReloadableTexture.apply` RETURN (where both the sampler and the `GpuTexture`
  are set) and mirrors the decoupled `GpuSampler`'s filter/wrap to the Vulkan backend
  via `TextureProxy.setFilter`/`setClamp`, keyed by `GlTexture.glId()`. (Was: the removed
  `AbstractTexture.setFilter(ZZ)`/`setClamp(Z)` redirects.) `DynamicTexture`/`TextureAtlas`
  would need equivalent hooks if their Vulkan sampling must match.
- **Screenshot readback — done:** `Screenshot.takeScreenshot(RenderTarget, int,
  Consumer<NativeImage>)` (async, was the synchronous `ScreenshotRecorder.takeScreenshot(
  Framebuffer) -> NativeImage`). `ScreenshotRecorderMixins` builds a render-target-sized
  image, fills it from the Vulkan backend (`radiance$loadFromTextureImageWithoutUI` →
  `RendererProxy.takeScreenshot`), hands it to the callback and cancels MC's GL readback.
  `RendererProxy` migrated too (dropped the removed `RenderSystem.apiDescription`;
  `Window.getHandle`→`handle`; unused `VertexFormat` import removed).

**The texture-tracking subsystem is fully ported to the 26.2 GPU model** (GL-id
resolution, registration, allocation/import, metadata, pixel upload, PBR aux textures,
sampler, screenshot readback), with the obsolete `targetID`-stamping mixins retired.

The auxiliary PBR data (specular/normal/flag `NativeImage`s stashed via
`INativeImageExt`) is largely independent of the GL path and can be retained.

**Retired (obsolete):** the `targetID`-stamping mixins that pre-stamped a GL id onto
each texture/sprite's `NativeImage` before upload — `NativeImageBackedTextureMixins`,
`ReloadableTextureMixins`, `SpriteAtlasTextureMixins`, `SpriteMixins`,
`SpriteContentsMixins` — plus the now-orphaned `ISpriteExt`/`ISpriteContentsExt`.
`CommandEncoderMixins` keys the Vulkan upload by the destination `GlTexture.glId()`
directly, so no pre-stamping is needed. (`INativeImageExt#radiance$getTargetID/
setTargetID` is kept for now: `AuxiliaryTextures` and the deferred glyph/overlay
mixins still set it; it becomes vestigial once those move to the
`CommandEncoder.writeToTexture` hook too.)

## Native-contract assessment (portable vs blocked)

The mod's Java mixins mostly **capture MC data and hand it across JNI** to the native Vulkan
`core` (not in this repo). A subsystem is portable from the Java side iff the Java→native
*contract* (the `native` method signatures) survives the MC API change — the native side sees
raw ids/pointers/packed bytes, not MC types.

| Subsystem | Native contract | Verdict |
| --- | --- | --- |
| **Textures** | glId + pixel pointers + `VkFormat` | ✅ preserved → **done** |
| **`BufferProxy`** (vertex/index buffers, world/sky/overlay uniforms) | `queueUpload(ptr,id)`, `initializeBuffer(id,size,usage)`, `updateWorldUniform(ptr)` … all primitives/pointers | ✅ preserved → **portable (moderate)**: re-extract from `MeshData` (`getBuffer`→`vertexBuffer`, `DrawParameters`→`DrawState`, `DrawMode`→`PrimitiveTopology`, `getVertexSizeByte`→`getVertexSize`) + matrices/`Camera`/`Fog`; drop/re-map `RenderPhase.setupGlintTexturing` |
| **`ChunkProxy`** | `initNative`, `rebuildSingle`, `isChunkReady`, `relocateSingle` … pure primitives | ✅ contract preserved, but the *extraction* drives MC's **rewritten** chunk builder (`ChunkBuilder`/`SectionBuilder`/`ChunkRendererRegion` → `SectionRenderDispatcher`/`SectionCompiler`) → **portable but heavy** |
| **`EntityProxy`** | `queueBuild(...)`, `build()` | ✅ contract preserved, but the *extraction* uses the **rewritten** `VertexConsumer` API + `VertexConsumerProvider`→`MultiBufferSource` + entity dispatch → **portable but heavy** |
| **Shaders** | opaque uniform **blob** (`draw(…, uniformPtr, uniformSize)`) + shader file paths | ✅ **preserved → portable** (see below; the native side is uniform-model-agnostic) |

**None of the render subsystems are native-blocked.** After reading the native source (MCVR,
`../MCVR`), every JNI contract is a raw ids/pointers/**opaque byte-blob** interface, independent
of MC's types. So the whole mod is portable from the Java side against the *existing* native
`core`; each subsystem is just coupled to the corresponding MC-subsystem rewrite (vertex
consumers, chunk rendering, shader capture).

## Shader / render-pipeline subsystem (portable — native contract is model-agnostic)

The mod **virtualizes** MC's GL shader pipeline: `CompiledShaderMixins`/`ShaderProgramMixins`
intercept `CompiledShader.compile` and `ShaderProgram.create`/`set` to build GL-less "virtual"
objects that carry the **GLSL source** + per-uniform/sampler/format metadata, which its native
Vulkan `core` then translates to SPIR-V (`ShaderRegistry`/`ShaderProxy`/`ShaderTranslator`).

**Native contract (from MCVR `com_radiance_client_proxy_vulkan_ShaderProxy.cpp`):**
`registerShader(shaderKey, vertexFormatType, drawMode, uniformSize, vertexShaderPath,
fragmentShaderPath, defines[])` — shader **file paths** + a **total uniform byte-size**; and
`draw(vertexId, indexId, shaderId, indexCount, indexType, uniformPtr, uniformSize)` — an
**opaque uniform blob** that native just `memcpy`s into a UBO. The blob's layout is defined by
the mod's own `ShaderDefinition` (`ShaderProxy.createUniform` writes each `ShaderField` at its
offset); native never sees individual uniforms. **So MC's individual-`GlUniform`→UBO change does
not touch the native contract** — the Java side just reads the values from a different place and
packs the same blob.

**Remaining Java-side re-architecture (portable):**
- Re-capture GLSL **source** at `GlDevice.getOrCompileShader`/`GlShaderModule`/`ShaderSource`
  (was `CompiledShader.compile`).
- Re-capture **program** creation at `GlProgram.link`/`GpuDevice.precompilePipeline`
  (was `ShaderProgram.create`/`set`).
- Read uniform **values** from 26.2 sources — `RenderSystem.getModelViewMatrixCopy()` (direct
  `Matrix4f`), `getProjectionMatrixBuffer()`/`getDynamicUniforms()`/the fog UBO (std140
  `GpuBufferSlice`s) — instead of individual `GlUniform.floatData`, packing into the mod's
  `ShaderDefinition` layout in `ShaderProxy.createUniform`.
- `ShaderTranslator` (done) already emits the mod's Vulkan GLSL from the captured source/fields.

Old note (superseded): "blocked on native code" — that was before reading MCVR; the native side
is uniform-model-agnostic, so this is a normal (large) Java port like the texture subsystem.

26.2 replaced that entire system:
- `CompiledShader`→`com.mojang.blaze3d.opengl.GlShaderModule` (compiled via
  `GlDevice.getOrCompileShader(Identifier, ShaderType, ShaderDefines, ShaderSource)`).
- `ShaderProgram`→`GlProgram` (`GlProgram.link(vertex, fragment, VertexFormat[], label)`) inside
  a `RenderPipeline` (`GpuDevice.precompilePipeline(RenderPipeline, ShaderSource)`).
- **Uniforms are now UBO blocks** (`Projection`/`Lighting`/`Fog`/`Globals`, see
  `GlProgram.BUILT_IN_UNIFORMS`) — not individual `GlUniform`s. `GlUniformMixins`/the mod's
  per-uniform fields (`modelViewMat`, `projectionMat`, …) map onto the old individual-uniform
  model, which no longer exists.

**This is the crux and it is blocked**, for two reasons:
1. The **UBO uniform-model change** alters the Java→native data contract (the backend receives
   uniform *blocks*, not individual `GlUniform` buffers).
2. The **native Vulkan `core` (C++/JNI) is not in this repository** (`src/main/native` absent;
   `libcore.so`/`core.dll` are gitignored binaries). That is where the captured GLSL/uniform data
   is consumed and translated to SPIR-V, so the Java capture cannot be re-architected to the UBO
   model without the native side + a coordinated contract redesign.

Re-architecting the Java capture would mean re-hooking `GlDevice.getOrCompileShader` (source),
`GlProgram.link`/`GlDevice.compilePipeline` (program), and re-expressing uniforms as UBO blocks —
but the shape of what to hand the native backend is a native-side decision. **Done here:**
`ShaderTranslator` (`VertexFormat.getAttributeNames()`→`getElements().map(name)`), which is pure
GLSL/format string work. The capture mixins, `ShaderRegistry`, `ShaderProxy`, and the
`I{ShaderProgram,GlUniform,CompiledShader}Ext` interfaces remain blocked as above.

## Does the native backend (MCVR) need 26.2 updates?

Evaluated `../MCVR` (v0.1.5, same line as this mod; no 26.2 work yet). **Very likely no
functional/version changes needed**, because MCVR is decoupled from MC specifics:
- **JNI contracts are opaque** (ids/pointers/byte-blobs) — independent of MC types.
- **Vertex layouts are byte-identical.** MCVR (`vertex_formats.cpp`) expects
  position `R32G32B32_SFLOAT`, color `R8G8B8A8_UNORM`, uv0 `R32G32_SFLOAT`, uv1/uv2
  `R16G16_SINT`, normal `R8G8B8A8_SNORM` — exactly 26.2's `DefaultVertexFormat`
  (`RGB32_FLOAT`/`RGBA8_UNORM`/`RG32_FLOAT`/`RG16_SINT`/`RG16_SINT`/`RGBA8_SNORM`).
- **Overlay shaders are translated by the Java side at runtime** and referenced by path;
  MCVR ships only its own post/world/RT shaders (no hardcoded MC uniform names). Texture
  data is fed as `VkFormat` (preserved). World height params (`sizeY`, `bottomSectionCoord`)
  are runtime args; unchanged in 26.2. No MC version string anywhere in MCVR.

**When MCVR *would* need touching:**
1. **Rebuild** against regenerated JNI headers **iff the Java port changes any `native`
   signature.** The port so far preserves them (only Java-side overloads changed); if the
   vertex-consumer/chunk/entity rewrites must add/alter a native method, MCVR's matching
   `Java_…_method` must be updated + rebuilt.
2. New native methods for any 26.2 render feature the mod newly hooks.
3. Runtime semantic mismatches (static analysis can't catch e.g. a changed lightmap encoding
   or chunk-geometry packing) — verify at runtime once the Java side renders.

Bottom line: the existing MCVR binary should work with a Java-only 26.2 port as long as the
`native` signatures stay identical; MCVR only needs a rebuild (not a rewrite) if they change.

## GUI / options cluster

26.2 rewrote the GUI to a **render-state extraction** model: screens override
`extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, delta)` (not `render(DrawContext,…)`);
`GuiGraphicsExtractor` is the new immediate-mode-ish context (`text(font, c, x, y, color, true)`
for `drawTextWithShadow`, `centeredText`, `blit(RenderPipeline, Identifier, …)` for `drawTexture`,
`setTooltipForNextFrame`, `pose()` returns a **2D** `Matrix3x2fStack`). Widgets renamed
(`ButtonWidget`→`Button`/`.dimensions`→`.bounds`, `ClickableWidget`→`AbstractWidget`,
`TextFieldWidget`→`EditBox`:`setText`→`setValue`/`setChangedListener`→`setResponder`/`getText`→
`getValue`/`setTextPredicate` removed, `SliderWidget`→`AbstractSliderButton`,
`AlwaysSelectedEntryListWidget`→`ObjectSelectionList` with `Entry.extractContent(...)` +
`getContentX/Y/Width/Height`). Input events refactored:
`mouseClicked/Dragged/Released(double,double,int,…)` → `mouse*(MouseButtonEvent event, …)` with
`event.x()/y()/button()`. Misc: `TextRenderer`→`Font`, `addDrawableChild`→`addRenderableWidget`,
`close()`→`onClose()`, `Minecraft.setScreen`→`Minecraft.gui.setScreen`, `Text`→`Component`,
`Formatting`→`ChatFormatting` (`Style.withFormatting`→`applyLegacyFormat`).

**Done (recipe):** `ShaderPackSettingsScreen`, `ModuleAttributeScreen`, `AttributeWidgetUtil`,
`ShaderPackScreen` (list widget + input events + legacy formatting).

**OptionInstance work:**
- `PotentialValuesBasedCallbacksNoValue` — **done**: `SimpleOption.CyclingCallbacks` →
  `OptionInstance.CycleableValueSet` (`validate`→`validateValue`, `getValues()`→
  `valueListSupplier()` via `CycleButton.ValueListSupplier.create`; the custom
  `getWidgetCreator` write/callback moves to `OptionInstance#onValueUpdate` at the caller).
- `CategoryVideoOptionEntry` — **retired**: `OptionsList.Entry`/`AbstractEntry`/`HeaderEntry`
  are all `protected` (can't subclass), but `OptionsList.addHeader(Component)` is public and does
  exactly what this custom category-header entry did → replace `body.addEntry(new
  CategoryVideoOptionEntry(text, body))` with `list.addHeader(text)`.
- `VideoOptionsScreenMixins` — **remaining, entangled**: a large `SimpleOption`→`OptionInstance`
  rewrite (~6 option constructors: `ValidatingIntSliderCallbacks`→`OptionInstance.IntRange`,
  `emptyTooltip`/`enumValueText`/`ofBoolean`, `GameOptions` getters→accessors, `Monitor`/
  `VideoMode`/`Window` API, `OptionListWidget` add-methods→`OptionsList.addBig/addSmall/addHeader`)
  **and** it constructs `RenderPipelineScreen` (render-coupled, below) — so it can't compile until
  that is ported.

**Actually render-coupled (not pure-GUI):** `RenderPipelineScreen` (calls
`IDrawContextExt.radiance$drawOrientedQuad` — the deferred `DrawContextMixins` `VertexConsumer`
path — plus `Screen.renderables` is now private and its manual *scaled-canvas* rendering /
child `mouseClicked` calls need rework), `DrawContextMixins` (shadows `DrawContext`'s 3D
`PoseStack`/`VertexConsumerProvider`, gone from `GuiGraphicsExtractor`), `ScreenMixins`
(`applyBlur`/`Framebuffer` redirect). These belong with the render subsystems.

## Access widener

The original 40+ entries are Yarn-named and many target now-deleted classes
(`RenderPhase`, the `GlStateManager` nested states, `ChunkBuilder`, `HeldItemRenderer`,
`SkyRendering`, …). It was reduced to a valid `accessWidener v2 official` header so
the project configures; the original is preserved at
`<scratch>/radiance.accesswidener.orig`. Re-add entries in the `official` namespace
only as the classes they reference are confirmed to survive.

## Files migrated so far (compile clean — verified by `compileJava`)

- `client/RadianceClient`, `client/pipeline/{Module,Pipeline}`, `client/gui/ModuleNode`
- `client/option/{DLSSMode,DenoiserMode,UpscalerQuality,UpscalerType}`
- `client/texture/{IdentifierInputStream,MipmapUtil,EmissionRecorder}`
- `client/proxy/world/PlayerProxy`
- `mixin_related/extensions/vanilla_resource_tracker/{IGlyphAtlasTextureExt,INativeImageExt,IRenderableGlyphExt}`
- `mixin_related/extensions/vulkan_render_integration/{IBlockColorsExt,INativeImageExt,IOverlayTextureExt,IParticleManagerExt}`
- `mixins/vulkan_render_integration/ClientChunkManagerMixins` (→`ClientChunkCache.onLightUpdate(LightLayer,SectionPos)`)
- `mixins/vulkan_render_integration/ParticleMixins` (`Particle` x/y/z shadows survive)
- `mixins/vanilla_resource_tracker/NamespaceResourceManagerMixins` (→`FallbackResourceManager`; `ResourcePack`→`PackResources`, `InputSupplier`→`IoSupplier`)
- `mixins/vulkan_options/GameOptionsScreenMixins` (→`OptionsSubScreen`; shadow `body`→`list`:`OptionsList`, `gameOptions`→`options`:`Options`)
- `mixins/vulkan_render_integration/AbstractTextureMixins` + `mixins/vanilla_resource_tracker/AbstractTextureMixins` — re-architected to the `GpuTexture`/`GlTexture.glId()` model (foundation of the texture-tracking subsystem; see below)
- `client/constant/VulkanConstants` — `VkFormat#getNativeImageInternalFormat()` (removed `InternalFormat`) → `getGpuFormat()` (`com.mojang.blaze3d.GpuFormat`)
- `client/texture/TextureTracker` — `Texture` built from `GpuFormat`; `textureID2GLID` (dead) → lazy `id2Texture` (`Identifier`→`AbstractTexture`)
- `mixins/vanilla_resource_tracker/TextureManagerMixins` — `registerTexture`→`register`; records the texture for lazy GL-id resolution
- `mixins/vanilla_resource_tracker/TextureUtilMixins` — retargeted `TextureUtil.prepareImage(InternalFormat,…)` (removed) → `GpuDevice.createTexture(…, GpuFormat, …)` RETURN, capturing the `GlTexture` id + metadata into `GLID2Texture`
- `client/proxy/vulkan/TextureProxy` — `prepareImage(InternalFormat,…)` → `prepareImage(GpuFormat,…)` (via the canonical `VkFormat.fromGpuFormat`)
- `mixins/vulkan_render_integration/TextureUtilMixins` — **Vulkan-substitution core** re-architected to the *import* model: hooks `GpuDevice.createTexture` RETURN and imports the created `GlTexture` into the Vulkan backend by `glId()` (was: redirect `TextureUtil.generateTextureId()`/`prepareImage` to cancel+substitute GL allocation)
- `mixins/vulkan_render_integration/CommandEncoderMixins` (new) — **pixel-upload path**: hooks `CommandEncoder.writeToTexture(GpuTexture, NativeImage, …)` and mirrors the upload to Vulkan via `TextureProxy.queueUpload` (was: `vri/NativeImageMixins` on the removed `NativeImage.uploadInternal`)
- `mixins/vulkan_render_integration/ReloadableTextureMixins` (new) — **sampler path**: hooks `ReloadableTexture.apply` RETURN and mirrors the `GpuSampler` filter/wrap to `TextureProxy.setFilter`/`setClamp` (was: the removed `AbstractTexture.setFilter`/`setClamp` redirects)
- `mixins/vanilla_resource_tracker/{NativeImageMixins}` + `mixins/vulkan_render_integration/{NativeImageMixins}` + `client/texture/{AuxiliaryTextures,AuxiliaryTextureReloader}` — the PBR auxiliary-texture path
- `mixins/vulkan_render_integration/{ScreenshotRecorderMixins}` + `client/proxy/vulkan/RendererProxy` — screenshot readback
- `client/vertex/{PBRVertexFormatElements,PBRVertexFormats}` — vertex-format foundation (see Vertex path below)

## Vertex path (partial)

The custom PBR vertex pipeline is a deep rewrite. Foundation **done**:
- `PBRVertexFormatElements` — Yarn `VertexFormatElement.register(id, uv, ComponentType,
  Usage, count)` → 26.2 `GpuFormat` constants (the element record is now just
  `(name, offset, GpuFormat)`; no id/Usage/ComponentType).
- `PBRVertexFormats` — `VertexFormat.builder()` → `builder(0)`, `add(name, element)` →
  `addAttribute(name, GpuFormat)`; the removed `Builder#skip(4)` is an explicit 4-byte
  `Padding` attribute.

**Still a ground-up rewrite** (removed/rewritten APIs, not mechanical renames):
- `client/vertex/PBRVertexConsumer` (602 L) — built on the removed **`RenderPhase`/
  `RenderLayer.MultiPhase.phases`** state system and the removed **element-id model**
  (`element.id()`/`getBit()`/`getRequiredMask()`/`format.has`), plus the renamed
  `VertexConsumer` methods (`vertex`/`color`/`texture`/`overlay`/`next` →
  `addVertex`/`setColor`/`setUv`/`setUv1`/…). `BuiltBuffer`→`MeshData`,
  `BufferAllocator`→`ByteBufferBuilder`, `VertexFormat.DrawMode`→`Mode`.
- `client/vertex/{StorageVertexConsumerProvider,StorageOutlineVertexConsumerProvider}` —
  `VertexConsumerProvider`→`MultiBufferSource`, `RenderLayer`→`RenderType`.
- `client/proxy/vulkan/BufferProxy` — `RenderPhase`, `BuiltBuffer`→`MeshData`, `Fog`,
  `Camera`, `ClientWorld`→`ClientLevel`.

These need the shader/`RenderPipeline` design (how the mod's custom attribute scheme and
render-state derivation map to the new GPU model), so they are best done with the
shader/render-pipeline subsystem.

### Deferred leaf files (need API-shape changes, not just renames)

- `client/gui/PotentialValuesBasedCallbacksNoValue` — `SimpleOption`→`OptionInstance`
  callback API rewrite.
- `client/util/BlockColorEmissionProvider` — `BlockColorProvider` removed
  (block color → `BlockTintSource`).
- The remaining ~24 `mixins/*` files mostly hook **rewritten subsystems**, so they
  need re-architecture rather than renaming (verified against the decompiled jar):
  - **Texture tracking** (`AbstractTexture`, `TextureManager`, `NativeImageBackedTexture`,
    `ReloadableTexture`, `Sprite*`, `NativeImage`, `MipmapHelper`): the mod hooks
    `AbstractTexture.getGlId()`/`bindTexture()` and `NativeImage.upload(...)`, all
    **removed** — textures are now `GpuTexture`/`GpuTextureView` via `GpuDevice`.
  - **Font / glyph — RETIRED (obsolete in 26.2).** The mod's glyph mixins
    (`FontStorage`/`GlyphAtlasTexture`/`BuiltinEmptyGlyph`/`BitmapFontGlyph`/`TtfGlyph`/
    `UnicodeTextureGlyph` + `IGlyphAtlasTextureExt`/`IRenderableGlyphExt`) intercepted the
    old GL glyph-upload (`bindTexture`/`getGlId`/`NativeImage.upload`, target-id stamping).
    In 26.2 `FontTexture extends AbstractTexture`: the glyph atlas is a normal `GpuTexture`
    created via `GpuDevice.createTexture` and glyphs upload via
    `CommandEncoder.writeToTexture(GpuTexture, NativeImage, …)` — i.e. exactly the path the
    **texture subsystem already tracks** (`TextureUtilMixins` import + `CommandEncoderMixins`
    upload mirror). So all 8 font files were deleted, like the `targetID`-stamping mixins.
  - **Atlas sources** (`Directory`/`Single`/`Unstitch`/`PalettedPermutations`):
    `AtlasSource.SpriteRegions` **removed** → `SpriteSource.run(ResourceManager, Output)`.
  - **`VideoOptionsScreenMixins`** — built on the `SimpleOption` API
    (`ValidatingIntSliderCallbacks`, `emptyTooltip()`, `ofBoolean`, `enumValueText()`),
    now the structurally-different `OptionInstance` API.
  - **`WindowMixins`** — the `Window` constructor was rewritten for the new
    `GpuBackend` (7 args incl `boolean`/`GpuBackend`, reordered; the 6 redirected
    `glfwWindowHint` ordinals + GL-context calls no longer exist).
  - **`MipmapHelperMixins`** — `getMipmapLevelsImages(NativeImage[], int)` →
    `MipmapGenerator.generateMipLevels(Identifier, NativeImage[], int, MipmapStrategy, …)`.
  - **block color** (`BlockColorsMixins`), **particles** (`ParticleManagerMixins`),
    **resource reload** (`ReloadableResourceManagerImplMixins`).
