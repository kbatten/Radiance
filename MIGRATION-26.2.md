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

**Confirmed 26.2 hook points (from reading `GlDevice`/`GlProgram`/`ShaderProxy`):**
- **Source capture** → `@Mixin(GlDevice)` hooking `getOrCompileShader(Identifier id, ShaderType
  type, ShaderDefines defines, ShaderSource shaderSource)` (protected, all-public params) —
  **not** the private `compileShader(ShaderCompilationKey, ShaderSource)` whose `ShaderCompilationKey`
  record is private and unnameable by a mixin. Resolve the GLSL via `shaderSource.get(id, type)`,
  return a virtual `GlShaderModule` (public ctor `(shaderId, Identifier, ShaderType)` — no more
  reflection). Replaces `@Mixin(CompiledShader)`/`compile`.
- **Program capture** → `GlDevice.compileProgram(RenderPipeline, ShaderSource)` /
  `GlProgram.link` (replaces `ShaderLoader.createProgram` + `ShaderProgram.create`). Name/vertex
  format/sources come off the `RenderPipeline` + the captured shader modules.
- `ShaderProgram.create`/`set`/`bind`/`unbind`/`close` → `GlProgram` (the linked program + its
  `opengl.Uniform` Ubo/Utb/Sampler bindings). `GlUniformMixins`/`IGlUniformExt` **retire** (no
  per-uniform buffers).
- **The crux — `ShaderProxy.createUniform`:** it packs the mod's blob by iterating
  `ShaderDefinition.fields()` and reading each field's `GlUniform` value. In 26.2 there are no
  per-uniform buffers; each field's value must be sourced from the built-in UBOs (Projection =
  modelView+proj via `RenderSystem.getModelViewMatrix()`/`getProjectionMatrix()`; Lighting; Fog;
  Globals via `RenderSystem.getDynamicUniforms()`) and written at the field's offset — a
  by-name/semantic mapping of ~dozens of fields. This is the real design problem and the reason
  the subsystem is a coupled rearchitecture (not decomposable into independent commits like the
  chunk cluster); it needs a dedicated focused pass. `IShaderProgramExt.radiance$getUniformsValue()`
  (`List<GlUniform>`) is replaced by UBO-slice accessors.

**Uniform-mapping design (worked out — `createUniform` becomes a name-keyed resolver).**
`ShaderField.name` is MC's GLSL uniform name; in 26.2 each lives in one of five built-in UBOs.
The 26.2 struct layouts (std140):
- **DynamicTransforms** (`DynamicUniforms.Transform` = `mat4+vec4+vec3+mat4`): `ModelViewMat`,
  `ColorModulator`, `ModelOffset`, `TextureMat` — written **per-draw** via
  `getDynamicUniforms().writeTransform(modelView, colorModulator, modelOffset, textureMat)`.
- **Projection** (`ProjectionMatrixBuffer`): `ProjMat`.
- **Globals** (`GlobalSettingsUniform` = `ivec3+vec3+vec2+float+float+int+int`): camera block pos,
  camera fraction, `ScreenSize`, `GlintAlpha`, `GameTime` (`(gameTime%24000 + partialTick)/24000`),
  `MenuBlurRadius`.
- **Lighting** (`RenderSystem.getShaderLights()` slice): `Light0_Direction`, `Light1_Direction`.
- **Fog** (`FogData` via `RenderSystem.setShaderFog`): `FogColor`, `environmentalStart/End`,
  `renderDistanceStart/End`, `skyEnd`, `cloudEnd`, shape.

Field → source table (write at `field.offset()`, reuse the existing `putInts`/`putFloats`/
`putMatrix` incl. the `ProjMat` remap):

| ShaderField.name | 26.2 source |
| --- | --- |
| `ModelViewMat` | `RenderSystem.getModelViewMatrixCopy()` |
| `ProjMat` | RenderSystem projection matrix (accessor, else captured at `setProjectionMatrix`) + existing `mapProjectionMatrix` |
| `TextureMat` / `ColorModulator` / `ModelOffset` | **captured per-draw** at `DynamicUniforms.writeTransform` (defaults identity / white / 0) |
| `ScreenSize` | `Window` width/height |
| `GameTime` / `GlintAlpha` / `MenuBlurRadius` | `GlobalSettingsUniform` inputs (RenderSystem/`DeltaTracker`/Options) |
| `Light0_Direction` / `Light1_Direction` | captured at `RenderSystem.setShaderLights` |
| `FogColor` / `Fog*Start` / `Fog*End` / `FogShape` | captured at `RenderSystem.setShaderFog` (or read `FogRenderer`/`FogData`) |
| `Sampler0/1/2…` | existing `resolveSamplerTextureId` (`RenderSystem.getShaderTexture`) — unchanged |

