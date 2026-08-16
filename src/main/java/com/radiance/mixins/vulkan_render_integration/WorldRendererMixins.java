package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.math.Axis;
import com.radiance.client.proxy.vulkan.BufferProxy;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.client.proxy.world.EntityProxy;
import com.radiance.client.proxy.world.PlayerProxy;
import com.radiance.client.texture.EmissiveBlockColor;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IViewAreaExt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 render-loop takeover. In 26.2 the old {@code WorldRenderer} was rearchitected into
 * {@link LevelRenderer}: entities, block entities, the block outline, particles and weather are all
 * pre-extracted into per-frame render states on {@link LevelRenderState} before {@code render} runs,
 * and terrain is driven through the {@code SectionRenderDispatcher}/{@code ViewArea}/{@code
 * SectionOcclusionGraph} frame graph. Almost none of the old field/method hook surface survives, so
 * this mixin is a from-scratch rewrite of the single {@code render} takeover.
 *
 * <p>We inject at the head of {@code render}, feed the Vulkan world/sky uniforms from the extracted
 * camera/level/sky render states (camera-matrix mapping is Option A: the clean, un-bobbed
 * {@code modelViewMatrix} is used for both the view and the "effected" view), drive the mod's own
 * per-object submit-node capture, and cancel so the vanilla frame graph never runs.
 *
 * <h3>Entity identity</h3>
 * The native ray tracer keys per-object caching on a stable identity hash. Raw {@link Entity}
 * instances are stable across frames, so entities are sourced from {@code entitiesForRendering()} and
 * culled here (preserving {@code identityHashCode(entity)}). Block-entity render states are transient,
 * so their identity is keyed on the (stable) block position inside {@link EntityProxy}.
 *
 * <h3>Chunk terrain</h3>
 * Cancelling {@code render} also skips {@code compileSections} (where MC would call
 * {@code RenderSection.compileAsync}), so this hook enqueues the frame's dirty sections (the
 * extractor's {@code sectionUpdateRenderStates}) into {@code ChunkProxy} and drains its native rebuild
 * queue. It also skips {@code updateSectionOcclusion} ({@code sectionOcclusionGraph.update}) -- the
 * camera BFS that populates the graph the extractor reads to fill {@code visibleSections} (and hence
 * {@code sectionUpdateRenderStates}) -- so we drive that here too; without it the graph stays empty,
 * nothing is ever dirty/enqueued, no chunk gets a BLAS and the ray-traced world is black. The rest of
 * the {@code ChunkProxy} lifecycle is driven by the ViewArea / SectionRenderDispatcher / RenderSection
 * mixins.
 *
 * <h3>Deferred</h3>
 * Particle / weather / crumbling capture are their own migration clusters and are left as documented
 * TODOs; the no-op {@code EntityProxy} stubs keep the render loop safe until they land.
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixins {

    @Shadow
    @Final
    private CloudRenderer cloudRenderer;

    @Shadow
    @Final
    private WeatherEffectRenderer weatherEffectRenderer;

    @Shadow
    public abstract ViewArea viewArea();

    @Shadow
    public abstract SectionOcclusionGraph sectionOcclusionGraph();

    @Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
        + "Lnet/minecraft/client/DeltaTracker;Z"
        + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;"
        + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
        at = @At("HEAD"), cancellable = true)
    public void redirectRender(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
        boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix,
        GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        GameRenderer gameRenderer = client.gameRenderer;
        LevelRenderState levelRenderState = gameRenderer.gameRenderState().levelRenderState;
        OptionsRenderState optionsState = gameRenderer.gameRenderState().optionsRenderState;
        SkyRenderState skyRenderState = levelRenderState.skyRenderState;
        TextureManager textureManager = client.getTextureManager();

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        PlayerProxy.setCameraPos(cameraState.pos);

        // ===================== World uniform =====================
        // Option A camera mapping: the clean (un-bobbed) modelViewMatrix is both the view and the
        // "effected" view; projection comes straight from the camera render state.
        Matrix4f viewMatrix = new Matrix4f(modelViewMatrix);
        Matrix4f effectedViewMatrix = new Matrix4f(modelViewMatrix);

        // MC 26.2 uses a reverse-Z, infinite-far projection (confirmed at runtime: m22~=0, m32=near=0.05,
        // NDC z maps near->1, far->0). The shared native path (buffers.cpp mapGLToVulkan) assumes a standard
        // GL projection (z in [-1,1]) and remaps [-1,1]->[0,1]; 1.21.x fed it exactly that. Fed reverse-Z,
        // the raygen's near point (ndc.z=0) unprojects BEHIND the camera, so primary rays point backward and
        // the world renders point-mirrored (upside-down + left-right). Convert MC's reverse-Z clip back to
        // standard GL clip (z_gl = -2*z_rev + w) so the native conversion produces a correct Vulkan
        // projection -- the 26.2 analog of 1.21.x, whose MC projection was already standard GL. Column-major.
        Matrix4f projectionMatrix = new Matrix4f(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, -2f, 0f,
            0f, 0f, 1f, 1f).mul(cameraState.projectionMatrix);
        // 26.2: RenderSystem.getTextureMatrix / RenderPhase.setupGlintTexturing are gone (glint's
        // TextureMat moved into the per-draw dynamictransforms UBO). Reproduce MC's classic glint
        // scroll matrix -- the exact value 1.21 fed here via setupGlintTexturing(0.16F) -- so the RT
        // glint overlay (default.rchit: glintUV = worldUBO.textureMat * glintUV) scrolls/tiles like
        // vanilla. glint.vsh is unchanged (texCoord0 = TextureMat * vec4(UV0,0,1)), so this still
        // matches. translate(-x,y)*rotateZ(135deg)*scale(0.16); periods 110000/30000 ms, time*8.
        long glintScroll = (long) (Util.getMillis() * 8L);
        float glintScrollX = (float) (glintScroll % 110000L) / 110000.0F;
        float glintScrollY = (float) (glintScroll % 30000L) / 30000.0F;
        Matrix4f glintTextureMatrix = new Matrix4f()
            .translation(-glintScrollX, glintScrollY, 0.0F)
            .rotateZ(2.3561945F)
            .scale(0.16F);

        // Classic ShaderGameTime fraction (RenderSystem.setShaderGameTime is gone in 26.2).
        float gameTime = ((float) (levelRenderState.gameTime % 24000L) + partialTick) / 24000.0F;
        int overlayTextureID = radiance$resolveGlId(gameRenderer.overlayTexture().getTextureView());
        boolean firstPerson = client.options.getCameraType().isFirstPerson();

        FogData fogData = cameraState.fogData;
        // skyRenderState.skybox reads NONE(0) every frame -- like sunAngle above, MC assigns it during the sky
        // render pass this mod cancels, so it never leaves its default. The RT sky miss shader treats skyType 0
        // as "void" and returns BLACK (no atmosphere), so the overworld sky was black while terrain stayed lit.
        // Read the skybox straight from the dimension (NONE=0, OVERWORLD=1, END=2 -- matches the shader) so the
        // overworld gets skyType 1 and the atmosphere renders.
        int skyType = client.level != null ? client.level.dimensionType().skybox().ordinal()
                                           : skyRenderState.skybox.ordinal();
        int endSkyTextureID = radiance$resolveGlId(
            textureManager.getTexture(AbstractEndPortalRenderer.END_SKY_LOCATION).getTextureView());
        int endPortalTextureID = radiance$resolveGlId(
            textureManager.getTexture(AbstractEndPortalRenderer.END_PORTAL_LOCATION).getTextureView());
        // levelLightmap() exposes the level lightmap GpuTextureView directly (no ILightMapManagerExt).
        int lightMapTextureID = radiance$resolveGlId(gameRenderer.levelLightmap());

        // Monotonic seconds clock for the handheld-light flame flicker (worldUBO.flickerTime).
        // gameTime above is a day fraction that wraps every 24000 ticks -- unusable as a flicker
        // clock -- so use a real wall clock here. Wrap hourly to keep float precision; the once-an-hour
        // phase jump is a single imperceptible frame.
        float flickerTime = (float) ((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000.0F;

        BufferProxy.updateWorldUniform(viewMatrix, effectedViewMatrix, projectionMatrix,
            glintTextureMatrix, gameTime, overlayTextureID, firstPerson,
            fogData.renderDistanceStart, fogData.renderDistanceEnd,
            fogColor.x(), fogColor.y(), fogColor.z(), fogColor.w(),
            0 /* fogShape: SPHERE (FogData no longer carries a shape) TODO */, skyType,
            endSkyTextureID, endPortalTextureID, lightMapTextureID, flickerTime);

        // ===================== Sky uniform =====================
        int baseColor = skyRenderState.skyColor;
        int horizonColor = skyRenderState.sunriseAndSunsetColor;
        // skyRenderState.sunAngle reads 0 every frame -- MC assigns it during the sky render pass that this
        // mixin cancels, so it's left at its reset() default, freezing the sun at the zenith (0,1,0) and
        // leaving every surface unlit while the sky still renders. 26.2 removed Level.getTimeOfDay/getSunAngle
        // (now WorldClock/Timeline), so compute the day-angle from the level time ourselves with MC's classic
        // sky-angle formula, so the sun tracks the day/night cycle.
        long radiance$dayTime = levelRenderState.gameTime % 24000L;
        double radiance$dayFrac = (double) radiance$dayTime / 24000.0 - 0.25;
        radiance$dayFrac -= Math.floor(radiance$dayFrac);
        double radiance$cosAdj = 0.5 - Math.cos(radiance$dayFrac * Math.PI) / 2.0;
        float sunAngle = (float) ((radiance$dayFrac * 2.0 + radiance$cosAdj) / 3.0);

        Matrix4f sunRotation = new Matrix4f();
        sunRotation.rotate(Axis.YP.rotationDegrees(-90.0F));
        sunRotation.rotate(Axis.XP.rotationDegrees(sunAngle * 360.0F));
        Vector3f sunDirection = sunRotation.transformPosition(0.0F, 1.0F, 0.0F, new Vector3f())
            .normalize();

        boolean sunRisingOrSetting = ARGB.alpha(horizonColor) > 0;
        boolean hasBlindnessOrDarkness = cameraState.entityRenderState.doesMobEffectBlockSky;
        // 26.2 TODO: no direct render-state flag replaces isSkyDark(tickDelta); default false.
        boolean skyDark = false;
        int submersionType = cameraState.fogType.ordinal();
        int moonPhase = skyRenderState.moonPhase.index();
        float rainGradient = level != null ? level.getRainLevel(partialTick) : 0.0F;
        // 26.2 stitches the sun and each moon phase into a shared `celestials` TextureAtlas (SkyRenderer),
        // so there is no standalone sun/moon GL texture to hand the RT sky shader. The source PNGs still
        // ship, though, and TextureManager.getTexture lazily registers+loads any path as its own
        // full-extent SimpleTexture (the exact route END_SKY_LOCATION above already relies on). Load the
        // sun and the *current* phase's moon disc as standalone textures; each moon phase is now its own
        // file (moon_phases.png's 4x2 grid is gone), named by MoonPhase.getSerializedName().
        int sunTextureID = radiance$resolveGlId(textureManager
            .getTexture(Identifier.withDefaultNamespace("textures/environment/celestial/sun.png"))
            .getTextureView());
        int moonTextureID = radiance$resolveGlId(textureManager
            .getTexture(Identifier.withDefaultNamespace("textures/environment/celestial/moon/"
                + skyRenderState.moonPhase.getSerializedName() + ".png"))
            .getTextureView());

        // ===================== Handheld dynamic lights =====================
        // Vanilla has no dynamic lighting; when the player carries a light-emitting item, synthesize a
        // point light at the player so it casts real light into the ray-traced world (a carried torch).
        // Position is camera-relative scene space (world - cameraPos, matching worldUBO.cameraPos), so it
        // needs no absolute coordinates. Main and off hand collapse to one light at the max level.
        int radiance$dynamicLightCount = 0;
        float[] radiance$dynamicLights = new float[BufferProxy.MAX_DYNAMIC_LIGHTS * 8];
        Player radiance$player = client.player;
        if (radiance$player != null) {
            ItemStack radiance$mainItem = radiance$player.getMainHandItem();
            ItemStack radiance$offItem = radiance$player.getOffhandItem();
            int radiance$mainLevel = radiance$heldLightLevel(radiance$mainItem);
            int radiance$offLevel = radiance$heldLightLevel(radiance$offItem);
            int radiance$level = Math.max(radiance$mainLevel, radiance$offLevel);
            if (radiance$level > 0) {
                // Sit the light a little below the eye, like a torch held low.
                Vec3 radiance$lightPos = radiance$player.getEyePosition(partialTick).subtract(0.0, 0.4, 0.0);
                // Send the BASE light; the shader pack's config scales it (handheld_light_gain on the
                // intensity, handheld_light_range on the range cap). intensity = (level/15)^2, range =
                // level blocks. Both packs read the same pack-agnostic SkyUBO values.
                float radiance$t = radiance$level / 15.0F;
                // Match a PLACED torch's color: the light item's block emissive hue, from the same
                // EmissiveBlockColor the chunk area lights use.
                ItemStack radiance$lightItem = radiance$mainLevel >= radiance$offLevel
                    ? radiance$mainItem : radiance$offItem;
                float[] radiance$color =
                    EmissiveBlockColor.ofBlockOrWarm(Block.byItem(radiance$lightItem.getItem()));
                radiance$dynamicLights[0] = (float) (radiance$lightPos.x - cameraState.pos.x);
                radiance$dynamicLights[1] = (float) (radiance$lightPos.y - cameraState.pos.y);
                radiance$dynamicLights[2] = (float) (radiance$lightPos.z - cameraState.pos.z);
                radiance$dynamicLights[3] = radiance$t * radiance$t;
                radiance$dynamicLights[4] = radiance$color[0];
                radiance$dynamicLights[5] = radiance$color[1];
                radiance$dynamicLights[6] = radiance$color[2];
                radiance$dynamicLights[7] = radiance$level;
                radiance$dynamicLightCount = 1;
            }
        }

        BufferProxy.updateSkyUniform(ARGB.redFloat(baseColor), ARGB.greenFloat(baseColor),
            ARGB.blueFloat(baseColor), ARGB.redFloat(horizonColor), ARGB.greenFloat(horizonColor),
            ARGB.blueFloat(horizonColor), ARGB.alphaFloat(horizonColor), sunDirection, skyType,
            sunRisingOrSetting, skyDark, hasBlindnessOrDarkness, submersionType, moonPhase,
            rainGradient, sunTextureID, moonTextureID, radiance$dynamicLightCount, radiance$dynamicLights);

        BufferProxy.updateMapping();

        // ===================== Entities (raw -> stable identity) =====================
        EntityRenderDispatcher entityRenderDispatcher = client.getEntityRenderDispatcher();
        List<Entity> entities = new ArrayList<>();
        if (level != null) {
            Entity cameraEntity = gameRenderer.mainCamera().entity();
            Frustum frustum = cameraState.cullFrustum;
            Vec3 camPos = cameraState.pos;
            double loosenSq = 48.0 * 48.0; // old loosenEntityFiltering radius (16 * 3)
            for (Entity entity : level.entitiesForRendering()) {
                if (entity == cameraEntity // force first-person body (old enablePlayerRenderer...)
                    || entity.distanceToSqr(camPos) < loosenSq
                    || entityRenderDispatcher.shouldRender(entity, frustum, camPos.x, camPos.y,
                    camPos.z)) {
                    entities.add(entity);
                }
            }
        }
        EntityProxy.queueEntitiesBuild(gameRenderer.mainCamera(), entities, entityRenderDispatcher,
            deltaTracker, levelRenderState.shouldShowEntityOutlines);

        // ===================== Block entities (pre-extracted render states) =====================
        EntityProxy.queueBlockEntitiesRebuild(levelRenderState.blockEntityRenderStates, cameraState);

        // ===================== Block outline =====================
        if (renderOutline && level != null) {
            EntityProxy.queueTargetBlockOutlineRebuild(gameRenderer.mainCamera(), level);
        }

        // ===================== Block-breaking crumbling =====================
        EntityProxy.queueCrumblingRebuild(levelRenderState);

        // ===================== Particles + weather =====================
        // 26.2: both are captured off their render states (ParticleEngine.extract /
        // WeatherEffectRenderer.extractRenderState) into the mod's PBRVertexConsumer and injected as
        // world-RT geometry -- the native post-render pass their vanilla path targets does not composite
        // in 26.2. See EntityProxy.queueParticleRebuild / queueWeatherBuild.
        if (level != null) {
            EntityProxy.queueParticleRebuild(gameRenderer.mainCamera(), partialTick,
                cameraState.cullFrustum);
            EntityProxy.queueWeatherBuild(this.weatherEffectRenderer, level,
                gameRenderer.mainCamera(), partialTick);
        }

        // ===================== Clouds (CloudRendererMixins intercepts render() for capture) =======
        CloudStatus cloudStatus = optionsState.cloudStatus;
        if (cloudStatus != CloudStatus.OFF && ARGB.alpha(levelRenderState.cloudColor) > 0) {
            this.cloudRenderer.render(levelRenderState.cloudColor, cloudStatus,
                levelRenderState.cloudHeight, optionsState.cloudRange, cameraState.pos,
                levelRenderState.gameTime, partialTick);
        }

        // ===================== Chunk terrain =====================
        // LevelRenderer.render (cancelled below) is where compileSections would call
        // RenderSection.compileAsync -> ChunkProxy.enqueueRebuild. Since we cancel it, enqueue this
        // frame's dirty sections (the extractor's per-frame update set) into the mod's native rebuild
        // pipeline, then drain the queue (build + upload to the Vulkan backend).
        ViewArea viewArea = this.viewArea();
        if (viewArea != null) {
            // Follow the camera. 26.2 moved the per-frame ViewArea grid rotation into
            // LevelRenderer.repositionCamera, which is called ONLY from LevelRenderer.render (near its
            // head) -- the method this mixin cancels at HEAD. Without replicating it the
            // RotatingSectionStorage stays frozen where invalidateCompiledGeometry last centered it
            // (world load / F3+A / settings change), so once the player walks past that radius
            // sections.getValue(node) returns null for their surroundings, nothing enqueues or builds,
            // and the ray-traced terrain goes invisible "after walking a certain distance". Replicating
            // the ViewArea.repositionCamera call rotates the grid to follow the player, firing the mod's
            // relocateSingle / updateSectionPos hooks (native chunk grid stays in sync) and letting the
            // occlusion graph below see the moved grid. The worldborder invalidate + translucency-sort
            // camera set that LevelRenderer.repositionCamera also does aren't needed here (no MC terrain
            // pass runs; translucency is sorted in the native backend). The call internally no-ops until
            // the camera crosses a section boundary, so it is cheap to invoke every frame.
            viewArea.repositionCamera(SectionPos.of(cameraState.pos));

            RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections =
                ((IViewAreaExt) viewArea).radiance$getSections();
            for (SectionUpdateRenderState sectionUpdate : levelRenderState.sectionUpdateRenderStates) {
                SectionRenderDispatcher.RenderSection section = sections.getValue(
                    sectionUpdate.sectionNode());
                if (section != null) {
                    ChunkProxy.enqueueRebuild(section);
                }
            }
        }
        ChunkProxy.rebuild(gameRenderer.mainCamera());

        // ===================== Section occlusion graph =====================
        // Vanilla render() ends with updateSectionOcclusion -> sectionOcclusionGraph.update(), which
        // runs the BFS from the camera that fills the graph storage. LevelExtractor reads that storage
        // on the NEXT frame (consumeFrustumUpdate() -> applyFrustum -> addSectionsInFrustum ->
        // visibleSections -> the dirty subset -> sectionUpdateRenderStates, the enqueue loop above).
        // Because we cancel render(), that BFS never ran: visibleSections stayed empty, nothing was
        // ever dirty/enqueued, no chunk got a BLAS -> the ray-traced world was black. We reimplement
        // compileSections above but must also drive updateSectionOcclusion here to restore the pipeline.
        //
        // smartCull=false: with smart cull ON, the BFS only propagates through a section when its
        // SectionMesh reports facesCanSeeEachother -- but we build sections through ChunkProxy (calling
        // SectionCompiler.compile directly), never through the SectionRenderDispatcher, so the
        // RenderSection's SectionMesh stays UNCOMPILED (facesCanSeeEachother=false) AND the dispatcher's
        // sectionOcclusionGraph::schedulePropagationFrom callback never fires. That dead-ends the flood
        // at the camera's first ring (~9 sections). A ray tracer does its own visibility, so we don't
        // want occlusion culling gating which chunks build: forcing smartCull off makes runUpdates skip
        // the face-visibility gate and flood every loaded in-frustum section into the TLAS. Only other
        // reader of this flag is the F3 "(s)" debug string, so mutating the extracted state is harmless.
        cameraState.smartCull = false;
        this.sectionOcclusionGraph().update(cameraState, optionsState.fov,
            levelRenderState.chunkLoadingRenderState);

        ci.cancel();
    }

    /**
     * 26.2: resolves the raw GL texture id backing a {@link GpuTextureView} (RenderPhase / direct GL
     * texture handles are gone), mirroring the resolution in {@code EntityProxy}/{@code
     * StorageVertexConsumerProvider}.
     */
    @Unique
    private static int radiance$resolveGlId(GpuTextureView view) {
        return view != null && view.texture() instanceof GlTexture glTexture ? glTexture.glId() : 0;
    }

    /**
     * Vanilla block light level (0-15) of a held item, or 0 if it emits no light. Maps the item back to
     * its block ({@link Block#byItem}) and reads the default state's emission -- torch, lantern,
     * glowstone, sea lantern, jack o'lantern, redstone torch, etc. Non-block or non-emissive items and
     * empty stacks return 0 (AIR emits 0).
     */
    @Unique
    private static int radiance$heldLightLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return Block.byItem(stack.getItem()).defaultBlockState().getLightEmission();
    }
}
