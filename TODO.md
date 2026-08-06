# TODO — Radiance 26.2 port

Feature roadmap for the MC 1.21.x → 26.2 port. This is the shared source-of-truth ordering;
the native-side counterparts live in `../MCVR/TODO.md`. Diff against the `main` branch to see
how each feature worked in 1.21.x.

**State:** ✅ done · ⚠️ wired but broken/wrong · ❌ not implemented (no-op stub) · 🔁 regressed · 🐞 crash

## Order of attack

| # | Feature | State | Where | Notes |
|---|---------|-------|-------|-------|
| 1 | Terrain visible while walking | ✅ (confirm) | `WorldRendererMixins` | Fixed: `render` is cancelled, so 26.2's per-frame `viewArea.repositionCamera` (moved into `LevelRenderer.render`) was never called → grid froze at load point. Now replicated. Awaiting user confirm. |
| 2 | **Mobs render correctly (not red)** | ✅ (confirm) | `EntityProxy.resolveTextureGlId`, `StorageVertexConsumerProvider.resolveTextureId` | Fixed: texture id was resolved as "first GL-backed sampler", but `RenderSetup.prepareTextures` orders the list [overlay(Sampler1), lightmap(Sampler2), main(Sampler0)] — so mobs sampled the red/white **overlay** as albedo. Now selects `Sampler0` by name. Should also fix block-entities/items/**hand**. Awaiting user confirm. |
| 3 | **GUI item icons corrupted** (hotbar, inventory) — root-caused | ⚠️ | needs **render-target awareness** in the draw replay: `RenderPassMixins`/`CommandEncoderMixins`/`GlStateManagerMixins` (`createRenderPass`→`DrawCommandProxy.Overlay`) + native | **Root cause found.** 26.2 renders GUI items through `GuiItemAtlas`: each item's 3D model is rendered (ortho, `FeatureRenderDispatcher.renderAllFeatures`) into a slot of the atlas's own `GpuTexture`, then the GUI blits each item as a flat quad sampling that atlas. But the mod has **no render-target awareness** — every clear/draw is unconditionally redirected to the single native overlay (`DrawCommandProxy.Overlay`), so the item-model draws never reach the atlas texture. The atlas stays empty/garbage → every icon samples the same garbage (user: identical "wavy yellow stripes, transparent between", not a 3D shape; **GUI-only, the RT first-person hand is fine**). Same class as the button/animated-sprite un-replayed-RTT bugs. **Fix options:** (A) add render-target awareness — intercept `createRenderPass`, and when the target is a non-overlay `GpuTexture`, route the draws/clear into a native render pass targeting that texture (general; also fixes other RTT: maps, sprite-anim atlases). (B) bypass the atlas — redirect per-item model geometry straight into the overlay at the icon transform each frame. (A) is the right long-term fix; both are sizable native+Java changes. |
| 4 | **Glowing / emissive tiles** (lava, torches, glowstone, fire) | ⚠️ | `BlockModelRendererMixins`, `PBRVertexConsumer`; native `emission.cpp`/`chunks.cpp` | Emission capture *is* wired (AlbedoEmission channel, `collectChunkEmission` option, `buildLightInfos`). Bug: emissive surfaces don't act as light emitters in the path tracer. Check whether it shares a root with #2 (capture into wrong channel/index). |
| 5 | **Animated textures** (water/lava/fire/portal flow) | ⚠️ | atlas upload path (native `textures.cpp`) | First frame only — 26.2 fills animated frames via an un-replayed `animate_sprite_blit` render-to-texture. Needs the same CPU-copy path the static atlas fix used. Pairs with #4 (lava is animated *and* emissive). |
| 6 | **Block-targeting highlight wireframe** | ⚠️ | `EntityProxy.queueTargetBlockOutlineRebuild:371` → `submitShapeOutline` + `RenderTypes.lines()` | Box outline renders wrong: facing east, edges shoot *past* the block (away + to the right) forming an "L" instead of a closed box. MC's `lines()` vertex format carries a per-vertex **normal (line direction) + line width** that MC expands into screen-facing quads in a vertex shader the RT path never runs — the capture likely mis-handles the normal/line-width (baking it into position). NB `StorageOutlineVertexConsumerProvider` is the *entity glow* outline, a different path — don't conflate. |
| 7 | **Sun & moon discs** | ❌ | `WorldRendererMixins:194-197` | `sunTextureID=0, moonTextureID=0`. In 26.2 sun/moon are atlas sprites (SkyRenderer) — feed the sprite GL-id + UVs to the native sky shader. |
| 8 | **Menu panorama / sky cubemap** | 🔁 | panorama samplerCube path | Was visible ~07-20, regressed to black; prime suspect was Iris hooking the sky. **Retest with Iris removed** — likely a quick win. |
| 9 | **Fog shape** + **sky-dark** | ⚠️ | `WorldRendererMixins:165,190` | Fog hardcoded SPHERE (`FogData` dropped shape) → cylindrical fog wrong. `skyDark=false` hardcoded (no render-state flag replaces `isSkyDark`). Small; batch with the sky pass. |
| 10 | **Particles** (block break, smoke, crits…) | ❌ | `EntityProxy.queueParticleRebuild` (no-op) | 26.2 moved particles to `submitQuadParticleGroup` — needs a new capture hook. Bigger. |
| 11 | **Weather** (rain/snow) | ❌ | `EntityProxy.queueWeatherBuild` (no-op) | 26.2 weather no longer goes through a `VertexConsumer` — needs a `WeatherEffectRenderer` hook. |
| 12 | **Item glint** | ⚠️ | `WorldRendererMixins:137` | Glint texture matrix hardcoded to identity (`RenderSystem.getTextureMatrix` gone; it's a UBO now). Cosmetic. |
| 13 | **Screenshot / world-icon capture** | ❌ | `GameRendererMixins:43` | `renderLevel` capture is a TODO. |
| 14 | **World shader translation** (terrain/clouds/end_portal) | ⚠️ | shader translator | 4 world shaders fail translation (new 26.2 ChunkSection block fields absent from BuiltinUniforms). **Low priority** — world is RT'd from captured meshes, not these draws. |

## Stability (slot in whenever it annoys)

- 🐞 **Clean exit** — "normal crash on exit" every run; shutdown-order teardown.

## Done this port (recent)

- ✅ Intermittent corrupt-TLAS crash (~10s–2min in-world) — native barrier fix (MCVR).
- ✅ Invisible terrain after walking (#1).
- ✅ Compositing, world flip (reverse-Z), black sky, black terrain lighting, buttons, text.

See `MIGRATION-26.2.md` for the full migration log.