**Capture hooks (small, replace the removed per-`GlUniform` reads):** mixin/redirect
`DynamicUniforms.writeTransform` (per-draw transform quad), `RenderSystem.setShaderLights`, and
`RenderSystem.setShaderFog` to stash the CPU-side values the resolver reads; matrices/window/time
come from direct accessors. This keeps `createUniform`'s per-field structure (only the *source* of
each value changes: `GlUniform` → resolver), which preserves forward-port alignment with 1.21.11.
Open detail to settle during impl: whether to read Lighting/Fog values from the captured slice
bytes (std140 offsets) or reconstruct them from `FogRenderer`/lighting setup — prefer capturing at
the `set*` call since it receives the data being uploaded.

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
- `client/vertex/PBRVertexConsumer` — the vertex-consumer keystone (see Vertex path below)
- `client/constant/Constants` — the enum→native-id mapping layer (see Chunk-rebuild cluster below)

## Chunk-rebuild cluster (26.2 architecture map)

`ChunkProxy` reimplements MC's chunk-rebuild loop (its own executors + queue) and drives MC's
section builder to extract per-layer geometry for the native backend. The whole 1.21.4 chunk
machinery it sits on was replaced, so `ChunkProxy` + `SectionBuilderMixins` +
`IChunkBuilder{,BuiltChunk}Ext` + `ChunkBuilder{,BuiltChunk}Mixins` + `BuiltChunkStorageMixins` +
`BuiltBufferMixins` form **one interlocked cluster** — none compiles until the set is migrated
together. Foundations now done: the **vertex-consumer keystone** and **`Constants`**. Target map:

| 1.21.4 | 26.2 |
| --- | --- |
| `ChunkBuilder` | `renderer.chunk.SectionRenderDispatcher` |
| `ChunkBuilder.BuiltChunk` | `SectionRenderDispatcher.RenderSection` (`index`; `data`→`sectionMesh` `AtomicReference<SectionMesh>`; `getOrigin`→`getRenderOrigin`; `getSectionPos`/origin→`getSectionNode` packed long; `reset`/`resortTransparency`) |
| `ChunkBuilder.ChunkData` / `.EMPTY` | `renderer.chunk.SectionMesh` / `CompiledSectionMesh.UNCOMPILED` |
| `SectionBuilder` / `.RenderData` | `renderer.chunk.SectionCompiler` / `SectionCompiler.Results` (`buffers`→`renderedLayers` `Map<ChunkSectionLayer,MeshData>`; `chunkOcclusionData`→`visibilitySet` `VisibilitySet`; `blockEntities`) |
| `BuiltChunkStorage` (`.chunks`) | sections live in `SectionRenderDispatcher` via `RotatingSectionStorage` |
| `ChunkRendererRegion` / `…Builder` | `renderer.chunk.RenderSectionRegion` / `RenderRegionCache` |
| `BlockBufferAllocatorStorage` | `renderer.SectionBufferBuilderPack` |
| chunk `RenderLayer`s | `renderer.chunk.ChunkSectionLayer` enum (only `SOLID`/`CUTOUT`/`TRANSLUCENT`; `.label()`, `.byTransparency`) |
| `RenderLayer.MultiPhase.phases.texture` | terrain texture is always the block atlas; per-`RenderType` texture lives behind package-private `RenderSetup.textures`/`TextureBinding` |

**Geometry-extraction model changed** (key to `SectionBuilderMixins`): 1.21.4 rendered each
block into a `VertexConsumer` (`blockRenderManager.renderBlock(…, PBRVertexConsumer, …)`). 26.2
`SectionCompiler.compile` uses a `BlockQuadOutput` (baked-quad callback) +
`BufferBuilder.putBlockBakedQuad(x,y,z,quad,instance)`. Crucially `putBlockBakedQuad` is a
**default on `VertexConsumer`** that routes through the standard per-vertex setters, so
`PBRVertexConsumer` still captures everything — the mixin just supplies a `BlockQuadOutput` /
`FluidRenderer.Output` (whose `getBuilder` returns a `VertexConsumer`) that funnel each layer's
quads into a per-`ChunkSectionLayer` PBR consumer built over `SectionBufferBuilderPack.buffer(layer)`.
Terrain texture = block atlas (`TextureAtlas.LOCATION_BLOCKS`); alphaMode maps straight off the
`ChunkSectionLayer` (SOLID→opaque, CUTOUT→cutout, TRANSLUCENT→transparent).

