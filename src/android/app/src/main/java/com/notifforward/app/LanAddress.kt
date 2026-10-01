package com.notifforward.app

import java.net.URI

object LanAddress {
    fun normalize(text: String): String {
        val value = text.trim()
        require(value.isNotEmpty()) { "请输入电脑当前局域网 IP 或 HTTPS 地址" }
        val uri = runCatching { URI(if (value.contains("://")) value else "https://$value") }
            .getOrElse { throw IllegalArgumentException("局域网地址格式错误") }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null &&
            uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/") && (uri.port == -1 || uri.port in 1..65535)) {
            "请使用电脑 HTTPS 地址，不含账号、路径或查询参数"
        }
        val host = uri.host.removeSurrounding("[", "]")
        require(!host.equals("localhost", true) && !host.startsWith("127.") && host != "::1" && host != "0.0.0.0" && host != "::") {
            "请输入电脑在局域网中的地址，不能使用手机本机或未指定地址"
        }
        return URI("https", null, uri.host, if (uri.port == -1) 47721 else uri.port, null, null, null).toASCIIString()
    }
}
