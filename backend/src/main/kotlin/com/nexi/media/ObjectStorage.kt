package com.nexi.media

import com.nexi.config.StorageConfig
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration

interface ObjectStorage : AutoCloseable {
    fun createUploadUrl(key: String, mimeType: String, expiresIn: Duration): String
    fun inspect(key: String): StoredObjectInfo
    fun createDownloadUrl(key: String, expiresIn: Duration): String
    fun delete(key: String)
    override fun close() = Unit
}

class S3ObjectStorage(private val config: StorageConfig) : ObjectStorage {
    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(config.accessKey, config.secretKey)
    )
    private val s3Configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build()
    private val client = S3Client.builder()
        .endpointOverride(URI.create(config.endpoint))
        .region(Region.of(config.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(s3Configuration)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build()
    private val presigner = S3Presigner.builder()
        .endpointOverride(URI.create(config.publicEndpoint))
        .region(Region.of(config.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(s3Configuration)
        .build()

    init {
        ensureBucketExists()
    }

    override fun createUploadUrl(key: String, mimeType: String, expiresIn: Duration): String {
        val objectRequest = PutObjectRequest.builder()
            .bucket(config.bucket)
            .key(key)
            .contentType(mimeType)
            .build()
        return presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(expiresIn)
                .putObjectRequest(objectRequest)
                .build()
        ).url().toString()
    }

    override fun inspect(key: String): StoredObjectInfo {
        val head = client.headObject(HeadObjectRequest.builder().bucket(config.bucket).key(key).build())
        val bytes: ResponseBytes<*> = client.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(config.bucket)
                .key(key)
                .range("bytes=0-31")
                .build()
        )
        return StoredObjectInfo(head.contentLength(), head.contentType(), bytes.asByteArray())
    }

    override fun createDownloadUrl(key: String, expiresIn: Duration): String {
        val objectRequest = GetObjectRequest.builder()
            .bucket(config.bucket)
            .key(key)
            .responseContentDisposition("inline")
            .build()
        return presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(expiresIn)
                .getObjectRequest(objectRequest)
                .build()
        ).url().toString()
    }

    override fun delete(key: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(key).build())
    }

    override fun close() {
        presigner.close()
        client.close()
    }

    private fun ensureBucketExists() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(config.bucket).build())
        } catch (_: NoSuchBucketException) {
            client.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build())
        } catch (exception: software.amazon.awssdk.services.s3.model.S3Exception) {
            if (exception.statusCode() == 404) {
                client.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build())
            } else {
                throw exception
            }
        }
    }
}
