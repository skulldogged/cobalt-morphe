package dev.skulldogged.cobalt.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val EXTENSION_CLASS =
    "Ldev/skulldogged/cobalt/extension/CobaltDownloader;"

private val cobaltDownloadsManifestPatch = resourcePatch {
    compatibleWith(YOUTUBE_COMPATIBILITY)

    execute {
        document("res/values/strings.xml").use { document ->
            val resources = document.documentElement

            fun addString(name: String, value: String) {
                val existing = document.getElementsByTagName("string")
                for (index in 0 until existing.length) {
                    val element = existing.item(index) as Element
                    if (element.getAttribute("name") == name) return
                }
                document.createElement("string").apply {
                    setAttribute("name", name)
                    appendChild(document.createTextNode(value))
                    resources.appendChild(this)
                }
            }

            fun addStringArray(name: String, values: List<String>) {
                val existing = document.getElementsByTagName("string-array")
                for (index in 0 until existing.length) {
                    val element = existing.item(index) as Element
                    if (element.getAttribute("name") == name) return
                }
                document.createElement("string-array").apply {
                    setAttribute("name", name)
                    values.forEach { value ->
                        appendChild(document.createElement("item").apply {
                            appendChild(document.createTextNode(value))
                        })
                    }
                    resources.appendChild(this)
                }
            }

            addString("cobalt_settings_title", "Cobalt downloads")
            addString("cobalt_settings_summary", "Download YouTube videos through your cobalt instance")
            addString("cobalt_settings_connection", "Connection")
            addString("cobalt_settings_download", "Download preferences")
            addString("cobalt_enabled_title", "Use cobalt downloads")
            addString("cobalt_enabled_summary", "Override YouTube's download button; turn off to use YouTube's original action")
            addString("cobalt_api_url_title", "API endpoint")
            addString("cobalt_api_url_summary", "HTTPS cobalt processing endpoint, including any path such as /api/")
            addString("cobalt_api_key_title", "API key")
            addString("cobalt_api_key_summary", "Optional Api-Key authentication token")
            addString("cobalt_video_quality_title", "Video quality")
            addString("cobalt_video_codec_title", "Preferred video codec")
            addString("cobalt_filename_style_title", "Filename style")
            addString("cobalt_better_audio_title", "Prefer higher-quality YouTube audio")
            addString("cobalt_better_audio_summary", "Use a higher-quality audio stream when cobalt can provide one")
            addString("cobalt_selected_value", "%s")

            addStringArray("cobalt_video_quality_entries", listOf(
                "Maximum available", "8K", "4K", "1440p", "1080p",
                "720p", "480p", "360p", "240p", "144p"
            ))
            addStringArray("cobalt_video_quality_values", listOf(
                "max", "4320", "2160", "1440", "1080",
                "720", "480", "360", "240", "144"
            ))
            addStringArray("cobalt_video_codec_entries", listOf("AV1", "VP9"))
            addStringArray("cobalt_video_codec_values", listOf("av1", "vp9"))
            addStringArray("cobalt_filename_style_entries", listOf(
                "Pretty", "Classic", "Basic", "Nerdy"
            ))
            addStringArray("cobalt_filename_style_values", listOf(
                "pretty", "classic", "basic", "nerdy"
            ))
        }

        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0)
            val application = document.getElementsByTagName("application").item(0)

            fun addPermissionIfMissing(permission: String) {
                val permissions = document.getElementsByTagName("uses-permission")
                for (index in 0 until permissions.length) {
                    val element = permissions.item(index) as Element
                    if (element.getAttribute("android:name") == permission) return
                }

                val element = document.createElement("uses-permission")
                element.setAttribute("android:name", permission)
                manifest.insertBefore(element, application)
            }

            addPermissionIfMissing("android.permission.INTERNET")
            addPermissionIfMissing("android.permission.FOREGROUND_SERVICE")
            addPermissionIfMissing("android.permission.FOREGROUND_SERVICE_DATA_SYNC")

            val serviceName =
                "dev.skulldogged.cobalt.extension.CobaltDownloadService"
            val services = document.getElementsByTagName("service")
            for (index in 0 until services.length) {
                val element = services.item(index) as Element
                if (element.getAttribute("android:name") == serviceName) return@use
            }

            val service = document.createElement("service")
            service.setAttribute("android:name", serviceName)
            service.setAttribute("android:exported", "false")
            service.setAttribute("android:foregroundServiceType", "dataSync")
            application.appendChild(service)
        }
    }

    finalize {
        fun Node.addPreference(tag: String, attributes: Map<String, String>, block: (Element.() -> Unit)? = null): Element {
            val element = ownerDocument.createElement(tag)
            attributes.forEach(element::setAttribute)
            block?.invoke(element)
            appendChild(element)
            return element
        }

        fun addCobaltSettings(path: String, icon: String?) {
            if (!get(path).exists()) return

            document(path).use { document ->
                val root = document.documentElement
                val existing = document.getElementsByTagName("PreferenceScreen")
                for (index in 0 until existing.length) {
                    val element = existing.item(index) as Element
                    if (element.getAttribute("android:key") == "cobalt_settings_screen") return@use
                }

                val screenAttributes = linkedMapOf(
                    "android:key" to "cobalt_settings_screen",
                    "android:title" to "@string/cobalt_settings_title",
                    "android:summary" to "@string/cobalt_settings_summary",
                )
                if (icon != null) {
                    screenAttributes["android:icon"] = icon
                    screenAttributes["android:layout"] = "@layout/preference_with_icon"
                }

                root.addPreference("PreferenceScreen", screenAttributes) {
                    addPreference("PreferenceCategory", mapOf(
                        "android:title" to "@string/cobalt_settings_connection"
                    )) {
                        addPreference("SwitchPreference", mapOf(
                            "android:key" to "cobalt_enabled",
                            "android:title" to "@string/cobalt_enabled_title",
                            "android:summary" to "@string/cobalt_enabled_summary",
                            "android:defaultValue" to "true",
                        ))
                        addPreference("EditTextPreference", mapOf(
                            "android:key" to "cobalt_api_url",
                            "android:title" to "@string/cobalt_api_url_title",
                            "android:summary" to "@string/cobalt_api_url_summary",
                            "android:dialogTitle" to "@string/cobalt_api_url_title",
                            "android:defaultValue" to "https://cobalt.skulldogged.dev/api/",
                            "android:inputType" to "textUri",
                            "android:dependency" to "cobalt_enabled",
                        ))
                        addPreference("EditTextPreference", mapOf(
                            "android:key" to "cobalt_api_key",
                            "android:title" to "@string/cobalt_api_key_title",
                            "android:summary" to "@string/cobalt_api_key_summary",
                            "android:dialogTitle" to "@string/cobalt_api_key_title",
                            "android:defaultValue" to "",
                            "android:inputType" to "textPassword",
                            "android:dependency" to "cobalt_enabled",
                        ))
                    }

                    addPreference("PreferenceCategory", mapOf(
                        "android:title" to "@string/cobalt_settings_download"
                    )) {
                        addPreference("ListPreference", mapOf(
                            "android:key" to "cobalt_video_quality",
                            "android:title" to "@string/cobalt_video_quality_title",
                            "android:summary" to "@string/cobalt_selected_value",
                            "android:dialogTitle" to "@string/cobalt_video_quality_title",
                            "android:entries" to "@array/cobalt_video_quality_entries",
                            "android:entryValues" to "@array/cobalt_video_quality_values",
                            "android:defaultValue" to "1440",
                            "android:dependency" to "cobalt_enabled",
                        ))
                        addPreference("ListPreference", mapOf(
                            "android:key" to "cobalt_video_codec",
                            "android:title" to "@string/cobalt_video_codec_title",
                            "android:summary" to "@string/cobalt_selected_value",
                            "android:dialogTitle" to "@string/cobalt_video_codec_title",
                            "android:entries" to "@array/cobalt_video_codec_entries",
                            "android:entryValues" to "@array/cobalt_video_codec_values",
                            "android:defaultValue" to "av1",
                            "android:dependency" to "cobalt_enabled",
                        ))
                        addPreference("ListPreference", mapOf(
                            "android:key" to "cobalt_filename_style",
                            "android:title" to "@string/cobalt_filename_style_title",
                            "android:summary" to "@string/cobalt_selected_value",
                            "android:dialogTitle" to "@string/cobalt_filename_style_title",
                            "android:entries" to "@array/cobalt_filename_style_entries",
                            "android:entryValues" to "@array/cobalt_filename_style_values",
                            "android:defaultValue" to "pretty",
                            "android:dependency" to "cobalt_enabled",
                        ))
                        addPreference("SwitchPreference", mapOf(
                            "android:key" to "cobalt_better_youtube_audio",
                            "android:title" to "@string/cobalt_better_audio_title",
                            "android:summary" to "@string/cobalt_better_audio_summary",
                            "android:defaultValue" to "false",
                            "android:dependency" to "cobalt_enabled",
                        ))
                    }
                }
            }
        }

        addCobaltSettings("res/xml/morphe_prefs.xml", null)
        addCobaltSettings(
            "res/xml/morphe_prefs_icons.xml",
            "@drawable/morphe_settings_screen_12_video"
        )
        addCobaltSettings(
            "res/xml/morphe_prefs_icons_bold.xml",
            "@drawable/morphe_settings_screen_12_video_bold"
        )
    }
}

@Suppress("unused")
val cobaltDownloadsPatch = bytecodePatch(
    name = "Cobalt downloads",
    description = "Replaces YouTube's native download action with a direct cobalt download.",
    default = false,
) {
    dependsOn(cobaltDownloadsManifestPatch)
    compatibleWith(YOUTUBE_COMPATIBILITY)

    extendWith("extensions/cobalt-downloads.mpe")

    execute {
        YouTubeApplicationInitFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize(Ljava/lang/Object;)V",
        )

        OfflineVideoEndpointFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->onDownloadButtonClick(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :show_native_downloader
                return-void
                :show_native_downloader
                nop
            """.trimIndent(),
        )
    }
}
