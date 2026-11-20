package org.bobrdobr.vpn.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Route definition for routing table configuration.
 */
data class CidrRoute(
    val address: String,
    val prefix: Int
) {
    override fun toString(): String = "$address/$prefix"

    companion object {
        val DEFAULT_IPV4 = CidrRoute("0.0.0.0", 0)
        val DEFAULT_IPV6 = CidrRoute("::", 0)
    }
}

/**
 * Encapsulates full configuration required to establish an Android VpnService TUN interface
 * and configure the underlying native protocol engine (Rust or Go).
 */
data class TunnelConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val serverName: String = "БобрДобр Сервер",
    val protocol: VpnProtocol = VpnProtocol.AMNEZIA_WG,
    val mtu: Int = DEFAULT_MTU,
    val ipv4Address: String = "10.8.0.2",
    val ipv4Prefix: Int = 32,
    val ipv6Address: String? = null,
    val ipv6Prefix: Int = 128,
    val dnsServers: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val routes: List<CidrRoute> = listOf(CidrRoute.DEFAULT_IPV4),
    val bypassSubnets: List<CidrRoute> = emptyList(),
    val allowedApplications: Set<String> = emptySet(),
    val disallowedApplications: Set<String> = emptySet(),
    val serverAddress: String = "",
    val serverPort: Int = 443,
    val rawConfigJson: String = "{}"
) {
    companion object {
        const val DEFAULT_MTU = 1360
        const val MIN_MTU = 1280
        const val MAX_MTU = 1500

        fun fromJson(jsonStr: String): TunnelConfig {
            val obj = JSONObject(jsonStr)

            val dnsList = mutableListOf<String>()
            val dnsArray = obj.optJSONArray("dnsServers")
            if (dnsArray != null) {
                for (i in 0 until dnsArray.length()) {
                    dnsList.add(dnsArray.getString(i))
                }
            } else {
                dnsList.addAll(listOf("1.1.1.1", "1.0.0.1"))
            }

            val routeList = mutableListOf<CidrRoute>()
            val routesArray = obj.optJSONArray("routes")
            if (routesArray != null) {
                for (i in 0 until routesArray.length()) {
                    val rObj = routesArray.getJSONObject(i)
                    routeList.add(CidrRoute(rObj.getString("address"), rObj.getInt("prefix")))
                }
            } else {
                routeList.add(CidrRoute.DEFAULT_IPV4)
            }

            val bypassList = mutableListOf<CidrRoute>()
            val bypassArray = obj.optJSONArray("bypassSubnets")
            if (bypassArray != null) {
                for (i in 0 until bypassArray.length()) {
                    val bObj = bypassArray.getJSONObject(i)
                    bypassList.add(CidrRoute(bObj.getString("address"), bObj.getInt("prefix")))
                }
            }

            val allowedSet = mutableSetOf<String>()
            val allowedArray = obj.optJSONArray("allowedApplications")
            if (allowedArray != null) {
                for (i in 0 until allowedArray.length()) {
                    allowedSet.add(allowedArray.getString(i))
                }
            }

            val disallowedSet = mutableSetOf<String>()
            val disallowedArray = obj.optJSONArray("disallowedApplications")
            if (disallowedArray != null) {
                for (i in 0 until disallowedArray.length()) {
                    disallowedSet.add(disallowedArray.getString(i))
                }
            }

            return TunnelConfig(
                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                serverName = obj.optString("serverName", "БобрДобр Сервер"),
                protocol = VpnProtocol.fromString(obj.optString("protocol", "AMNEZIA_WG")),
                mtu = obj.optInt("mtu", DEFAULT_MTU),
                ipv4Address = obj.optString("ipv4Address", "10.8.0.2"),
                ipv4Prefix = obj.optInt("ipv4Prefix", 32),
                ipv6Address = if (obj.has("ipv6Address") && !obj.isNull("ipv6Address")) obj.getString("ipv6Address") else null,
                ipv6Prefix = obj.optInt("ipv6Prefix", 128),
                dnsServers = dnsList,
                routes = routeList,
                bypassSubnets = bypassList,
                allowedApplications = allowedSet,
                disallowedApplications = disallowedSet,
                serverAddress = obj.optString("serverAddress", ""),
                serverPort = obj.optInt("serverPort", 443),
                rawConfigJson = obj.optString("rawConfigJson", "{}")
            )
        }
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("serverName", serverName)
        obj.put("protocol", protocol.name)
        obj.put("mtu", mtu)
        obj.put("ipv4Address", ipv4Address)
        obj.put("ipv4Prefix", ipv4Prefix)
        obj.put("ipv6Address", ipv6Address ?: JSONObject.NULL)
        obj.put("ipv6Prefix", ipv6Prefix)

        val dnsArray = JSONArray()
        dnsServers.forEach { dnsArray.put(it) }
        obj.put("dnsServers", dnsArray)

        val routesArray = JSONArray()
        routes.forEach {
            val rObj = JSONObject()
            rObj.put("address", it.address)
            rObj.put("prefix", it.prefix)
            routesArray.put(rObj)
        }
        obj.put("routes", routesArray)

        val bypassArray = JSONArray()
        bypassSubnets.forEach {
            val bObj = JSONObject()
            bObj.put("address", it.address)
            bObj.put("prefix", it.prefix)
            bypassArray.put(bObj)
        }
        obj.put("bypassSubnets", bypassArray)

        val allowedArray = JSONArray()
        allowedApplications.forEach { allowedArray.put(it) }
        obj.put("allowedApplications", allowedArray)

        val disallowedArray = JSONArray()
        disallowedApplications.forEach { disallowedArray.put(it) }
        obj.put("disallowedApplications", disallowedArray)

        obj.put("serverAddress", serverAddress)
        obj.put("serverPort", serverPort)
        obj.put("rawConfigJson", rawConfigJson)
        return obj.toString()
    }
}
