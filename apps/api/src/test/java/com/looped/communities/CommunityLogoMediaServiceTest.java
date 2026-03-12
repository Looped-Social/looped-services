package com.looped.communities;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.S3Client;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityLogoMediaServiceTest {
    private final S3Client s3 = Mockito.mock(S3Client.class);

    @Test
    void allowed_upload_types_include_svg() {
        CommunityLogoMediaService service = new CommunityLogoMediaService(
                s3,
                "bucket",
                DataSize.ofMegabytes(10),
                2048
        );

        assertThat(service.allowedUploadMimeTypes()).contains("image/svg+xml");
    }

    @Test
    void rasterize_svg_in_place_converts_to_png_with_bounded_dimensions() throws Exception {
        byte[] svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200">
                  <rect width="400" height="200" fill="#0057ff"/>
                  <text x="200" y="110" font-size="48" text-anchor="middle" fill="#ffffff">Looped</text>
                </svg>
                """.getBytes(StandardCharsets.UTF_8);
        String key = "media/communities/logos/test-svg";
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) svg.length)
                .build());
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) svg.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(svg))
        ));

        AtomicReference<byte[]> written = new AtomicReference<>();
        doAnswer(invocation -> {
            RequestBody requestBody = invocation.getArgument(1);
            try (var in = requestBody.contentStreamProvider().newStream()) {
                written.set(in.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        CommunityLogoMediaService service = new CommunityLogoMediaService(
                s3,
                "bucket",
                DataSize.ofMegabytes(10),
                2048
        );

        CommunityLogoMediaService.ProcessedImage processed = service.rasterizeSvgInPlace(key, "image/svg+xml");

        assertThat(processed.mimeType()).isEqualTo("image/png");
        assertThat(processed.width()).isEqualTo(2048);
        assertThat(processed.height()).isEqualTo(1024);
        assertThat(written.get()).isNotNull();
        verify(s3).putObject(
                argThat((PutObjectRequest request) ->
                        key.equals(request.key()) && "image/png".equals(request.contentType())),
                any(RequestBody.class)
        );
        var image = ImageIO.read(new ByteArrayInputStream(written.get()));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(2048);
        assertThat(image.getHeight()).isEqualTo(1024);
    }
}
