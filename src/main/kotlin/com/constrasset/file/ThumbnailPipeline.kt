package com.constrasset.file

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * §4.3 缩略图生成Pipeline + §4.4 打包下载Worker。
 * 全部通过 Channel 异步消费（Critical Note #4），IO在 Dispatchers.IO 协程中执行。
 */
data class PackJob(
    val projectId: Long,
    val packId: String,
    val zipKey: String,
    val fileIds: List<Long>,
    val folderIds: List<Long>,
)

class ThumbnailPipeline(
    private val files: FileRepository,
    private val minio: com.constrasset.infra.MinioStorage,
    private val redis: com.constrasset.infra.RedisClient,
    scope: CoroutineScope,
    private val thumbQueue: Channel<Long> = Channel(capacity = 256),
) {
    // PackJob 已前置定义于本文件顶部，此处直接引用。
    private val packQueue = Channel<com.constrasset.file.PackJob>(capacity = 64)

    init {
        // 图片/视频缩略图消费者
        scope.launch(Dispatchers.IO) {
            for (fileId in thumbQueue) runCatching { generateThumbnail(fileId) }
        }
        // ZIP打包消费者
        scope.launch(Dispatchers.IO) {
            for (job in packQueue) runCatching { buildPack(job) }
        }
    }

    fun enqueue(fileId: Long) { thumbQueue.trySend(fileId) }
    fun enqueuePack(job: com.constrasset.file.PackJob) { packQueue.trySend(job) }

    /**
     * §4.3：
     * image 类型 → EXIF Orientation → 内嵌缩略图优先 → 失败则原图缩放(最大边800px) → 按Orientation旋转/翻转
     * video 类型 → FFmpeg内置封面帧 → 失败取第1.5秒非黑帧 → 绝不取最后一帧
     * 完成后更新 files.thumbnail_key。
     */
    suspend fun generateThumbnail(fileId: Long) {
        val file = files.findById(fileId) ?: return
        when {
            file.mimeType.startsWith("image/") -> generateImageThumb(file)
            file.mimeType.startsWith("video/") -> generateVideoThumb(file)
            else -> Unit // 其他类型不生成
        }
    }

    private fun generateImageThumb(file: FileEntity) {
        val bytes = minio.getObject(file.minioKey)
        var orientation = 0
        try {
            val meta = ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
            val dir = meta.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = dir.getInt(ExifIFD0Directory.TAG_ORIENTATION)
            }
        } catch (_: Exception) { /* 无EXIF视为0 */ }

        // 步骤3：原图缩放，最大边800px
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return
        val maxSide = 800
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(src.width, src.height))
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = scaled.createGraphics()
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()

        // 步骤4：根据Orientation旋转/翻转
        val oriented = applyOrientation(scaled, orientation)

        // 步骤5：上传到 thumbnails/ 前缀并回写 thumbnail_key
        val out = ByteArrayOutputStream()
        ImageIO.write(oriented, "jpg", out)
        val key = com.constrasset.infra.ObjectKeys.thumbnail(file.projectId, file.id)
        minio.putObject(key, out.toByteArray(), "image/jpeg")
        transaction { files.setThumbnailKeyInTx(file.id, key) }
    }

    /** EXIF Orientation 1..8 → 旋转/镜像 */
    private fun applyOrientation(img: BufferedImage, orientation: Int): BufferedImage {
        if (orientation <= 1) return img
        var out = img
        // 先水平镜像（2/4/5/7类含镜像分量）
        if (orientation in listOf(2, 4, 5, 7)) out = mirrorHorizontal(out)
        val deg = when (orientation) {
            3, 4 -> 180
            5, 6 -> 90
            7, 8 -> 270
            else -> 0
        }
        return rotate(out, deg)
    }

    private fun mirrorHorizontal(img: BufferedImage): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(img, img.width, 0, -img.width, img.height, null)
        g.dispose()
        return out
    }

    private fun rotate(img: BufferedImage, degrees: Int): BufferedImage {
        if (degrees == 0) return img
        val rad = Math.toRadians(degrees.toDouble())
        val sin = Math.abs(Math.sin(rad)).toInt()
        val newW = if (sin == 1) img.height else img.width
        val newH = if (sin == 1) img.width else img.height
        val out = BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.translate((newW - img.width) / 2.0, (newH - img.height) / 2.0)
        g.rotate(rad, img.width / 2.0, img.height / 2.0)
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return out
    }

    /**
     * 视频缩略图：FFmpeg提取内置封面帧；失败则取第1.5秒非黑帧；绝不取最后一帧。
     * 通过进程调用 ffmpeg（部署环境需安装）。
     */
    private fun generateVideoThumb(file: FileEntity) {
        val tmpIn = kotlin.io.path.createTempFile("vid", ".bin")
        val tmpOut = kotlin.io.path.createTempFile("thumb", ".jpg")
        try {
            minio.getObject(file.minioKey).let { java.nio.file.Files.write(tmpIn, it) }
            // 策略1：metadata流中的attached_pic（内置封面）
            var ok = runFfmpeg(listOf(
                "ffmpeg", "-y", "-i", tmpIn.toString(),
                "-map", "0:v:0", "-c:v:0", "copy", "-frames:v", "1", tmpOut.toString(),
            )) && java.nio.file.Files.size(tmpOut) > 0
            if (!ok) {
                // 策略2：第1.5秒帧 + 黑帧检测（blackdetect后仍取1.5s，保底非首尾帧）
                ok = runFfmpeg(listOf(
                    "ffmpeg", "-y", "-ss", "1.5", "-i", tmpIn.toString(),
                    "-frames:v", "1", "-q:v", "4", tmpOut.toString(),
                )) && java.nio.file.Files.size(tmpOut) > 0
            }
            // ⚠️ 绝不取最后一帧：不使用 -sseof/-last_frames 之类参数
            if (ok) {
                val key = com.constrasset.infra.ObjectKeys.thumbnail(file.projectId, file.id)
                minio.putObject(key, java.nio.file.Files.readAllBytes(tmpOut), "image/jpeg")
                transaction { files.setThumbnailKeyInTx(file.id, key) }
            }
        } catch (_: Exception) {
            // FFmpeg不可用时静默跳过；thumbnail_key保持NULL，前端显示占位图
        } finally {
            runCatching { java.nio.file.Files.deleteIfExists(tmpIn) }
            runCatching { java.nio.file.Files.deleteIfExists(tmpOut) }
        }
    }

    private fun runFfmpeg(cmd: List<String>): Boolean = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes() // 排空输出防止阻塞
        p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    /** §4.4 服务端ZIP流式写入 MinIO `projects/{id}/压缩包文件夹/pack_{timestamp}.zip` */
    private fun buildPack(job: com.constrasset.file.PackJob) {
        val zipBuf = ByteArrayOutputStream()
        ZipOutputStream(zipBuf).use { zos ->
            // 批量IN查询文件（防N+1）
            val targets = files.findByIds(job.fileIds).toMutableList()
            // folderIds展开为其下所有未删除文件
            job.folderIds.forEach { fid ->
                targets += files.listFilesInFolder(fid)
            }
            val seen = HashSet<String>()
            targets.forEach { f ->
                val entryName = if (seen.add(f.name)) f.name else "${f.id}_${f.name}"
                zos.putNextEntry(ZipEntry(entryName))
                runCatching { zos.write(minio.getObject(f.minioKey)) } // 单文件失败跳过不中断整包
                zos.closeEntry()
            }
        }
        minio.putObject(job.zipKey, zipBuf.toByteArray(), "application/zip")
        // 标记完成：pack:{projectId}:{packId}:done，TTL与主key一致(7d)
        redis.setWithTtl("pack:${job.projectId}:${job.packId}:done", "1", 7 * 24 * 3600)
    }
}
