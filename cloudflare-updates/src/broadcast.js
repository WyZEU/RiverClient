/**
 * Push notification of new releases, so the launcher stops asking.
 *
 * The launcher used to poll /latest.json - every fifteen seconds until 0.1.8.8, every
 * five minutes after it. Polling is the wrong shape for this: almost every request
 * answers "nothing has changed", the one that matters arrives up to a poll interval
 * late, and the noise buried the usage figures badly enough that the version chart read
 * in the thousands off a handful of people.
 *
 * This holds one socket per running launcher and says something only when there is
 * something to say. Suggested by Jack (BGRD).
 *
 * Hibernation is not optional here. A Durable Object with sockets attached through
 * `ws.accept()` stays resident and billed for as long as any of them is open, so a
 * persistent connection per user costs more than the polling it replaces - it would have
 * been a regression dressed as an optimisation. `state.acceptWebSocket()` hands the
 * sockets to the runtime instead: this object is evicted between messages and charged
 * only when one actually flows. Connections survive that eviction, which is why the
 * handlers below are methods on the class rather than closures over a connect scope -
 * there is no guarantee the object that receives a message is the one that accepted it.
 */

/**
 * Offered by the launcher and echoed back here. A WebSocket handshake fails unless the
 * server names one of the protocols the client asked for, so this doubles as the cheap
 * gate on the route: anything that connects without asking for it is turned away.
 */
export const PUSH_PROTOCOL = "river.updates.v1";

/** Sockets that have gone this long without a pong are assumed dead. */
const STALE_MS = 10 * 60 * 1000;

export class Broadcast {
  constructor(state, env) {
    this.state = state;
    this.env = env;

    /*
      Keepalives are answered by the runtime without waking this object. A client has to
      send something periodically or an idle proxy will drop the connection, and paying
      for a wake-up per client per interval would give back most of what hibernation buys.
    */
    try {
      this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    } catch {
      // Older runtimes without auto-response still work, they just wake for pings.
    }
  }

  /*
    The high-water mark of connected launchers per day. A count read at one instant says
    almost nothing on its own - everyone could have been connected an hour ago - so the
    peak is what makes the socket page worth looking at.
  */
  async recordPeak() {
    try {
      const day = new Date().toISOString().slice(0, 10);
      const key = "peak:" + day;
      const current = this.state.getWebSockets().length;
      const stored = (await this.state.storage.get(key)) || 0;
      if (current > stored) await this.state.storage.put(key, current);
    } catch {}
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/connect") {
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("Expected a WebSocket upgrade.", { status: 426 });
      }

      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.state.acceptWebSocket(server);

      /*
        The current release goes out on connect. Without it a launcher that starts up
        between publishes would know nothing until the next one, which would make the
        socket strictly worse than the poll it replaced for the case that matters most -
        somebody opening River for the first time in a week.
      */
      const latest = await this.state.storage.get("latest");
      if (latest) {
        try { server.send(JSON.stringify({ type: "latest", ...latest })); } catch {}
      }
      this.recordPeak();

      return new Response(null, {
        status: 101,
        webSocket: client,
        // Echoing the protocol is what completes the handshake; without it the client
        // rejects the connection.
        headers: { "Sec-WebSocket-Protocol": PUSH_PROTOCOL }
      });
    }

    if (url.pathname === "/announce" && request.method === "POST") {
      const body = await request.json().catch(() => null);
      const version = String(body?.version || "").trim();
      if (!version) return Response.json({ ok: false, message: "No version." }, { status: 400 });

      const latest = {
        version,
        minimumVersion: String(body?.minimumVersion || "").trim(),
        publishedAt: String(body?.publishedAt || "").trim(),
        announcedAt: Date.now()
      };
      await this.state.storage.put("latest", latest);

      const message = JSON.stringify({ type: "update", ...latest });
      let sent = 0;
      let dropped = 0;
      for (const socket of this.state.getWebSockets()) {
        try {
          socket.send(message);
          sent++;
        } catch {
          // A socket that throws on send is already gone; closing it keeps the count honest.
          dropped++;
          try { socket.close(1011, "send failed"); } catch {}
        }
      }
      return Response.json({ ok: true, version, sent, dropped });
    }

    if (url.pathname === "/status") {
      const latest = await this.state.storage.get("latest");
      const peaks = await this.state.storage.list({ prefix: "peak:" });
      const days = [];
      for (const [key, value] of peaks) days.push({ day: key.slice("peak:".length), peak: value });
      days.sort((a, b) => (a.day < b.day ? 1 : -1));
      return Response.json({
        ok: true,
        connected: this.state.getWebSockets().length,
        latest: latest || null,
        days: days.slice(0, 60)
      });
    }

    return new Response("Unknown broadcast route.", { status: 404 });
  }

  /*
    Clients are not asked to say anything, and nothing they could say would be trusted:
    the only direction that carries meaning here is server to client. A "ping" that gets
    this far came from a runtime without auto-response, so it is answered by hand; the
    timestamp is what /status uses to tell a live socket from one behind a proxy that
    stopped forwarding without closing.
  */
  webSocketMessage(ws, message) {
    if (typeof message !== "string") return;
    if (message === "ping") {
      try { ws.send("pong"); } catch {}
    }
    try { ws.serializeAttachment({ seen: Date.now() }); } catch {}
  }

  webSocketClose(ws) {
    try { ws.close(); } catch {}
  }

  webSocketError(ws) {
    try { ws.close(1011, "socket error"); } catch {}
  }

  /** Reaps sockets a proxy dropped without telling either end. */
  async alarm() {
    const now = Date.now();
    for (const socket of this.state.getWebSockets()) {
      let seen = 0;
      try { seen = socket.deserializeAttachment()?.seen || 0; } catch {}
      if (seen && now - seen > STALE_MS) {
        try { socket.close(1001, "stale"); } catch {}
      }
    }
  }
}