**Cluster progress:** ✅ `IChunkBuilderExt`+`ChunkBuilderMixins` (dispatcher accessor), ✅
`SectionBuilderMixins` (`compile` hook), ✅ `BufferProxy` (buffer bridge; `updateWorldUniform`
now takes render-state as params), ✅ `BuiltBufferMixins` (`decodeQuadCentroids`). Remaining:
`ChunkProxy` (rebuild loop) + `ChunkBuilderBuiltChunkMixins`/`IChunkBuilderBuiltChunkExt`
(RenderSection lifecycle) + `BuiltChunkStorageMixins`.

**ChunkProxy rebuild loop needs re-architecture (not a port).** `ChunkProxy.rebuild(Camera)`
polls `builtChunk.needsRebuild()` / `shouldBuild()` / `cancelRebuild()` / `needsImportantRebuild()`
/ `scheduleRebuild(boolean)` — **all removed** from `RenderSection`. In 26.2 the dirty-tracking
and compile scheduling moved out of the section into `LevelRenderer`/`RenderSectionManager`;
`RenderSection` only exposes `reset()` (was `clear`), `setSectionNode(long)` (was `setSectionPos`),
`compileAsync(RenderSectionRegion)` / `createCompileTask`, and the dispatcher's
`SectionTaskDynamicQueue`. Storage also moved: `BuiltChunkStorage.chunks[]` → `RotatingSectionStorage`
inside the dispatcher. Two candidate integrations:
- **A — keep the standalone loop:** hook 26.2's compile trigger (`RenderSection.compileAsync` or
  `LevelRenderer.setSectionDirty`) to `ChunkProxy.enqueueRebuild` + cancel MC's own compile, and
  drop the `needsRebuild`/`shouldBuild` poll (queue membership already means dirty). Closest to the
  mod's current architecture.
- **B — hook the compile result:** let MC's dispatcher run its normal compile (already producing PBR
  `MeshData` via `SectionBuilderMixins`) and hook the mesh-set / `uploadTerrainBuffersToGpu` to feed
  native + skip the GL upload. Less machinery, bigger departure.
The `RenderSection` lifecycle mixin (`reset`/`setSectionNode` → enqueue/relocate) and the storage
mixin fall out of whichever integration is chosen.

**Decision: Approach A — DONE.** The whole chunk-rebuild cluster compiles clean (`ChunkProxy` +
`ChunkBuilderBuiltChunkMixins` + `BuiltChunkStorageMixins` + `ChunkBuilderMixins` dispatcher
capture + `IViewAreaExt`; `IChunkBuilderBuiltChunkExt` retired). 1.21.11 stays maintained, so the
port was kept structurally parallel to upstream for forward-porting — see memory
`keep-26_2-parallel-to-1-21-11`. Implementation as built:
- **Compile trigger** — `@Inject` HEAD-cancellable into `RenderSection.compileAsync(RenderSectionRegion)`
  (MC calls it from `LevelRenderer` only when a section is dirty & should build) →
  `ChunkProxy.enqueueRebuild(self)` + `ci.cancel()`. Replaces the old `scheduleRebuild`/`clear`
  enqueue points; the `needsRebuild`/`shouldBuild` poll in `rebuild(Camera)` is dropped (queue
  membership already means dirty).
- **Own rebuild loop** — for each queued `RenderSection`: rebuild the region via
  `new RenderRegionCache().createRegion(Minecraft.getInstance().level, section.getSectionNode())`;
  if null → `invalidateSingle`; else `sectionCompiler.compile(SectionPos.of(sectionNode), region,
  vertexSorting, pack)` (intercepted by `SectionBuilderMixins` → PBR `Results`), then feed
  `Results.renderedLayers` (`Map<ChunkSectionLayer,MeshData>`) to native `rebuildSingle`.
  Compiler via `((IChunkBuilderExt) dispatcher).radiance$getSectionCompiler()`.
