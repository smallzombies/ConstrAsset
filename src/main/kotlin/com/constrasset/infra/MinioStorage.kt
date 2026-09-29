package com.constrasset.infra

import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.http.Method
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * MinIO（S3兼容）封装（Critical Note #3：下载/预览一律 Presigned URL，禁止 Ktor 代理文件流）。
 */
class MinioStorage(
    private val endpoint: String,
    accessKey: String,
    secretKey: String,
    val bucket: String,
) : AutoCloseable {

    private val client = MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .build()

    fun ensureBucket() {
        val exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
        }
    }

    /** 上传对象；调用方在协程 dispatcher.IO 中执行（Global Constraint：禁止阻塞线程） */
    fun putObject(key: String, data: ByteArray, contentType: String) {
        data.inputStream().use { ins ->
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .`object`(key)
                    .stream(ins, data.size.toLong(), -1)
                    .contentType(contentType)
                    .build(),
            )
        }
    }

    fun putObject(key: String, stream: InputStream, size: Long, contentType: String) {
        client.putObject(
            PutObjectArgs.builder()
                .bucket(bucket).`object`(key)
                .stream(stream, if (size >= 0) size else -1, if (size >= 0) -1 else 10L * 1024 * 1024)
                .contentType(contentType)
                .build(),
        )
    }

    fun getObject(key: String): ByteArray =
        client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(key).build()).readBytes()

    fun removeObject(key: String) {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build())
    }

    /**
     * 预签名 GET URL（默认有效期 1 小时）。
     * §9 文件下载 / 预览 / 打包下载均通过此方法返回临时URL。
     */
    fun presignedGetUrl(key: String, expiryMinutes: Long = 60): String =
        client.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .`object`(key)
                .expiry(expiryMinutes.toInt(), TimeUnit.MINUTES)
                .build(),
        )

    override fun close() { /* MinioClient 无需显式关闭 */ }
}

/** MinIO key 规划（§4.4 压缩包路径: projects/{id}/压缩包文件夹/pack_{timestamp}.zip） */
object ObjectKeys {
    fun raw(projectId: Long, fileId: Long, fileName: String) =
        "projects/$projectId/files/${fileId}_${fileName}"

    fun thumbnail(projectId: Long, fileId: Long) =
        "projects/$projectId/thumbnails/${fileId}.jpg"   // thumbnails/ 前缀（§4.3 步骤5）

    fun pack(projectId: Long, timestampMs: Long) =
        "projects/$projectId/压缩包文件夹/pack_$timestampMs.zip"
}
