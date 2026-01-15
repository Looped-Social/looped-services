package com.looped.messaging;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MessageMediaService {
    private static final Set<String> ALLOWED_IMAGE = Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");
    private static final Set<String> ALLOWED_VIDEO = Set.of("video/mp4");

    private final String bucket;
    private final long maxImageBytes;
    private final long maxVideoBytes;
    private final Duration presignPutTtl;
    private final Duration presignGetTtl;
    private final S3Presigner presigner;

    public MessageMediaService(
            @Value("${s3.messaging.bucket:}") String bucket,
            @Value("${media.maxImageSize}") DataSize maxImageSize,
            @Value("${media.maxVideoSize}") DataSize maxVideoSize,
            @Value("${messageMedia.presignPutTtl:PT15M}") Duration presignPutTtl,
            @Value("${messageMedia.presignGetTtl:PT5M}") Duration presignGetTtl,
            @Qualifier("messageMediaS3Presigner") S3Presigner presigner
    ) {
        this.bucket = bucket;
        this.maxImageBytes = maxImageSize.toBytes();
        this.maxVideoBytes = maxVideoSize.toBytes();
        this.presignPutTtl = presignPutTtl;
        this.presignGetTtl = presignGetTtl;
        this.presigner = presigner;
    }

    public PresignPutResult presignPut(String contentType, long sizeBytes) {
        if (bucket == null || bucket.isBlank()) {
            return PresignPutResult.badRequest("message_media_bucket_not_configured");
        }
        if (contentType == null || contentType.isBlank()) {
            return PresignPutResult.badRequest("content_type_required");
        }
        boolean isImage = ALLOWED_IMAGE.contains(contentType);
        boolean isVideo = ALLOWED_VIDEO.contains(contentType);
        if (!isImage && !isVideo) {
            return PresignPutResult.badRequest("unsupported_content_type");
        }
        long max = isImage ? maxImageBytes : maxVideoBytes;
        if (sizeBytes <= 0 || sizeBytes > max) {
            return PresignPutResult.badRequest("size_exceeds_limit");
        }

        String key = "dm/original/" + UUID.randomUUID();
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                .signatureDuration(presignPutTtl)
                .putObjectRequest(put)
                .build();
        var presigned = presigner.presignPutObject(presignReq);
        return PresignPutResult.ok(key, presigned.url().toString(), Map.of("Content-Type", contentType));
    }

    public PresignGetResult presignGet(String key) {
        if (bucket == null || bucket.isBlank()) {
            return PresignGetResult.badRequest("message_media_bucket_not_configured");
        }
        if (key == null || key.isBlank()) {
            return PresignGetResult.badRequest("key_required");
        }
        if (!key.startsWith("dm/")) {
            return PresignGetResult.badRequest("invalid_key_prefix");
        }
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                .signatureDuration(presignGetTtl)
                .getObjectRequest(get)
                .build();
        var presigned = presigner.presignGetObject(presignReq);
        return PresignGetResult.ok(key, presigned.url().toString(), (int) presignGetTtl.toSeconds());
    }

    public record PresignPutResult(Status status, String key, String uploadUrl, Map<String, String> headers, String error) {
        static PresignPutResult ok(String key, String uploadUrl, Map<String, String> headers) {
            return new PresignPutResult(Status.OK, key, uploadUrl, headers, null);
        }

        static PresignPutResult badRequest(String err) {
            return new PresignPutResult(Status.BAD_REQUEST, null, null, Map.of(), err);
        }
    }

    public record PresignGetResult(Status status, String key, String downloadUrl, Integer expiresInSeconds, String error) {
        static PresignGetResult ok(String key, String downloadUrl, Integer expiresInSeconds) {
            return new PresignGetResult(Status.OK, key, downloadUrl, expiresInSeconds, null);
        }

        static PresignGetResult badRequest(String err) {
            return new PresignGetResult(Status.BAD_REQUEST, null, null, null, err);
        }
    }

    public enum Status { OK, BAD_REQUEST }
}