- **Lifecycle mixin** (`ChunkBuilderBuiltChunkMixins` → `RenderSection`): `setSectionPos(J)` →
  `setSectionNode(J)` TAIL → `relocateSingle(index, renderOrigin)`. The old `<init>` stream.collect
  redirect + `delete()` cancel are obsolete (RenderSection `<init>` is `(int index, long sectionNode)`,
  no per-section buffer setup).
- **Storage mixin** (`BuiltChunkStorageMixins` → `ViewArea`): `clear()`→`ChunkProxy.clear()`;
  size/init hook → `ChunkProxy.init(...)`; `updateCameraPosition`→`updateSectionPos`. `storage.chunks[]`
  iteration for `rebuildAll` → the dispatcher's `RotatingSectionStorage` (`Iterable`/`forEach`).
- **Dispatcher capture** — `@Inject` `SectionRenderDispatcher` `<init>` TAIL →
  `ChunkProxy.setDispatcher(self)` (single instance; mirrors the current `currentStorage` static).
- `ChunkData.EMPTY`/anonymous `ChunkData` → `SectionMesh`/`CompiledSectionMesh.UNCOMPILED`;
  `builtChunk.data` → `renderSection.sectionMesh`.

## Entity / vertex-provider path — SubmitNodeCollector rearchitecture (2nd crux)

**26.2 removed `VertexConsumerProvider` / `MultiBufferSource` entirely.** There is no
`getBuffer(RenderType) -> VertexConsumer` immediate-mode provider anymore; entity/item rendering
moved to a **deferred `net.minecraft.client.renderer.SubmitNodeCollector`** — renderers *submit*
nodes (`EntityRenderDispatcher.render(..., SubmitNodeCollector, ...)`), and geometry is written to
a `VertexConsumer` later at flush via `SubmitNodeCollector.CustomGeometryRenderer.render(PoseStack.Pose,
VertexConsumer)`. `VertexConsumer` and `RenderBuffers` survive at the low level, but the provider
abstraction is gone.

Impact: the mod captured entity/item geometry by passing its `StorageVertexConsumerProvider`
(a `VertexConsumerProvider` that hands out `PBRVertexConsumer`s) into the render calls. That
strategy has no 26.2 shape — **`StorageVertexConsumerProvider` /
`StorageOutlineVertexConsumerProvider` can't be ported as-is**, and the whole
**entity/item + world-render cluster** (`EntityProxy`, the entity/item/held-item renderer mixins,
`WorldRendererMixins`/`CloudRendererMixins`) is a **coupled rearchitecture** around
`SubmitNodeCollector` — a second crux comparable to the shader subsystem, not the "clean port" the
options table first assumed. Capture must move to the collector's flush point (intercept where the
submitted node's `CustomGeometryRenderer` is invoked and substitute a `PBRVertexConsumer` for the
`VertexConsumer` it writes to). The genuinely clean remaining subsystems are the **low-level**
ones that still take a `VertexConsumer` directly (block/fluid model renderers, particles) + the
misc/core mixins — not the entity provider layer.

Related — the same immediate-mode overhaul makes several other "render-hook" mixins rewrites, not
clean ports: **`BlockModelRendererMixins`** hooked `ModelBlockRenderer.renderQuad(…VertexConsumer…)`
which is **gone** (blocks render via `putBlockBakedQuad`/`QuadInstance` now), and its color source
`BlockColors.getColor(state,world,pos,tint)` is gone too (→ `getTintSources`); it needs
re-architecting around the quad-instance model (+ `PBRVertexConsumer.albedoEmission`, dropped from
the keystone — re-add when doing this). **`FluidRendererMixins`** (676 L) hooks
`FluidRenderer.render(…VertexConsumer…)` but 26.2 renamed it to `tesselate(…Output…)` and renamed
every internal it shadows (`isSameFluid`→`isNeighborSameFluid`, `calculateFluidHeight`→
`calculateAverageHeight`, `getFluidHeight`→`getHeight`, `getLight`→`getLightCoords`, …) — a full
rewrite. `BillboardParticleMixins` similarly writes to a `VertexConsumer`. The genuinely clean
remainder is the **non-render-hook** files (`GlStateManager`, `Lightmap`, `AuxiliaryTextures` ✅,
`ParticleManager`) — large but not tied to the render-model change.

`RenderType` texture extraction (needed by whatever feeds `PBRVertexConsumer` on the entity path):
`RenderType.state.textures.values().first().location()` — reachable via access-widener (26.2 is
de-obfuscated so `TextureBinding.location()` is already public; widen the `TextureBinding` class +
`RenderSetup.textures` / `RenderType.state` / `RenderType.name` fields). Mirrors the upstream
`RenderLayer.MultiPhase.phases.texture.getId()` direct-field access.

