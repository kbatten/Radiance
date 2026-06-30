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
- **Still to do (only this):**
  - `vri/NativeImageMixins#radiance$loadFromTextureImageWithoutUI` (screenshot readback)
    is a best-effort scaffold — `NativeImage.loadFromTextureImage` was removed; texture
    download now goes through `GpuDevice`/`CommandEncoder`.

**The texture-tracking subsystem is otherwise fully ported to the 26.2 GPU model**
(GL-id resolution, registration, allocation/import, metadata, pixel upload, PBR aux
textures, sampler), with the obsolete `targetID`-stamping mixins retired.

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

### Deferred leaf files (need API-shape changes, not just renames)

  (and depends on the render-coupled `AuxiliaryTextures`).
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
  - **Font** (`BitmapFontGlyph`, `BuiltinEmptyGlyph`, `FontStorage`, `TtfGlyph`):
    glyph/atlas system rebuilt (`FontSet`/`FontTexture`/`glyphs.*`).
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
