package com.example.data.media

import android.content.Context
import com.example.data.catalog.WallpaperCatalog
import com.example.data.model.OfflinePackSizeOption
import com.example.data.model.Wallpaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class OfflinePackProgress(
    val packOption: OfflinePackSizeOption = OfflinePackSizeOption.STUDIO,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val bytesWritten: Long = 0L,
    val currentTitle: String = "",
    val isDownloading: Boolean = false,
    val isPaused: Boolean = false,
    val statusLabel: String = "NOT_DOWNLOADED"
) {
    val fraction: Float
        get() = if (totalCount <= 0) 0f else (completedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)

    val isComplete: Boolean
        get() = totalCount > 0 && completedCount >= totalCount && !isDownloading
}

/**
 * Manages optional curated offline wallpaper pack downloads in app-private storage (`filesDir/offline_packs`).
 * Prevents duplicates, reports transparent byte/item progress, and supports Pause, Resume, Cancel & Delete.
 */
object OfflinePackDownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _progress = MutableStateFlow(OfflinePackProgress())
    val progress: StateFlow<OfflinePackProgress> = _progress.asStateFlow()

    fun offlineDir(context: Context): File =
        File(context.applicationContext.filesDir, "offline_packs").apply { if (!exists()) mkdirs() }

    fun getDownloadedFile(context: Context, wallpaperId: String): File? {
        val f = File(offlineDir(context), "$wallpaperId.jpg")
        return if (f.exists() && f.length() > 128L) f else null
    }

    fun selectCandidateWallpapers(
        option: OfflinePackSizeOption,
        alreadyDownloadedIds: List<String>
    ): List<Wallpaper> {
        val all = WallpaperCatalog.allWallpapers
        val byPopularity = all.sortedByDescending { it.popularityScore }
        val chosen = byPopularity.take(option.count)
        // Return full target list; downloaded items already on disk are skipped without redownloading.
        return chosen.sortedBy { if (it.id in alreadyDownloadedIds) 0 else 1 }
    }

    fun syncPersistedState(
        context: Context,
        targetCount: Int,
        downloadedIds: List<String>,
        status: String
    ) {
        if (_progress.value.isDownloading) return
        val option = OfflinePackSizeOption.fromCount(targetCount)
        val validExisting = downloadedIds.filter { getDownloadedFile(context, it) != null }
        val bytes = offlineDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        _progress.value = OfflinePackProgress(
            packOption = option,
            totalCount = if (validExisting.isNotEmpty()) option.count else 0,
            completedCount = validExisting.size,
            bytesWritten = bytes,
            statusLabel = status
        )
    }

    fun startOrResumeDownload(
        context: Context,
        option: OfflinePackSizeOption,
        existingDownloadedIds: List<String>,
        onStatePersisted: (targetCount: Int, downloadedIds: List<String>, status: String) -> Unit
    ) {
        val appContext = context.applicationContext
        if (_progress.value.isDownloading && _progress.value.isPaused) {
            _progress.value = _progress.value.copy(isPaused = false, statusLabel = "DOWNLOADING")
            return
        }
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val dir = offlineDir(appContext)
            val candidates = selectCandidateWallpapers(option, existingDownloadedIds)
            val completedIds = LinkedHashSet<String>()
            existingDownloadedIds.forEach { id ->
                if (getDownloadedFile(appContext, id) != null) completedIds.add(id)
            }
            candidates.forEach { wp ->
                if (File(dir, "${wp.id}.jpg").let { it.exists() && it.length() > 128L }) {
                    completedIds.add(wp.id)
                }
            }

            var totalBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
            var failed = 0
            _progress.value = OfflinePackProgress(
                packOption = option,
                totalCount = candidates.size,
                completedCount = completedIds.size.coerceAtMost(candidates.size),
                failedCount = 0,
                bytesWritten = totalBytes,
                currentTitle = "Preparing offline pack...",
                isDownloading = true,
                isPaused = false,
                statusLabel = "DOWNLOADING"
            )
            onStatePersisted(option.count, completedIds.toList(), "DOWNLOADING")

            val client = BackdropImageLoader.createOkHttpClient(appContext)
            try {
                for (wp in candidates) {
                    while (_progress.value.isPaused) {
                        delay(200L)
                    }
                    val targetFile = File(dir, "${wp.id}.jpg")
                    if (targetFile.exists() && targetFile.length() > 128L) {
                        completedIds.add(wp.id)
                        _progress.value = _progress.value.copy(
                            completedCount = completedIds.size.coerceAtMost(candidates.size),
                            currentTitle = wp.title
                        )
                        continue
                    }

                    _progress.value = _progress.value.copy(currentTitle = wp.title)
                    val ok = runCatching {
                        val req = Request.Builder().url(wp.thumbnailUrl).build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use false
                            val body = resp.body ?: return@use false
                            val tempFile = File(dir, "${wp.id}.tmp")
                            FileOutputStream(tempFile).use { out ->
                                body.byteStream().copyTo(out)
                            }
                            if (tempFile.length() > 128L) {
                                tempFile.renameTo(targetFile)
                            } else {
                                tempFile.delete()
                                false
                            }
                        }
                    }.getOrDefault(false)

                    if (ok && targetFile.exists()) {
                        completedIds.add(wp.id)
                        totalBytes += targetFile.length()
                    } else {
                        failed++
                    }

                    val completedCount = completedIds.size.coerceAtMost(candidates.size)
                    _progress.value = _progress.value.copy(
                        completedCount = completedCount,
                        failedCount = failed,
                        bytesWritten = totalBytes
                    )
                    if (completedIds.size % 5 == 0 || completedCount == candidates.size) {
                        onStatePersisted(option.count, completedIds.toList(), "DOWNLOADING")
                    }
                }

                val finalStatus = if (completedIds.size >= candidates.size) "COMPLETED" else "PARTIAL"
                _progress.value = _progress.value.copy(
                    isDownloading = false,
                    isPaused = false,
                    currentTitle = if (finalStatus == "COMPLETED") "Offline Pack Ready" else "Pack Ready (${completedIds.size}/${candidates.size})",
                    statusLabel = finalStatus
                )
                onStatePersisted(option.count, completedIds.toList(), finalStatus)
            } catch (_: CancellationException) {
                _progress.value = _progress.value.copy(
                    isDownloading = false,
                    isPaused = false,
                    currentTitle = "Paused at ${completedIds.size}/${candidates.size}",
                    statusLabel = "PAUSED"
                )
                onStatePersisted(option.count, completedIds.toList(), "PAUSED")
            }
        }
    }

    fun pauseDownload() {
        if (_progress.value.isDownloading) {
            _progress.value = _progress.value.copy(isPaused = true, statusLabel = "PAUSED")
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _progress.value = _progress.value.copy(isDownloading = false, isPaused = false, statusLabel = "CANCELLED")
    }

    fun clearOfflinePack(
        context: Context,
        onStatePersisted: (targetCount: Int, downloadedIds: List<String>, status: String) -> Unit
    ) {
        downloadJob?.cancel()
        val dir = offlineDir(context)
        dir.listFiles()?.forEach { it.delete() }
        _progress.value = OfflinePackProgress(statusLabel = "NOT_DOWNLOADED")
        onStatePersisted(OfflinePackSizeOption.STUDIO.count, emptyList(), "NOT_DOWNLOADED")
    }
}
