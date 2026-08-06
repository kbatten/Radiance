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
| 3 | **GUI item icons corrupted** (hotbar, inventory) — FIX applied (option A), awaiting test | ⚠️ | Radiance `RenderTargets`/`TextureUtilMixins`/`CommandEncoderMixins`/`RenderPassMixins`/`ShaderProxy` + MCVR `textures`/`ui_module` RTT + JNI | **Root cause:** 26.2 renders GUI items into a `GuiItemAtlas` render-to-texture, but the mod routed every draw to the single native overlay → atlas empty → every icon = same garbage ("wavy yellow stripes"; GUI-only, RT hand fine). **Fix (option A, render-target awareness):** import `USAGE_RENDER_ATTACHMENT` color textures as color-attachment+sampled; a draw whose color attachment is a registered render target (not the main framebuffer) routes to a native RTT render pass targeting that texture (`beginTarget`/`drawToTarget`/`endTarget`), reusing the overlay pipelines (RTT pass is overlay-compatible: RGBA8_UNORM+D32_SFLOAT), LOADing the atlas + replaying the per-slot region clear, applying reverse-Z depth + cull, ending in SHADER_READ for the blit. Radiance 4a4a28e + MCVR 15aebec. **Untestable on dev Mac — budget 1–2 validation-guided round-trips.** Iteration points if wrong: viewport/scissor = clear region may need to be the RenderPass renderArea instead; cull front-face (CCW) may be flipped in the atlas; overlay-pass interruption / SHADER_READ barrier. Build **jar first** (regenerates JNI headers) then MCVR. |
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
| 15 | **First-person held item is a flat square** | ⚠️ | first-person item capture (`EntityProxy` hand path) | RT'd held item renders as a textured square, not its actual item-model shape. Texture is correct (post mobs-red fix), geometry is wrong. **Low priority** (user-deprioritised 2026-08-06). Likely the item-model geometry captured for the hand is a bounding quad rather than the built model, or the flat 'generated' model's shape/alpha isn't honoured. Distinct from the GUI item-atlas path (#3). |

## Stability (slot in whenever it annoys)

- 🐞 **Clean exit** — "normal crash on exit" every run; shutdown-order teardown.

## Done this port (recent)

- ✅ Intermittent corrupt-TLAS crash (~10s–2min in-world) — native barrier fix (MCVR).
- ✅ Invisible terrain after walking (#1).
- ✅ Compositing, world flip (reverse-Z), black sky, black terrain lighting, buttons, text.

See `MIGRATION-26.2.md` for the full migration log.
