package com.colink.android.ui.castboard.bridge

import androidx.webkit.JavaScriptReplyProxy
import com.colink.android.ui.castboard.CastBoardPluginItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val PLUGIN_BASE_URL = "https://appassets.androidplatform.net/plugins"

class PluginBridge {
    fun dispatchPlugins(replyProxy: JavaScriptReplyProxy, plugins: List<CastBoardPluginItem>) {
        if (plugins.isEmpty()) return
        val message = buildJsonObject {
            put("channel", "castboard")
            put("type", "plugins.register")
            put("payload", buildJsonObject {
                put("plugins", buildJsonArray {
                    plugins.forEach { plugin ->
                        add(buildJsonObject {
                            put("manifest", plugin.rawManifest)
                            put("baseUrl", "$PLUGIN_BASE_URL/${plugin.directoryName}/")
                        })
                    }
                })
            })
        }
        replyProxy.postMessage(Json.encodeToString(message))
    }
}
