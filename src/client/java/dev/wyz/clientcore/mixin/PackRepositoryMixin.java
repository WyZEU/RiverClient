package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.resources.RiverBuiltinPack;
import dev.wyz.clientcore.resources.RiverGlobalDataPacks;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Registers River's own packs into the right repository:
 *  - the CLIENT resource repository (has a ClientPackSource) gets River's built-in pack;
 *  - the integrated server's DATA repository (has a ServerPacksSource) gets River's global
 *    data-pack folder, so packs downloaded from the in-game browser apply to every world.
 * A repository is only ever one of the two, so the branches are mutually exclusive.
 */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {

    @Mutable
    @Shadow
    @Final
    private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void clientcore$addRiverPacks(RepositorySource[] providedSources, CallbackInfo ci) {
        boolean isClientResources = false;
        boolean isServerData = false;
        for (RepositorySource source : this.sources) {
            if (source instanceof ClientPackSource) {
                isClientResources = true;
                break;
            }
            if (source instanceof ServerPacksSource) {
                isServerData = true;
            }
        }
        if (isClientResources) {
            this.sources = RiverBuiltinPack.appendTo(this.sources);
        } else if (isServerData) {
            this.sources = RiverGlobalDataPacks.appendTo(this.sources);
        }
    }
}
