# CLAUDE.md — Radiance (Java / Fabric mod)

## What this repo is

Radiance is a Fabric mod that replaces Minecraft's OpenGL renderer with a native
**Vulkan hardware ray-tracing** backend. This repo is the **Java half**: it hooks MC's
render loop via Mixins, captures geometry/textures/uniforms, and forwards everything
across a JNI bridge to the native renderer.

The **native half** lives in a separate repo, **MCVR** (`/Users/keith/src/MCVR`), which
builds `core.dll` and installs it into this repo's `src/main/resources/core.dll`. The two
are developed together — see `../MCVR/CLAUDE.md`.

## Current work: the 26.2 port

- Working branch: **`main.26_2`** — porting from MC 1.21.x to **MC 26.2**.
- Reference branch: **`main`** — the working 1.21.x version. Diff against it to see how a
  feature was implemented before the port.
- 26.2 ships **de-obfuscated** (official Mojang names) — no yarn/intermediary remapping.
- **`MIGRATION-26.2.md`** (repo root, ~60 KB) is the running migration log — the primary
  reference for what changed and why.
- Remaining feature/port work is tracked in **`TODO.md`**.

## Versions (`gradle.properties`)

| | |
|---|---|
| minecraft | 26.2 |
| fabric-loader | 0.19.3 |
| loom | 1.17.12 |
| fabric API | 0.153.0+26.2 |
| mod_version | 0.1.5-alpha |

Iris and Sodium are **incompatible** with Radiance's Vulkan backend (Iris is a GL shader
mod; it throws at init). Do not run with them installed.

## Build & run

```bash
./gradlew build          # -> build/libs/Radiance-<version>.jar
```

Deploy the jar to `.minecraft/mods/` on the run box. The game runs on **Windows (RTX
2080)**; native (`core.dll`) is built on a Linux box and installed into this repo's
resources by MCVR's `cmake --install`. A Java-only change needs only `./gradlew build`;
a native change needs an MCVR rebuild+install first (see `../MCVR/CLAUDE.md`). Bundle
native+Java changes per round to minimize build/run round-trips.

- `./gradlew compileJava` — fast compile-only check (no remap/jar), good for verifying a
  mixin change builds.

## Layout

```
src/main/java/com/radiance/
  client/
    proxy/vulkan/   BufferProxy, DrawCommandProxy, GeometryCapture, PipelineStateProxy,
                    RendererProxy, ShaderProxy, TextureProxy, WindowProxy   (JNI bridge)
    proxy/world/    ChunkProxy, EntityProxy, PlayerProxy                    (JNI bridge)
    vertex/         PBRVertexConsumer + vertex capture
    constant/ option/ pipeline/ shader/ texture/ gui/ util/
  mixin_related/extensions/   duck-interface extensions injected onto MC classes (IFooExt)
  mixins/
    vulkan_render_integration/  (49) THE CRUX — the render-loop takeover + capture hooks
    vanilla_resource_tracker/   (5)  texture/resource id tracking
    vulkan_options/             (2)  video-options screen additions
src/main/resources/
  fabric.mod.json  radiance.mixins.json  radiance.accesswidener
  core.dll         <- installed by MCVR; the native renderer
src/main/native/include/   generated JNI headers (com_radiance_*.h); MCVR compiles against these
```

## How it fits together (mental model)

1. **Mixins** in `vulkan_render_integration` intercept MC's render loop. The keystone is
   `WorldRendererMixins.redirectRender` — it **cancels `LevelRenderer.render` at HEAD** and
   re-drives everything (world/sky uniforms, entities, terrain scheduling) through the
   proxies. Because `render` is cancelled, anything 26.2 moved *into* `render` must be
   replicated by hand (this is a recurring source of port bugs — e.g. the ViewArea grid
   rotation that caused invisible terrain).
2. **Proxies** are thin classes with `native` methods; they marshal data (often via
   `MemoryUtil` off-heap buffers holding raw addresses) and call into `core.dll`.
3. **Terrain**: `SectionBuilderMixins` captures per-section geometry into a
   `PBRVertexConsumer`; `ChunkProxy` ships it to the native chunk/BLAS pipeline.
   Terrain is ray-traced from captured meshes, **not** from intercepted draw calls.
4. **Entities/BEs/outline/hand/crumbling**: captured through `EntityProxy` submit paths.

## Conventions & gotchas

- **Cancelled-`render` rule:** when a feature "used to just work", check whether 26.2 moved
  its trigger into `LevelRenderer.render` (which this mod cancels). If so, replicate the
  essential call in `WorldRendererMixins`. Confirm callers by decompiling the MC jar (see
  below) rather than guessing.
- **Reading MC 26.2 source:** no decompiler jar is installed on this Mac. Use
  `javap -p -c` on
  `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`
  and read the bytecode — reliable for method bodies, call sites, and field access.
- **API verification:** confirm 26.2 signatures with `javap` against the loom jar before
  writing mixin code; class/method shapes changed a lot from 1.21.
- Commits get a standard `Co-Authored-By` trailer automatically; branch off `main.26_2`.

## Known-incompatible / environment notes

- Validation on the run box: the Windows box has a toxic implicit-overlay-layer stack
  (OBS/Steam/Galaxy/EOS) that crashes with validation on. Native validation lives in MCVR;
  keep it disabled unless deliberately debugging (see `../MCVR/CLAUDE.md`).
- `radiance_native.log` (repo root) is the freopen'd native stderr — grep it for `[...]`
  probe tags when diagnosing.
