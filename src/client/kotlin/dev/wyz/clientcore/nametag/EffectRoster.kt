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

    /** Whether a remote player is a River user broadcasting their badge. */
    @JvmStatic
    fun hasRemoteBadge(uuid: UUID): Boolean = remoteBadges.contains(uuid)

    /** The cape style a remote River user is wearing, or null. */
    @JvmStatic
    fun remoteCapeStyle(uuid: UUID): String? = remoteCapes[uuid]

    @JvmStatic
    fun setRemoteRoster(badges: Set<UUID>, capes: Map<UUID, String>) {
        remoteBadges = badges.toSet()
        remoteCapes = capes.toMap()
    }

    @JvmStatic
    fun clearRemoteRoster() {
        remoteBadges = emptySet()
        remoteCapes = emptyMap()
    }
}
