package com.ereader.shared

import io.ktor.http.Url

object ModelDownloadUrl {
    private val dnsLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    private val hex = "0123456789abcdefABCDEF"

    fun isAllowed(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true) || url.any {
                it.isWhitespace() || it.code <= 0x20 || it.code in 0x7f..0x9f || it == '\\' || it == '#'
            }) return false
        if (url.indices.any { url[it] == '%' &&
                (it + 2 >= url.length || url[it + 1] !in hex || url[it + 2] !in hex)
            }) return false
        val authority = url.substring(8).takeWhile { it != '/' && it != '?' }
        if (authority.isEmpty() || '@' in authority) return false
        val parsed = try { Url(url) } catch (_: Exception) { return false }
        if (parsed.protocol.name != "https" || parsed.user != null || parsed.password != null ||
            !validHost(parsed.host)) return false
        val port = authority.removePrefix(parsed.host)
        return port.isEmpty() || (port.startsWith(':') && port.length > 1 &&
            port.drop(1).all { it in '0'..'9' } && port.drop(1).toIntOrNull() in 1..65535)
    }

    private fun validHost(host: String): Boolean {
        if (host.startsWith('[') && host.endsWith(']')) return validIpv6(host.substring(1, host.lastIndex))
        val name = host.removeSuffix(".")
        if (name.length !in 1..253) return false
        val labels = name.split('.')
        if (labels.size == 4 && labels.all { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }) {
            return validIpv4(name)
        }
        return labels.all { dnsLabel.matches(it) }
    }

    private fun validIpv4(host: String): Boolean = host.split('.').let { parts ->
        parts.size == 4 && parts.all {
            it.length in 1..3 && it.all { char -> char in '0'..'9' } &&
                (it.length == 1 || it.first() != '0') && it.toIntOrNull() in 0..255
        }
    }

    private fun validIpv6(host: String): Boolean {
        val halves = host.split("::")
        if (halves.size > 2) return false
        val groups = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
        var count = 0
        groups.forEachIndexed { index, group ->
            if ('.' in group) {
                if (index != groups.lastIndex || !host.endsWith(group) || !validIpv4(group)) return false
                count += 2
            } else {
                if (group.length !in 1..4 || group.any { it !in hex }) return false
                count++
            }
        }
        return if (halves.size == 2) count < 8 else count == 8
    }
}
