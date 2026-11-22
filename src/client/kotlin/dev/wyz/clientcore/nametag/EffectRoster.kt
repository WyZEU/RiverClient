package dev.wyz.clientcore.nametag

import java.util.UUID

/**
 * Which cosmetics remote River users on this server are wearing (badge + cape style).
 *
 * Populated by [dev.wyz.clientcore.net.PresenceService], which asks the River presence
 * backend who else on the same (hashed) server is running River Client with cosmetics on.
 */
object EffectRoster {

    @Volatile
    private var remoteBadges: Set<UUID> = emptySet()

    @Volatile
    private var remoteCapes: Map<UUID, String> = emptyMap()

    @Volatile
    private var remotePlus: Set<UUID> = emptySet()

    @Volatile
    private var remoteCloth: Set<UUID> = emptySet()
    @Volatile
    private var remoteWings: Map<UUID, String> = emptyMap()

    /** Whether a remote player is a River user broadcasting their badge. */
    @JvmStatic
    fun hasRemoteBadge(uuid: UUID): Boolean = remoteBadges.contains(uuid)

    /** Whether the server says a remote River user has River Plus (the gold badge). */
    @JvmStatic
    fun hasRemotePlus(uuid: UUID): Boolean = remotePlus.contains(uuid)

    /** Whether a remote Plus player has cloth physics on for their cape (the server checks Plus). */
    @JvmStatic
    fun hasRemoteCloth(uuid: UUID): Boolean = remoteCloth.contains(uuid)

    /** The cape style a remote River user is wearing, or null. */
    @JvmStatic
    fun remoteCapeStyle(uuid: UUID): String? = remoteCapes[uuid]

    fun remoteWingStyle(uuid: UUID): String? = remoteWings[uuid]

    @JvmStatic
    fun setRemoteRoster(
        badges: Set<UUID>, capes: Map<UUID, String>, plus: Set<UUID> = emptySet(),
        cloth: Set<UUID> = emptySet(), wings: Map<UUID, String> = emptyMap()
    ) {
        remoteBadges = badges.toSet()
        remoteCapes = capes.toMap()
        remotePlus = plus.toSet()
        remoteCloth = cloth.toSet()
        remoteWings = wings.toMap()
    }

    @JvmStatic
    fun clearRemoteRoster() {
        remoteBadges = emptySet()
        remoteCapes = emptyMap()
        remotePlus = emptySet()
        remoteCloth = emptySet()
        remoteWings = emptyMap()
    }
}
