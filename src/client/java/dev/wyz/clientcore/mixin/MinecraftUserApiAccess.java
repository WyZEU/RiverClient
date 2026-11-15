package dev.wyz.clientcore.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's own Mojang account service, for the premium check (see net.PremiumGate). */
@Mixin(Minecraft.class)
public interface MinecraftUserApiAccess {
    @Accessor("userApiService")
    UserApiService clientcore_userApiService();
}
