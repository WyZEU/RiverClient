package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.media.MusicJam
import dev.wyz.clientcore.net.RiverSocial
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverScreen
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class RiverMusicJamScreen(private val parent: Screen?) : Screen(Component.literal("Music Jam")), RiverScreen {
    private val mc = Minecraft.getInstance()
    private var seen = -1
    private var tab = 0
    private var page = 0
    private val labels = ArrayList<Triple<String, Int, Int>>()
    private var x = 0
    private var y = 0
    private var w = 0
    private var h = 0

    override fun init() {
        rebuild()
        RiverSocial.ensureSession { ok ->
            if (ok) { RiverSocial.refresh(force = true); MusicJam.refresh() }
            seen = -1
        }
    }

    override fun tick() {
        if (seen != MusicJam.revision) rebuild()
    }

    private fun button(text: String, bx: Int, by: Int, bw: Int, enabled: Boolean = true, action: () -> Unit) {
        addRenderableWidget(object : Button(bx, by, bw, 20, Component.literal(text), { action() }, { it.get() }) {
//? if >=26.1 {
/*            override fun extractContents(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} elif >=1.21.11 {
            override fun renderContents(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?} else {
/*            override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?}
                ClientUi.drawFlatButton(g, font, this.x, this.y, this.width, this.height, message.string, active && isHoveredOrFocused)
                if (!active) g.fill(this.x, this.y, this.x + this.width, this.y + this.height, 0x66000000)
                if (isFocused) ClientUi.drawRoundedBorder(g, this.x, this.y, this.width, this.height, 4, ClientUi.ACCENT_B)
            }
        }).active = enabled
    }

    private fun rebuild() {
        clearWidgets()
        labels.clear()
        seen = MusicJam.revision
        w = minOf(420, width - 16); h = minOf(310, height - 16)
        x = (width - w) / 2; y = (height - h) / 2
        val room = MusicJam.room
        val usable = RiverSocial.signedIn && !MusicJam.busy && mc.level != null
        val footer = y + h - 28
        button("Back", x + w - 76, footer, 64) { onClose() }
        if (room != null) {
            button(if (MusicJam.hosting) "End jam" else "Leave jam", x + 12, footer, 84, !MusicJam.busy) { MusicJam.leave() }
            labels += Triple("${room.hostName}'s jam", x + 12, y + 34)
            labels += Triple(room.track.title, x + 12, y + 48)
            val members = room.members.associateBy { it.uuid }
            val rows = if (MusicJam.hosting) (room.members.map { it.uuid to it.name } +
                RiverSocial.friends.filter { it.uuid !in members && it.online }.map { it.uuid to it.name }) else room.members.map { it.uuid to it.name }
            val listY = 68
            val count = ((h - listY - 62) / 24).coerceAtLeast(1)
            page = page.coerceIn(0, ((rows.size - 1) / count).coerceAtLeast(0))
            rows.drop(page * count).take(count).forEachIndexed { i, (uuid, name) ->
                val ry = y + listY + i * 24
                labels += Triple(name, x + 12, ry + 6)
                if (uuid in members) labels += Triple(if (uuid == room.host) "Host" else "Joined", x + w - 76, ry + 6)
                else button("Invite", x + w - 76, ry, 64, usable) { MusicJam.invite(uuid) }
            }
            pager(rows.size, count, footer - 26)
        } else {
            button("Host a jam", x + 12, y + 30, (w - 30) / 2) { tab = 0; page = 0; rebuild() }
            button("Invites (${MusicJam.invites.size})", x + w / 2 + 3, y + 30, (w - 30) / 2) { tab = 1; page = 0; MusicJam.refresh(); rebuild() }
            if (tab == 0) {
                val source = MusicJam.sourceName()
                labels += Triple(if (source.isEmpty()) "No song playing" else "Playing in $source", x + 12, y + 62)
                labels += Triple("You will be the host. Friends follow you.", x + 12, y + 76)
                button("Start jam", x + 12, y + 96, w - 24, usable && source.isNotEmpty()) { MusicJam.start() }
            } else {
                val invites = MusicJam.invites.filter { it.expiresAt > System.currentTimeMillis() }
                val count = ((h - 120) / 48).coerceAtLeast(1)
                page = page.coerceIn(0, ((invites.size - 1) / count).coerceAtLeast(0))
                invites.drop(page * count).take(count).forEachIndexed { i, invite ->
                    val ry = y + 60 + i * 48
                    labels += Triple(invite.hostName, x + 12, ry)
                    button("Join & play Spotify", x + 12, ry + 14, (w - 30) / 2, usable) { MusicJam.join(invite, true) }
                    button("Lyrics only", x + w / 2 + 3, ry + 14, (w - 30) / 2, usable) { MusicJam.join(invite, false) }
                }
                if (invites.isEmpty()) labels += Triple("No invitations", x + 12, y + 66)
                pager(invites.size, count, footer - 26)
            }
        }
    }

    private fun pager(total: Int, count: Int, by: Int) {
        if (total <= count) return
        button("<", x + w / 2 - 28, by, 24, page > 0) { page--; rebuild() }
        button(">", x + w / 2 + 4, by, 24, (page + 1) * count < total) { page++; rebuild() }
    }

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        ClientUi.drawBackdrop(g, width, height)
        ClientUi.beginFrame()
        ClientUi.drawPanel(g, x, y, w, h)
        ClientUi.drawGradientTitle(g, font, "Music Jam", x + 12, y + 12)
        for ((text, tx, ty) in labels) g.drawString(font, font.plainSubstrByWidth(text, x + w - tx - 12), tx, ty, ClientUi.TEXT, false)
        val notice = when {
            mc.level == null -> "Join a world to start a jam."
            !RiverSocial.signedIn -> if (RiverSocial.signingIn) "Signing in..." else RiverSocial.error.ifEmpty { "Sign in to River Friends." }
            MusicJam.busy -> "Connecting..."
            MusicJam.message.isNotEmpty() -> MusicJam.message
            MusicJam.following -> MusicJam.playerMessage
            MusicJam.hosting -> "You are the host. Invite online friends."
            else -> "Shares your song and lyrics with friends."
        }
        g.drawString(font, font.plainSubstrByWidth(notice, w - 24), x + 12, y + h - 40, ClientUi.MUTED, false)
//? if >=26.1 {
/*        super.extractRenderState(g, mouseX, mouseY, partialTick)
*///?} else {
        super.render(g, mouseX, mouseY, partialTick)
//?}
    }

    override fun onClose() { mc.setScreen(parent) }
    override fun isPauseScreen(): Boolean = false
}
