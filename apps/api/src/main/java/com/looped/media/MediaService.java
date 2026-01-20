package com.looped.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import org.springframework.util.unit.DataSize;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaService {
    private final String bucket;
    private final Region region;
    private final S3Presigner presigner;
    private final long maxImageBytes;
    private final long maxVideoBytes;
    private final String callbackSecret;

    public MediaService(
            @Value("${s3.bucket}") String bucket,
            @Value("${s3.region}") String region,
            @Value("${media.maxImageSize}") DataSize maxImageSize,
            @Value("${media.maxVideoSize}") DataSize maxVideoSize,
            @Value("${media.callbackSecret:}") String callbackSecret,
            S3Presigner presigner
    ) {
        this.bucket = bucket;
        this.region = Region.of(region);
        this.maxImageBytes = maxImageSize.toBytes();
        this.maxVideoBytes = maxVideoSize.toBytes();
        this.callbackSecret = callbackSecret;
        this.presigner = presigner;
    }

    private static final Set<String> ALLOWED_IMAGE = Set.of("image/jpeg", "image/png", "image/webp");
    // iOS often captures in HEIF/HEIC; allow for MVP (iOS-first). Note: some non-iOS clients/browsers may not render these.
    private static final Set<String> ALLOWED_IMAGE_HEIF = Set.of("image/heic", "image/heif");
    private static final Set<String> ALLOWED_VIDEO = Set.of("video/mp4");

    public PresignResult presign(String contentType, long sizeBytes) {
        if (contentType == null || contentType.isBlank()) {
            return PresignResult.badRequest("content_type_required");
        }
        boolean isImage = ALLOWED_IMAGE.contains(contentType) || ALLOWED_IMAGE_HEIF.contains(contentType);
        boolean isVideo = ALLOWED_VIDEO.contains(contentType);
        if (!isImage && !isVideo) {
            return PresignResult.badRequest("unsupported_content_type");
        }
        long max = isImage ? maxImageBytes : maxVideoBytes;
        if (sizeBytes <= 0 || sizeBytes > max) {
            return PresignResult.badRequest("size_exceeds_limit");
        }

        String key = "media/original/" + UUID.randomUUID();
        return presignKey(contentType, key);
    }

    public PresignResult presignImage(String contentType, long sizeBytes, String prefix) {
        if (contentType == null || contentType.isBlank()) {
            return PresignResult.badRequest("content_type_required");
        }
        if (!ALLOWED_IMAGE.contains(contentType)) {
            return PresignResult.badRequest("unsupported_content_type");
        }
        if (sizeBytes <= 0 || sizeBytes > maxImageBytes) {
            return PresignResult.badRequest("size_exceeds_limit");
        }
        String resolvedPrefix = normalizePrefix(prefix);
        String key = resolvedPrefix + UUID.randomUUID();
        return presignKey(contentType, key);
    }

    private String normalizePrefix(String prefix) {
        String resolved = prefix == null ? "" : prefix.trim();
        if (resolved.startsWith("/")) {
            resolved = resolved.substring(1);
        }
        if (!resolved.isEmpty() && !resolved.endsWith("/")) {
            resolved = resolved + "/";
        }
        return resolved;
    }

    private PresignResult presignKey(String contentType, String key) {
        // Build presign URL
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(put)
                .build();
        var presigned = presigner.presignPutObject(presignReq);
        String url = presigned.url().toString();

        String signature = null;
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            signature = hmacSha256Base64(callbackSecret, key);
        }

        return PresignResult.ok(key, url, Map.of("Content-Type", contentType), signature);
    }

    public String presignedGetUrl(String key, Duration duration) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                .signatureDuration(duration == null ? Duration.ofMinutes(5) : duration)
                .getObjectRequest(get)
                .build();
        return presigner.presignGetObject(presignReq).url().toString();
    }

    public static String hmacSha256Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record PresignResult(Status status, String key, String uploadUrl, Map<String, String> headers, String callbackSignature, String error) {
        static PresignResult ok(String key, String url, Map<String, String> headers, String sig) { return new PresignResult(Status.OK, key, url, headers, sig, null); }
        static PresignResult badRequest(String err) { return new PresignResult(Status.BAD_REQUEST, null, null, Map.of(), null, err); }
    }
    public enum Status { OK, BAD_REQUEST }
}
