# TODO — Radiance 26.2 port

Feature roadmap for the MC 1.21.x → 26.2 port. This is the shared source-of-truth ordering;
the native-side counterparts live in `../MCVR/TODO.md`. Diff against the `main` branch to see
how each feature worked in 1.21.x.

**State:** ✅ done · ⚠️ wired but broken/wrong · ❌ not implemented (no-op stub) · 🔁 regressed · 🐞 crash

## Order of attack

| # | Feature | State | Where | Notes |
|---|---------|-------|-------|-------|
| 1 | Terrain visible while walking | ✅ (confirm) | `WorldRendererMixins` | Fixed: `render` is cancelled, so 26.2's per-frame `viewArea.repositionCamera` (moved into `LevelRenderer.render`) was never called → grid froze at load point. Now replicated. Awaiting user confirm. |
| 2 | **Mobs render correctly (not red)** | ⚠️ | `EntityProxy`, native entity texture bind | Entity albedo shows red/white → entity texture GL-id / bindless index resolves to the missing-texture default. Likely also fixes first-person **hand + held items** (same submit path). |
| 3 | **Glowing / emissive tiles** (lava, torches, glowstone, fire) | ⚠️ | `BlockModelRendererMixins`, `PBRVertexConsumer`; native `emission.cpp`/`chunks.cpp` | Emission capture *is* wired (AlbedoEmission channel, `collectChunkEmission` option, `buildLightInfos`). Bug: emissive surfaces don't act as light emitters in the path tracer. Check whether it shares a root with #2 (capture into wrong channel/index). |
| 4 | **Animated textures** (water/lava/fire/portal flow) | ⚠️ | atlas upload path (native `textures.cpp`) | First frame only — 26.2 fills animated frames via an un-replayed `animate_sprite_blit` render-to-texture. Needs the same CPU-copy path the static atlas fix used. Pairs with #3 (lava is animated *and* emissive). |
| 5 | **Sun & moon discs** | ❌ | `WorldRendererMixins:194-197` | `sunTextureID=0, moonTextureID=0`. In 26.2 sun/moon are atlas sprites (SkyRenderer) — feed the sprite GL-id + UVs to the native sky shader. |
| 6 | **Menu panorama / sky cubemap** | 🔁 | panorama samplerCube path | Was visible ~07-20, regressed to black; prime suspect was Iris hooking the sky. **Retest with Iris removed** — likely a quick win. |
| 7 | **Fog shape** + **sky-dark** | ⚠️ | `WorldRendererMixins:165,190` | Fog hardcoded SPHERE (`FogData` dropped shape) → cylindrical fog wrong. `skyDark=false` hardcoded (no render-state flag replaces `isSkyDark`). Small; batch with the sky pass. |
| 8 | **Particles** (block break, smoke, crits…) | ❌ | `EntityProxy.queueParticleRebuild` (no-op) | 26.2 moved particles to `submitQuadParticleGroup` — needs a new capture hook. Bigger. |
| 9 | **Weather** (rain/snow) | ❌ | `EntityProxy.queueWeatherBuild` (no-op) | 26.2 weather no longer goes through a `VertexConsumer` — needs a `WeatherEffectRenderer` hook. |
| 10 | **Item glint** | ⚠️ | `WorldRendererMixins:137` | Glint texture matrix hardcoded to identity (`RenderSystem.getTextureMatrix` gone; it's a UBO now). Cosmetic. |
| 11 | **Screenshot / world-icon capture** | ❌ | `GameRendererMixins:43` | `renderLevel` capture is a TODO. |
| 12 | **World shader translation** (terrain/clouds/end_portal) | ⚠️ | shader translator | 4 world shaders fail translation (new 26.2 ChunkSection block fields absent from BuiltinUniforms). **Low priority** — world is RT'd from captured meshes, not these draws. |

## Stability (slot in whenever it annoys)

- 🐞 **Clean exit** — "normal crash on exit" every run; shutdown-order teardown.

## Done this port (recent)

- ✅ Intermittent corrupt-TLAS crash (~10s–2min in-world) — native barrier fix (MCVR).
- ✅ Invisible terrain after walking (#1).
- ✅ Compositing, world flip (reverse-Z), black sky, black terrain lighting, buttons, text.

See `MIGRATION-26.2.md` for the full migration log.
