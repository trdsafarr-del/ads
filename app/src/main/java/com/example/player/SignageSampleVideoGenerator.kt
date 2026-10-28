package com.example.player

import android.content.Context
import java.io.File
import java.io.FileInputStream

/**
 * Utility for validating real MP4/WebM video files in the local offline signage cache
 * and cleaning up any legacy synthetic files. Does NOT use MediaMuxer or MediaCodec,
 * preventing native MPEG4Writer track errors and ensuring only real videos are played.
 */
object SignageSampleVideoGenerator {

    fun getVideoCacheDir(context: Context): File {
        return File(context.filesDir, "signage_videos").apply {
            if (!exists()) mkdirs()
            // Ensure any legacy synthetic fallback file is removed
            val legacyFallback = File(this, "fallback_master_signage.mp4")
            if (legacyFallback.exists()) {
                try {
                    legacyFallback.delete()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Checks that [file] is a valid, non-empty video file (MP4 / MOV / WebM) on disk.
     */
    fun isValidMp4File(file: File?): Boolean {
        if (file == null || !file.exists() || file.length() < 1024L) return false
        if (file.name == "fallback_master_signage.mp4" || file.name.endsWith(".gen.tmp")) {
            return false
        }
        return try {
            val header = ByteArray(12)
            val read = FileInputStream(file).use { it.read(header) }
            if (read < 8) return false
            // Standard ISO Base Media File Format (MP4 / MOV) has 'ftyp' at bytes 4..7
            val isMp4 = header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
            // WebM / Matroska starts with 0x1A 0x45 0xDF 0xA3
            val isWebm = header[0] == 0x1A.toByte() &&
                header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() &&
                header[3] == 0xA3.toByte()
            isMp4 || isWebm
        } catch (_: Exception) {
            false
        }
    }
}
