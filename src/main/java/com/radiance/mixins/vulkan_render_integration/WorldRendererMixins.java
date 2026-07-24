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
import com.radiance.mixin_related.extensions.vulkan_render_integration.IViewAreaExt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
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
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
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
    public abstract ViewArea viewArea();

    @Shadow
    public abstract SectionOcclusionGraph sectionOcclusionGraph();

    // TEMP diagnostic: terrain never builds. This hook is the only place sections are enqueued for rebuild
    // (MC's compileAsync is skipped because render is cancelled). Render thread only, so a plain counter is
    // fine.
    @org.spongepowered.asm.mixin.Unique
    private static int radiance$sectionEnqLog = 0;

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
        Matrix4f projectionMatrix = new Matrix4f(cameraState.projectionMatrix);
        // 26.2 TODO: RenderSystem.getTextureMatrix is gone (glint is a UBO now); identity is the
        // safe best-effort until the glint matrix is re-sourced.
        Matrix4f glintTextureMatrix = new Matrix4f();

        // Classic ShaderGameTime fraction (RenderSystem.setShaderGameTime is gone in 26.2).
        float gameTime = ((float) (levelRenderState.gameTime % 24000L) + partialTick) / 24000.0F;
        int overlayTextureID = radiance$resolveGlId(gameRenderer.overlayTexture().getTextureView());
        boolean firstPerson = client.options.getCameraType().isFirstPerson();

        FogData fogData = cameraState.fogData;
        int skyType = skyRenderState.skybox.ordinal();
        int endSkyTextureID = radiance$resolveGlId(
            textureManager.getTexture(AbstractEndPortalRenderer.END_SKY_LOCATION).getTextureView());
        int endPortalTextureID = radiance$resolveGlId(
            textureManager.getTexture(AbstractEndPortalRenderer.END_PORTAL_LOCATION).getTextureView());
        // levelLightmap() exposes the level lightmap GpuTextureView directly (no ILightMapManagerExt).
        int lightMapTextureID = radiance$resolveGlId(gameRenderer.levelLightmap());

        BufferProxy.updateWorldUniform(viewMatrix, effectedViewMatrix, projectionMatrix,
            glintTextureMatrix, gameTime, overlayTextureID, firstPerson,
            fogData.renderDistanceStart, fogData.renderDistanceEnd,
            fogColor.x(), fogColor.y(), fogColor.z(), fogColor.w(),
            0 /* fogShape: SPHERE (FogData no longer carries a shape) TODO */, skyType,
            endSkyTextureID, endPortalTextureID, lightMapTextureID);

        // ===================== Sky uniform =====================
        int baseColor = skyRenderState.skyColor;
        int horizonColor = skyRenderState.sunriseAndSunsetColor;
        float sunAngle = skyRenderState.sunAngle;

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
        // 26.2 TODO: sun/moon are atlas sprites now (SkyRenderer), not standalone textures; 0 until
        // the atlas sprite GL ids + UVs are resolved for the native sky shader.
        int sunTextureID = 0;
        int moonTextureID = 0;

        BufferProxy.updateSkyUniform(ARGB.redFloat(baseColor), ARGB.greenFloat(baseColor),
            ARGB.blueFloat(baseColor), ARGB.redFloat(horizonColor), ARGB.greenFloat(horizonColor),
            ARGB.blueFloat(horizonColor), ARGB.alphaFloat(horizonColor), sunDirection, skyType,
            sunRisingOrSetting, skyDark, hasBlindnessOrDarkness, submersionType, moonPhase,
            rainGradient, sunTextureID, moonTextureID);

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

        // 26.2 TODO: particle / weather capture (EntityProxy no-op stubs for now).

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
        int radiance$updates = 0;
        int radiance$enqueued = 0;
        if (viewArea != null) {
            RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections =
                ((IViewAreaExt) viewArea).radiance$getSections();
            for (SectionUpdateRenderState sectionUpdate : levelRenderState.sectionUpdateRenderStates) {
                radiance$updates++;
                SectionRenderDispatcher.RenderSection section = sections.getValue(
                    sectionUpdate.sectionNode());
                if (section != null) {
                    ChunkProxy.enqueueRebuild(section);
                    radiance$enqueued++;
                }
            }
        }
        if ((radiance$sectionEnqLog++ % 200) == 0 || (radiance$updates > 0 && radiance$enqueued == 0)) {
            // visibleSections() is filled by SectionOcclusionGraph.addSectionsInFrustum during extract;
            // sectionUpdateRenderStates (=updates) is those visible sections that are also dirty. If
            // visibleSections is ~0 the occlusion graph produced nothing (sections never compile ->
            // visibility can't propagate); if it is large but updates=0 the dirty flag is the issue.
            int radiance$visible =
                ((net.minecraft.client.renderer.LevelRenderer) (Object) this).visibleSections().size();
            com.radiance.client.RadianceClient.LOGGER.warn(
                "[ChunkEnqueue] viewArea={} visibleSections={} updates={} enqueued={}", viewArea != null,
                radiance$visible, radiance$updates, radiance$enqueued);
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
}