## Vertex path (partial)

The custom PBR vertex pipeline is a deep rewrite. Foundation **done**:
- `PBRVertexFormatElements` — Yarn `VertexFormatElement.register(id, uv, ComponentType,
  Usage, count)` → 26.2 `GpuFormat` constants (the element record is now just
  `(name, offset, GpuFormat)`; no id/Usage/ComponentType).
- `PBRVertexFormats` — `VertexFormat.builder()` → `builder(0)`, `add(name, element)` →
  `addAttribute(name, GpuFormat)`; the removed `Builder#skip(4)` is an explicit 4-byte
  `Padding` attribute.

**Vertex-consumer keystone — DONE** (`client/vertex/PBRVertexConsumer`, redesigned):
- Renamed `VertexConsumer` methods (`vertex`/`color`/`texture`/`overlay`/`light`/`normal` →
  `addVertex`/`setColor`/`setUv`/`setUv1`/`setUv2`/`setNormal`), plus the two new abstract
  methods `setColor(int packedArgb)` and `setLineWidth(float)` (no-op for PBR).
- `BufferAllocator`→`ByteBufferBuilder` (`allocate`→`reserve`, `getAllocated`→`build`);
  `BuiltBuffer`→`MeshData` (`DrawParameters`→`DrawState`); `VertexFormat.DrawMode`→
  `com.mojang.blaze3d.PrimitiveTopology` (`getIndexCount`→`indexCount`);
  `VertexFormat.IndexType`→`com.mojang.blaze3d.IndexType` (`smallestFor`→`least`).
- **Removed element-id/mask model.** `VertexFormatElement` is now `record(name, offset,
  GpuFormat)` with no id, so `getRequiredMask`/`getOffsetsByElementId`/`getBit`/`streamFromMask`
  are gone. The mask was purely an offset lookup + a redundant guard (requiredMask was always 0,
  writableMask covered every non-position attribute), so it is replaced by direct writes to
  fixed offsets precomputed in `PBRVertexFormats` (`getElement(name).offset()`).
- **Render-state classification moved out of the class.** `RenderPhase` and
  `RenderLayer.MultiPhase.phases` are removed, and 26.2 forks chunk vs entity geometry onto
  different types (`ChunkSectionLayer` enum vs `RenderType`). The constructor now takes the two
  resolved ints it actually needs — `(ByteBufferBuilder, int textureID, int alphaMode)` — and
  the caller resolves them: trivial for chunk (block atlas id + ChunkSectionLayer→alphaMode),
  RenderType-based for entity/item (deferred to those subsystems; `getPostTextMode(String)` +
  the `ALPHA_MODE_*` constants stay here for reuse). This sidesteps the package-private
  `RenderSetup.textures`/`TextureBinding` texture-extraction problem entirely for the chunk path.
- `GLint`/`GLintOverlay` now take a resolved `int glintTextureID` and `PoseStack.Pose`
  (`getPositionMatrix`/`getNormalMatrix`→`pose()`/`normal()`;
  `Direction.getFacing`→`getApproximateNearest`, `getRotationQuaternion`→`getRotation`).

**Still a ground-up rewrite** (removed/rewritten APIs, not mechanical renames):
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
  - **Render-type / phase — RETIRED (obsolete in 26.2).** The four mixins
    (`RenderPhaseMixins` + `RenderPhaseLightmapMixins`/`RenderPhaseTargetMixins` +
    `RenderLayerMixins`) neutralized `RenderPhase`'s GL begin/end actions (target switch, lightmap
    enable) since the mod renders via Vulkan, and rebuilt the LIGHTNING layer. `RenderPhase` is
    **entirely removed** in 26.2 (no begin/end-action system — GL state is declarative in
    `RenderPipeline` + render passes), so the neutralization is moot (superseded by the mod's
    GpuDevice/CommandEncoder-level interception). The LIGHTNING rebuild used the removed
    `RenderLayer.of`/`MultiPhaseParameters`/`RenderPhase.Texture` API (26.2 builds it via
    `RenderType.create`/`RenderSetup`/`RenderPipeline`); if lightning visuals need the mod's
    textured variant, reimplement it with the weather/entity render path, not here. No mod code
    referenced the setters/LIGHTNING, so all 4 were deleted.
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
