package dev.wyz.clientcore.world

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Asks the router to forward a port, so a hosted world is reachable without anyone editing
 * router settings by hand.
 *
 * Plain SSDP + SOAP over the JDK: adding a UPnP library for three requests is not worth the
 * dependency. Everything here blocks, so callers must stay off the render/tick threads.
 *
 * The diagnosis matters as much as the mapping. A gateway can happily accept a mapping that
 * cannot possibly work - behind a second router the port opens on a private address, and on
 * a CGNAT connection no forwarding is possible at all. Both are reported as distinct results
 * instead of a silent success that leaves friends unable to connect.
 */
object UpnpPortMapper {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val DISCOVER_TIMEOUT_MS = 3500
    private const val HTTP_TIMEOUT_MS = 5000
    /** Renewed while hosting; a lease means a crash cannot leave the port open forever. */
    private const val LEASE_SECONDS = 3600

    private val WAN_SERVICES = listOf(
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1"
    )

    enum class Result {
        /** Port is open and the router holds a real public address. */
        MAPPED,
        /** Mapping worked, but the router sits behind another router - not reachable yet. */
        DOUBLE_NAT,
        /** The ISP uses carrier-grade NAT; no port forwarding is possible. */
        CGNAT,
        /** No UPnP gateway answered, or it refused. */
        UNAVAILABLE
    }

    /**
     * [gatewayFound] separates "no router answered" from "the router answered and said no".
     * Without it the UI ends up telling people to switch UPnP on while quoting an error
     * that only a working UPnP gateway could have produced.
     */
    data class Outcome(
        val result: Result,
        val externalIp: String = "",
        val message: String = "",
        val gatewayFound: Boolean = false
    )

    private data class Gateway(val controlUrl: String, val serviceType: String)

    fun open(port: Int): Outcome {
        val gateway = discover()
            ?: return Outcome(Result.UNAVAILABLE, message = "No router answered. UPnP is probably switched off.")

        val externalIp = externalIp(gateway)
        if (externalIp.isEmpty()) {
            return Outcome(Result.UNAVAILABLE, message = "The router did not report an external address.", gatewayFound = true)
        }
        if (isCarrierGrade(externalIp)) {
            return Outcome(
                Result.CGNAT, externalIp,
                "Your internet provider uses shared addresses (CGNAT), so no port can be opened.", gatewayFound = true
            )
        }

        val localIp = localAddress()
            ?: return Outcome(Result.UNAVAILABLE, message = "Could not work out this machine's address.", gatewayFound = true)

        val body = buildString {
            append("<NewRemoteHost></NewRemoteHost>")
            append("<NewExternalPort>$port</NewExternalPort>")
            append("<NewProtocol>TCP</NewProtocol>")
            append("<NewInternalPort>$port</NewInternalPort>")
            append("<NewInternalClient>$localIp</NewInternalClient>")
            append("<NewEnabled>1</NewEnabled>")
            append("<NewPortMappingDescription>River hosted world</NewPortMappingDescription>")
            append("<NewLeaseDuration>$LEASE_SECONDS</NewLeaseDuration>")
        }
        val response = soap(gateway, "AddPortMapping", body)
            ?: return Outcome(Result.UNAVAILABLE, externalIp, "The router refused to open the port.", gatewayFound = true)
        if (response.first !in 200..299) {
            val detail = Regex("<errorDescription>([^<]*)</errorDescription>")
                .find(response.second)?.groupValues?.get(1)
            return Outcome(Result.UNAVAILABLE, externalIp, detail ?: "The router refused to open the port.", gatewayFound = true)
        }

        // A private "external" address means another router sits in front of this one, so
        // the port is open on an address the internet cannot reach.
        if (isPrivate(externalIp)) {
            return Outcome(
                Result.DOUBLE_NAT, externalIp,
                "Your router is behind another router. Put the first one in bridge mode, or point its DMZ at $externalIp.", gatewayFound = true
            )
        }

        return Outcome(Result.MAPPED, externalIp, gatewayFound = true)
    }

    fun close(port: Int) {
        val gateway = discover() ?: return
        soap(
            gateway, "DeletePortMapping",
            "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>"
        )
    }

    // ------------------------------------------------------------------ discovery

    private fun discover(): Gateway? {
        val location = searchForGateway() ?: return null
        val description = httpGet(location) ?: return null
        val serviceType = WAN_SERVICES.firstOrNull { description.contains(it) } ?: return null

        // The controlURL belonging to this service is the first one after its declaration.
        val tail = description.substring(description.indexOf(serviceType))
        val controlPath = Regex("<controlURL>([^<]+)</controlURL>").find(tail)?.groupValues?.get(1) ?: return null
        val base = URL(location)
        val controlUrl = URL(URL("${base.protocol}://${base.host}:${base.port}"), controlPath).toString()
        return Gateway(controlUrl, serviceType)
    }

    /**
     * SSDP discovery, made resilient to lost UDP packets.
     *
     * The old version sent a single M-SEARCH burst and treated the first receive timeout
     * as "no router" - so one dropped reply (common on Wi-Fi) produced a false "UPnP is
     * off" even when the router was perfectly reachable. This re-sends across a few short
     * rounds and keeps listening until the overall deadline, so a single loss no longer
     * looks like a missing gateway.
     */
    private fun searchForGateway(): String? {
        val services = listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1") + WAN_SERVICES
        DatagramSocket().use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = 700
            val target = InetAddress.getByName(SSDP_ADDRESS)
            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + DISCOVER_TIMEOUT_MS

            while (System.currentTimeMillis() < deadline) {
                for (service in services) {
                    val request = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $service\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
                    runCatching { socket.send(DatagramPacket(request, request.size, target, SSDP_PORT)) }
                }

                // Drain replies for this round; a timeout just means "try another burst",
                // not "give up".
                val roundEnd = System.currentTimeMillis() + 1000
                while (System.currentTimeMillis() < roundEnd) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    } catch (_: Throwable) {
                        return null
                    }
                    val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    Regex("(?i)LOCATION:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun externalIp(gateway: Gateway): String {
        val response = soap(gateway, "GetExternalIPAddress", "") ?: return ""
        return Regex("<NewExternalIPAddress>([^<]*)</NewExternalIPAddress>")
            .find(response.second)?.groupValues?.get(1).orEmpty()
    }

    // ------------------------------------------------------------------ transport

    private fun soap(gateway: Gateway, action: String, body: String): Pair<Int, String>? {
        val envelope = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${gateway.serviceType}\">$body</u:$action></s:Body></s:Envelope>"

        val connection = (URL(gateway.controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPAction", "\"${gateway.serviceType}#$action\"")
        }
        return try {
            connection.outputStream.use { it.write(envelope.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            // Faults carry their reason in the error stream, and that reason is what makes
            // a refusal explainable rather than just "it didn't work".
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
            code to text
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    // ------------------------------------------------------------------ addresses

    /** The LAN address the router should forward to. */
    private fun localAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 && it.hostAddress != null }
                ?.hostAddress
        }.getOrNull()
    }

    private fun isPrivate(ip: String): Boolean =
        ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip)

    /** 100.64.0.0/10 - the range providers use when they cannot give you a real address. */
    private fun isCarrierGrade(ip: String): Boolean =
        Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.").containsMatchIn(ip)
}
