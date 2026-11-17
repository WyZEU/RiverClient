package dev.wyz.clientcore.safety

import dev.wyz.clientcore.compat.McId
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents
*///?} else {
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents
//?}
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * The server opt-out the Freelook mod (modrinth.com/mod/freelook) defined, which server plugins
 * already speak: on joining, the client sends an empty `freelook:handshake`, and a server that
 * does not allow freelook answers with an empty `freelook:disable`. River honours it, so any
 * server that has said no to freelook this way gets no freelook from River either, not only the
 * ones on [ServerSafety]'s list.
 *
 * The handshake only goes to a server that listens for it (it registers the channel), so other
 * servers never see it. With the Freelook mod itself installed, River leaves the channels to it:
 * registering them twice would stop the game.
 */
object FreelookOptOut {
    /** True once the server has said no to freelook; reset on every new connection. */
    @Volatile
    var disabledByServer = false
        private set

    private class Handshake : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Handshake> = TYPE

        companion object {
            val TYPE = CustomPacketPayload.Type<Handshake>(McId.fromNamespaceAndPath("freelook", "handshake"))
            val CODEC: StreamCodec<RegistryFriendlyByteBuf, Handshake> = StreamCodec.unit(Handshake())
        }
    }

    private class Disable : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Disable> = TYPE

        companion object {
            val TYPE = CustomPacketPayload.Type<Disable>(McId.fromNamespaceAndPath("freelook", "disable"))
            val CODEC: StreamCodec<RegistryFriendlyByteBuf, Disable> = StreamCodec.unit(Disable())
        }
    }

    fun register() {
        if (FabricLoader.getInstance().isModLoaded("freelook")) return
        runCatching {
            // Fabric API took Mojang's names on 26.x: serverbound and clientbound.
//? if >=26.1 {
/*            PayloadTypeRegistry.serverboundPlay().register(Handshake.TYPE, Handshake.CODEC)
            PayloadTypeRegistry.clientboundPlay().register(Disable.TYPE, Disable.CODEC)
*///?} else {
            PayloadTypeRegistry.playC2S().register(Handshake.TYPE, Handshake.CODEC)
            PayloadTypeRegistry.playS2C().register(Disable.TYPE, Disable.CODEC)
//?}
            ClientPlayNetworking.registerGlobalReceiver(Disable.TYPE) { _, _ -> disabledByServer = true }
            ClientPlayConnectionEvents.JOIN.register { _, sender, _ ->
                disabledByServer = false
                if (ClientPlayNetworking.canSend(Handshake.TYPE)) sender.sendPacket(Handshake())
            }
            // Servers often announce their channels just after the join itself.
//? if >=26.1 {
/*            ServerboundPlayChannelEvents.REGISTER.register { _, sender, _, channels ->
                if (Handshake.TYPE.id() in channels) sender.sendPacket(Handshake())
            }
*///?} else {
            C2SPlayChannelEvents.REGISTER.register { _, sender, _, channels ->
                if (Handshake.TYPE.id() in channels) sender.sendPacket(Handshake())
            }
//?}
            ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> disabledByServer = false }
        }.onFailure { println("[River] freelook opt-out unavailable: $it") }
    }
}
