package com.looped.media;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;

class MediaServiceTest {
    private final S3Presigner presigner = Mockito.mock(S3Presigner.class);

    @Test
    void rejects_unsupported_content_type() {
        MediaService svc = new MediaService("bucket", "us-east-1", 10_000L, 100_000L, null, presigner);
        var res = svc.presign("application/octet-stream", 100);
        assertThat(res.status()).isEqualTo(MediaService.Status.BAD_REQUEST);
        assertThat(res.error()).isEqualTo("unsupported_content_type");
    }

    @Test
    void rejects_oversize_image() {
        MediaService svc = new MediaService("bucket", "us-east-1", 10_000L, 100_000L, null, presigner);
        var res = svc.presign("image/jpeg", 20_000L);
        assertThat(res.status()).isEqualTo(MediaService.Status.BAD_REQUEST);
        assertThat(res.error()).isEqualTo("size_exceeds_limit");
    }
}

