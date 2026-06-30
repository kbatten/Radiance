package com.radiance.client.texture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;

public class AuxiliaryTextureReloader implements PreparableReloadListener {

    // 26.2: reload(Synchronizer, ResourceManager, Executor, Executor) became
    // reload(SharedState, Executor taskExecutor, PreparationBarrier, Executor reloadExecutor);
    // the ResourceManager now comes from SharedState#resourceManager().
    @Override
    public CompletableFuture<Void> reload(PreparableReloadListener.SharedState currentReload,
        Executor taskExecutor, PreparableReloadListener.PreparationBarrier preparationBarrier,
        Executor reloadExecutor) {
        return AuxiliaryTextures.prepareDecodedImagesAsync(currentReload.resourceManager(),
                taskExecutor)
            .thenCompose(preparationBarrier::wait)
            .thenAcceptAsync(AuxiliaryTextures::applyPreparedImages, reloadExecutor);
    }
}
