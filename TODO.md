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
| 3 | **GUI item icons** (hotbar, inventory) + inventory player model | ✅ | Radiance `RenderTargets`/`TextureUtilMixins`/`CommandEncoderMixins`/`RenderPassMixins`/`ShaderProxy` + MCVR `textures`/`ui_module` RTT + JNI | **DONE (user-confirmed 2026-08-06): icons render, inventory player model renders.** 26.2 renders GUI items into a `GuiItemAtlas` RTT (and inventory/skin/banner previews via `PictureInPictureRenderer`), both redirected by `RenderSystem.outputColorTextureOverride`; the mod routed everything to the single overlay → empty targets. Fix = render-target awareness (option A): import `USAGE_RENDER_ATTACHMENT` textures as color-attachment+sampled; draws whose active target is a registered RTT texture route to a native RTT pass (`beginTarget`/`drawToTarget`/`endTarget`) reusing the overlay pipelines (compatible RGBA8_UNORM+D32_SFLOAT). Key fixes across rounds: depth image init/clear (reverse-Z 0.0 far), mixin `<clinit>` startup NPE (`be15b92`), and the **Y-flip** — RTT renders with a negative-height viewport so targets store GL-bottom-up for MC's fixed V-flipped blit; cull off since the flip inverts winding (MCVR `3e5f6d2` + Radiance `123d8a3`). Remaining minor follow-up: animated item slots (clock/compass/buckets) may need their captured per-slot clear/scissor rect Y-flipped from MC (GL) to Vulkan coords. |
| 4 | **Glowing / emissive tiles** (lava, torches, glowstone, fire) | ⚠️ | `BlockModelRendererMixins`, `PBRVertexConsumer`; native `emission.cpp`/`chunks.cpp` | Emission capture *is* wired (AlbedoEmission channel, `collectChunkEmission` option, `buildLightInfos`). Bug: emissive surfaces don't act as light emitters in the path tracer. Check whether it shares a root with #2 (capture into wrong channel/index). |
| 5 | **Animated textures** (water/lava/fire/portal flow) | ⚠️ | atlas upload path (native `textures.cpp`) | First frame only — 26.2 fills animated frames via an un-replayed `animate_sprite_blit` render-to-texture. Needs the same CPU-copy path the static atlas fix used. Pairs with #4 (lava is animated *and* emissive). |
| 6 | **Block-targeting highlight wireframe** | ⚠️ | `EntityProxy.queueTargetBlockOutlineRebuild:371` → `submitShapeOutline` + `RenderTypes.lines()` | Box outline renders wrong: facing east, edges shoot *past* the block (away + to the right) forming an "L" instead of a closed box. MC's `lines()` vertex format carries a per-vertex **normal (line direction) + line width** that MC expands into screen-facing quads in a vertex shader the RT path never runs — the capture likely mis-handles the normal/line-width (baking it into position). NB `StorageOutlineVertexConsumerProvider` is the *entity glow* outline, a different path — don't conflate. |
| 7 | **Sun & moon discs** | ❌ | `WorldRendererMixins:194-197` | `sunTextureID=0, moonTextureID=0`. In 26.2 sun/moon are atlas sprites (SkyRenderer) — feed the sprite GL-id + UVs to the native sky shader. |
| 8 | **Menu panorama / sky cubemap** | 🔁 | panorama samplerCube path | Was visible ~07-20, regressed to black; prime suspect was Iris hooking the sky. **Retest with Iris removed** — likely a quick win. |
| 9 | **Fog shape** + **sky-dark** | ⚠️ | `WorldRendererMixins:165,190` | Fog hardcoded SPHERE (`FogData` dropped shape) → cylindrical fog wrong. `skyDark=false` hardcoded (no render-state flag replaces `isSkyDark`). Small; batch with the sky pass. |
| 10 | **Particles** (block break, smoke, crits…) | ❌ | `EntityProxy.queueParticleRebuild` (no-op) | 26.2 moved particles to `submitQuadParticleGroup` — needs a new capture hook. Bigger. |
| 11 | **Weather** (rain/snow) | ❌ | `EntityProxy.queueWeatherBuild` (no-op) | 26.2 weather no longer goes through a `VertexConsumer` — needs a `WeatherEffectRenderer` hook. |
| 12 | **Item glint** (enchantment shimmer) | ⚠️ | `WorldRendererMixins:137` | Glint texture matrix hardcoded to identity (`RenderSystem.getTextureMatrix` gone; it's a UBO now). Cosmetic — but note it also manifests as **#15**: the first-person held **enchanted** item's glint renders as a flat square (unenchanted items are fine). Fixing the glint UBO here should resolve both. |
| 13 | **Screenshot / world-icon capture** | ❌ | `GameRendererMixins:43` | `renderLevel` capture is a TODO. |
| 14 | **World shader translation** (terrain/clouds/end_portal) | ⚠️ | shader translator | 4 world shaders fail translation (new 26.2 ChunkSection block fields absent from BuiltinUniforms). **Low priority** — world is RT'd from captured meshes, not these draws. |
| 15 | **First-person held item: enchantment glint renders as a flat square** | ⚠️ | first-person item capture (`EntityProxy` hand path) + glint; see **#12** | **Narrowed (user 2026-08-06): only ENCHANTED items are wrong — unenchanted held items render correctly (proper 3D model + texture).** So the base item-model geometry/texture is fine; the defect is the **enchantment glint** overlay pass, which renders as a flat square over/around the item. Almost certainly the same root as **#12 (item glint)**: the glint texture matrix is hardcoded to identity (`RenderSystem.getTextureMatrix` gone → it's a UBO now), so the glint's animated scrolling UV/geometry is mishandled. **Low priority** (user-deprioritised). Fold into #12. |

## Stability (slot in whenever it annoys)

- 🐞 **Clean exit** — "normal crash on exit" every run; shutdown-order teardown.

## Done this port (recent)

- ✅ Intermittent corrupt-TLAS crash (~10s–2min in-world) — native barrier fix (MCVR).
- ✅ Invisible terrain after walking (#1).
- ✅ Compositing, world flip (reverse-Z), black sky, black terrain lighting, buttons, text.

See `MIGRATION-26.2.md` for the full migration log.
