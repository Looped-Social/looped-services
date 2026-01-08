package com.looped.verification;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Service
public class VerificationPrivateMediaService {
    private static final Set<String> ALLOWED_IMAGE = Set.of("image/jpeg", "image/png");

    private final String bucket;
    private final PhotoIdVerificationProperties props;
    private final S3Presigner presigner;
    private final S3Client s3;

    public VerificationPrivateMediaService(
            @Value("${s3.verification.bucket:}") String bucket,
            PhotoIdVerificationProperties props,
            @Qualifier("verificationS3Presigner") S3Presigner presigner,
            @Qualifier("verificationS3Client") S3Client s3
    ) {
        this.bucket = bucket;
        this.props = props;
        this.presigner = presigner;
        this.s3 = s3;
    }

    public PresignPutResult presignPutImage(String key, String contentType, long sizeBytes) {
        if (bucket == null || bucket.isBlank()) {
            return PresignPutResult.badRequest("verification_bucket_not_configured");
        }
        if (contentType == null || contentType.isBlank()) {
            return PresignPutResult.badRequest("content_type_required");
        }
        if (!ALLOWED_IMAGE.contains(contentType)) {
            return PresignPutResult.badRequest("unsupported_content_type");
        }
        if (sizeBytes <= 0 || sizeBytes > props.getMaxImageBytes()) {
            return PresignPutResult.badRequest("size_exceeds_limit");
        }
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                .signatureDuration(props.getPresignTtl())
                .putObjectRequest(put)
                .build();
        var presigned = presigner.presignPutObject(presignReq);
        return PresignPutResult.ok(key, presigned.url().toString(), Map.of("Content-Type", contentType));
    }

    public String presignGet(String key, Duration ttl) {
        if (bucket == null || bucket.isBlank()) return null;
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build();
        var presigned = presigner.presignGetObject(presignReq);
        return presigned.url().toString();
    }

    public boolean isConfigured() {
        return bucket != null && !bucket.isBlank();
    }

    public boolean deleteObjectQuietly(String key) {
        if (!isConfigured()) return false;
        if (key == null || key.isBlank()) return false;
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (RuntimeException ignored) {
            // best-effort cleanup; do not leak keys or PII into logs
            return false;
        }
    }

    public Duration adminDownloadTtl() {
        return props.getAdminDownloadTtl();
    }

    public record PresignPutResult(Status status, String key, String uploadUrl, Map<String, String> headers, String error) {
        static PresignPutResult ok(String key, String uploadUrl, Map<String, String> headers) {
            return new PresignPutResult(Status.OK, key, uploadUrl, headers, null);
        }

        static PresignPutResult badRequest(String err) {
            return new PresignPutResult(Status.BAD_REQUEST, null, null, Map.of(), err);
        }
    }

    public enum Status { OK, BAD_REQUEST }
}
