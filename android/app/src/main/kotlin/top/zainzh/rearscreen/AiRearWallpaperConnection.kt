package top.zainzh.rearscreen

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/** Only the dedicated operations are exposed to the editor. */
internal class AiRearWallpaperConnection(private val remote: ITaskService) : AiRearWallpaperJournal.Store {
    override fun readArchive(id: String, destination: File) {
        write(id, false, destination)
    }

    fun write(id: String, thumbnail: Boolean, destination: File): String =
        ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use {
            remote.copyAiRearWallpaper(id, thumbnail, it)
        }

    fun inspectReplacement(id: String): String? {
        if (!TaskServiceManager.isConnectedServiceCurrent(remote)) throw IOException("SHIZUKU_DISCONNECTED")
        return remote.inspectAiRearWallpaperReplacement(id, null).takeIf(String::isNotEmpty)
    }

    override fun readOriginalForAdoption(id: String, ticket: String, destination: File) {
        if (!TaskServiceManager.isConnectedServiceCurrent(remote)) throw IOException("SHIZUKU_DISCONNECTED")
        ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use {
            if (remote.inspectAiRearWallpaperReplacement(id, it) != ticket)
                throw IOException("WALLPAPER_ADOPTION_CHANGED")
        }
    }

    override fun adoptArchive(id: String, ticket: String, original: File, replacement: File): String {
        if (!TaskServiceManager.isConnectedServiceCurrent(remote)) throw IOException("SHIZUKU_DISCONNECTED")
        return ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { expected ->
            ParcelFileDescriptor.open(replacement, ParcelFileDescriptor.MODE_READ_ONLY).use { candidate ->
                remote.adoptAiRearWallpaper(id, ticket, expected, candidate)
            }
        }
    }

    override fun replaceArchive(id: String, original: File, replacement: File) {
        if (!TaskServiceManager.isConnectedServiceCurrent(remote)) throw IOException("SHIZUKU_DISCONNECTED")
        ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { expected ->
            ParcelFileDescriptor.open(replacement, ParcelFileDescriptor.MODE_READ_ONLY).use { candidate ->
                remote.replaceAiRearWallpaper(id, expected, candidate)
            }
        }
    }
    override fun restoreArchive(id: String, applied: File, original: File): Boolean {
        if (!TaskServiceManager.isConnectedServiceCurrent(remote)) throw IOException("SHIZUKU_DISCONNECTED")
        return ParcelFileDescriptor.open(applied, ParcelFileDescriptor.MODE_READ_ONLY).use { installed ->
            ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { preimage ->
                remote.restoreAiRearWallpaper(id, installed, preimage)
            }
        }
    }
}
