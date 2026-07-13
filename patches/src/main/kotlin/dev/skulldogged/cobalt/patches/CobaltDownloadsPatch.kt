package dev.skulldogged.cobalt.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val EXTENSION_CLASS =
    "Ldev/skulldogged/cobalt/extension/CobaltDownloader;"

private val cobaltDownloadsManifestPatch = resourcePatch {
    compatibleWith(YOUTUBE_COMPATIBILITY)

    execute {
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
