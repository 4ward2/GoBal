package com.danielealbano.androidremotecontrolmcp.services.remote

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Which routes are safe to use. A relay route is end-to-end encrypted whatever carries it. A
 * direct route is only as private as the network under it: without TLS everything, the password
 * included, crosses that network in the clear, so it is allowed inside private networks only.
 * Pure.
 */
object RoutePolicy {
    const val NEEDS_TLS = "这个地址在公网上，必须用加密传输（打开「加密传输」，或改用扫码配对）。不加密的连接只能用于局域网和组网内的地址。"
    private const val OCTETS = 4

    /** Unique-local and link-local prefixes. */
    private val PRIVATE_V6 = listOf("fc", "fd", "fe80")
    private const val OCTET_MAX = 255
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** Why [route] must not be used, or null when it may. */
    fun refusal(route: Route): String? {
        val safe = route.kind == Route.RELAY || route.tls || isPrivate(hostOf(route.endpoint))
        return if (safe) null else NEEDS_TLS
    }

    fun hostOf(endpoint: String): String = endpoint.substringBeforeLast(':').removePrefix("[").removeSuffix("]")

    /**
     * True for an address that only exists inside a private network: loopback, a LAN, link-local,
     * or the ranges overlay networks hand out (Tailscale's 100.64/10, IPv6 unique-local). A name
     * is never private: where it points is decided by whoever answers DNS.
     */
    fun isPrivate(host: String): Boolean {
        val octets = IPV4.matchEntire(host)?.groupValues?.drop(1)
        val v4 = octets?.map { it.toInt() }
        val lower = host.lowercase()
        return when {
            v4 != null -> v4.size == OCTETS && v4.all { it <= OCTET_MAX } && privateV4(v4[0], v4[1])
            ':' in lower -> lower == "::1" || PRIVATE_V6.any { lower.startsWith(it) }
            else -> lower == "localhost"
        }
    }

    /** The address blocks of RFC 1918 (private), 6598 (carrier and overlay networks) and 3927 (link-local). */
    private fun privateV4(
        a: Int,
        b: Int,
    ): Boolean {
        val lan = a == NET_10 || (a == NET_172 && b in NET_172_FROM..NET_172_TO) || (a == NET_192 && b == NET_192_168)
        val overlay = a == NET_100 && b in NET_100_FROM..NET_100_TO
        return lan || overlay || a == LOOPBACK || (a == LINK_LOCAL && b == LINK_LOCAL_B)
    }

    private const val NET_10 = 10
    private const val LOOPBACK = 127
    private const val NET_172 = 172
    private const val NET_172_FROM = 16
    private const val NET_172_TO = 31
    private const val NET_192 = 192
    private const val NET_192_168 = 168
    private const val NET_100 = 100
    private const val NET_100_FROM = 64
    private const val NET_100_TO = 127
    private const val LINK_LOCAL = 169
    private const val LINK_LOCAL_B = 254
}

/** What a failed attempt to reach a computer looked like. */
data class LinkFailure(
    val route: Route,
    val error: Throwable? = null,
    /** The HTTP status the computer (or relay) answered the upgrade with, 0 for none. */
    val httpCode: Int = 0,
    /** True when some VPN app holds the phone's traffic right now. */
    val vpnActive: Boolean = false,
)

/** Says, in words a person can act on, why a computer could not be reached. Pure. */
object LinkDiagnosis {
    const val WRONG_PASSWORD = "口令不对，或这台电脑不再认这个口令。"
    const val NOT_PAIRED = "这台电脑不认这次配对了，请在电脑上重新配对。"
    const val NO_SUCH_NAME = "找不到这个地址（域名解析失败）。检查地址有没有写错、手机能不能上网。"
    const val CERTIFICATE = "加密连接的证书有问题：地址和证书对不上，或证书不被信任。"
    const val VPN_CONFLICT = "连不上。手机上正开着一个 VPN 类应用，而这个地址在组网内网里：代理软件和组网软件不能同时开。可以关掉其中一个，或改用扫码中继、frp 这类公网地址。"
    const val CLOSED_PORT = "连不上这个地址的端口。电脑可能关机或睡眠了，Paseo 可能没在运行，或防火墙挡住了。"
    const val NO_ANSWER = "电脑没有回应。它可能关机或睡眠了，或 Paseo 没在运行。"
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403

    /** A refusal is final (the pairing no longer holds); anything else is worth trying again. */
    fun isRefusal(failure: LinkFailure): Boolean = failure.httpCode == UNAUTHORIZED || failure.httpCode == FORBIDDEN

    fun explain(failure: LinkFailure): String {
        val direct = failure.route.kind == Route.DIRECT
        val inside = direct && RoutePolicy.isPrivate(RoutePolicy.hostOf(failure.route.endpoint))
        val unreachable = failure.error is ConnectException || failure.error is SocketTimeoutException
        return when {
            isRefusal(failure) -> if (direct) WRONG_PASSWORD else NOT_PAIRED
            failure.error is UnknownHostException -> NO_SUCH_NAME
            failure.error is SSLException -> CERTIFICATE
            unreachable && inside && failure.vpnActive -> VPN_CONFLICT
            unreachable -> CLOSED_PORT
            else -> NO_ANSWER
        }
    }
}
