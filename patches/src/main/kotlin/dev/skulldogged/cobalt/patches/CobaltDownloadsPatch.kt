package dev.skulldogged.cobalt.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS =
    "Ldev/skulldogged/cobalt/extension/CobaltDownloader;"

@Suppress("unused")
val cobaltDownloadsPatch = bytecodePatch(
    name = "Cobalt downloads",
    description = "Replaces YouTube's native download action with a direct cobalt download.",
    default = false,
) {
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
