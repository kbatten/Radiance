package com.radiance.mixins.vulkan_options;

import static net.minecraft.client.InactivityFpsLimit.AFK;
import static net.minecraft.client.Options.genericValueLabel;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.serialization.Codec;
import com.radiance.client.gui.PotentialValuesBasedCallbacksNoValue;
import com.radiance.client.gui.RenderPipelineScreen;
import com.radiance.client.option.Options;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: {@code VideoOptionsScreen} became {@code VideoSettingsScreen}, {@code SimpleOption} became
 * {@code OptionInstance}, and the custom category rows ({@code CategoryVideoOptionEntry}, retired) are
 * now {@code OptionsList.addHeader}. The stock options are reached through the parent's {@code options}
 * ({@code net.minecraft.client.Options}) accessors; single/paired rows use {@code addBig}/{@code
 * addSmall}. The fullscreen-resolution logic follows vanilla {@code VideoSettingsScreen.addOptions}
 * ({@code Window#findBestMonitor}, {@code Monitor#indexOfMode}/{@code #mode}/{@code #modeCount},
 * {@code Window#setPreferredFullscreenVideoMode}).
 */
@Mixin(VideoSettingsScreen.class)
public class VideoOptionsScreenMixins extends GameOptionsScreenMixins {

    @Unique
    private static final Component INACTIVITY_FPS_LIMIT_MINIMIZED_TOOLTIP = Component.translatable(
        "options.inactivityFpsLimit.minimized.tooltip");
    @Unique
    private static final Component INACTIVITY_FPS_LIMIT_AFK_TOOLTIP = Component.translatable(
        "options.inactivityFpsLimit.afk.tooltip");

    @Unique
    private static final PotentialValuesBasedCallbacksNoValue<Boolean> BOOLEAN_NO_KEY = new PotentialValuesBasedCallbacksNoValue<>(
        ImmutableList.of(Boolean.TRUE, Boolean.FALSE), Codec.BOOL
    );

    @Inject(method = "addOptions()V", at = @At(value = "HEAD"), cancellable = true)
    public void redirectAddOptions(CallbackInfo ci) {
        OptionInstance<Integer> maxFps = new OptionInstance<>("options.framerateLimit",
            OptionInstance.noTooltip(),
            (optionText, value) -> value == 260 ?
                genericValueLabel(optionText, Component.translatable("options.framerateLimit.max"))
                :
                    genericValueLabel(optionText,
                        Component.translatable("options.framerate", value)),
            new OptionInstance.IntRange(1, 26).xmap(value -> value * 10, value -> value / 10, true),
            Codec.intRange(10, 260),
            Options.maxFps,
            value -> {
                Minecraft.getInstance().getFramerateLimitTracker().setFramerateLimit(value);
                Options.setMaxFps(value, true);
            });

        Window window = Minecraft.getInstance().getWindow();
        Monitor monitor = window.findBestMonitor();
        int j;
        if (monitor == null) {
            j = -1;
        } else {
            Optional<VideoMode> optional = window.getPreferredFullscreenVideoMode();
            j = optional.map(monitor::indexOfMode).orElse(-1);
        }

        OptionInstance<Integer> fullScreenResolutionOption = new OptionInstance<>(
            "options.fullscreen.resolution", OptionInstance.noTooltip(),
            (optionText, value) -> {
                if (monitor == null) {
                    return Component.translatable("options.fullscreen.unavailable");
                } else if (value == -1) {
                    return genericValueLabel(optionText,
                        Component.translatable("options.fullscreen.current"));
                } else {
                    VideoMode videoMode = monitor.mode(value);
                    return genericValueLabel(optionText,
                        Component.translatable("options.fullscreen.entry",
                            videoMode.getWidth(),
                            videoMode.getHeight(),
                            videoMode.getRefreshRate(),
                            videoMode.getRedBits() + videoMode.getGreenBits() +
                                videoMode.getBlueBits()));
                }
            }, new OptionInstance.IntRange(-1,
            monitor != null ? monitor.modeCount() - 1 : -1), j, value -> {
            if (monitor != null) {
                window.setPreferredFullscreenVideoMode(
                    value == -1 ? Optional.empty() : Optional.of(monitor.mode(value)));
            }
        });

        OptionInstance<InactivityFpsLimit> inactivityFpsLimit = new OptionInstance<>(
            "options.inactivityFpsLimit",
            value -> {
                return switch (value) {
                    case MINIMIZED -> Tooltip.create(INACTIVITY_FPS_LIMIT_MINIMIZED_TOOLTIP);
                    case AFK -> Tooltip.create(INACTIVITY_FPS_LIMIT_AFK_TOOLTIP);
                };
            },
            (optionText, value) -> value.caption(),
            new OptionInstance.Enum<>(Arrays.asList(InactivityFpsLimit.values()),
                InactivityFpsLimit.CODEC),
            AFK,
            inactivityLimit -> {
                Options.setInactivityFpsLimit(
                    inactivityLimit == AFK ? 30 : 9, true);
            });

        OptionInstance<Boolean> enableVsync = OptionInstance.createBoolean("options.vsync",
            Options.vsync,
            value -> {
                if (Minecraft.getInstance().getWindow() != null) {
                    Options.setVsync(value, true);
                }
            });

        OptionInstance<Integer> chunkBuildingBatchSize = new OptionInstance<>(
            Options.CHUNK_BUILDING_BATCH_SIZE_KEY,
            OptionInstance.noTooltip(),
            (optionText, value) -> genericValueLabel(optionText,
                Component.literal(Integer.toString(value))),
            new OptionInstance.IntRange(1, 32),
            Codec.intRange(1, 32),
            Options.chunkBuildingBatchSize,
            value -> Options.setChunkBuildingBatchSize(value, true));

        OptionInstance<Integer> chunkBuildingTotalBatches = new OptionInstance<>(
            Options.CHUNK_BUILDING_TOTAL_BATCHES_KEY,
            OptionInstance.noTooltip(),
            (optionText, value) -> genericValueLabel(optionText,
                Component.literal(Integer.toString(value))),
            new OptionInstance.IntRange(1, 32),
            Codec.intRange(1, 32),
            Options.chunkBuildingTotalBatches,
            value -> Options.setChunkBuildingTotalBatches(value, true));

        OptionInstance<Integer> chunkBuildingThreads = new OptionInstance<>(
            Options.CHUNK_BUILDING_THREADS_KEY, OptionInstance.noTooltip(),
            (optionText, value) -> genericValueLabel(optionText,
                Component.literal(Integer.toString(value))),
            new OptionInstance.IntRange(1, Options.getMaxChunkBuildingThreads()),
            Codec.intRange(1, Options.getMaxChunkBuildingThreads()),
            Options.chunkBuildingThreads,
            value -> Options.setChunkBuildingThreads(value, true));

        OptionInstance<Boolean> collectChunkEmission = OptionInstance.createBoolean(
            Options.COLLECT_CHUNK_EMISSION_KEY,
            Options.collectChunkEmission,
            value -> Options.setCollectChunkEmission(value, true));

        OptionInstance<Boolean> pipelineSettings = new OptionInstance<>(Options.PIPELINE_SETUP_KEY,
            OptionInstance.noTooltip(),
            (optionText, value) -> optionText,
            BOOLEAN_NO_KEY,
            false,
            value -> Minecraft.getInstance().gui
                .setScreen(new RenderPipelineScreen((Screen) (Object) this)));

        // Adding categories and options
        this.list.addHeader(Component.translatable(Options.CATEGORY_GAMEPLAY));
        OptionInstance<?>[] optionsGameplay = new OptionInstance<?>[]{ //
            this.options.graphicsPreset(), //
            this.options.renderDistance(), //
            this.options.simulationDistance(), //
            this.options.guiScale(), //
            this.options.attackIndicator(), //
            this.options.gamma(), //
            this.options.cloudStatus(), //
            this.options.particles(), //
            this.options.screenEffectScale(), //
            this.options.entityDistanceScaling(), //
            this.options.fovEffectScale(), //
            this.options.showAutosaveIndicator(), //
            this.options.glintSpeed(), //
            this.options.glintStrength(), //
            this.options.menuBackgroundBlurriness(), //
            this.options.bobView(), //
        };
        this.list.addBig(this.options.biomeBlendRadius());
        this.list.addBig(this.options.mipmapLevels());
        this.list.addSmall(optionsGameplay);

        this.list.addHeader(Component.translatable(Options.CATEGORY_WINDOW));
        OptionInstance<?>[] optionsWindow = new OptionInstance<?>[]{ //
            maxFps, //
            inactivityFpsLimit, //
            enableVsync, //
            this.options.fullscreen(), //
        };
        this.list.addSmall(optionsWindow);
        this.list.addBig(fullScreenResolutionOption);

        this.list.addHeader(Component.translatable(Options.CATEGORY_TERRAIN));
        this.list.addBig(chunkBuildingBatchSize);
        this.list.addBig(chunkBuildingTotalBatches);
        this.list.addBig(chunkBuildingThreads);
        this.list.addBig(collectChunkEmission);

        this.list.addHeader(Component.translatable(Options.CATEGORY_PIPELINE));
        this.list.addBig(pipelineSettings);

        ci.cancel();
    }
}
