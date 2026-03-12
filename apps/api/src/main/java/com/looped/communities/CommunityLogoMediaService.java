package com.looped.communities;

import com.looped.media.MediaService;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.xml.sax.InputSource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;

@Service
public class CommunityLogoMediaService {
    private static final String SVG_MIME_TYPE = "image/svg+xml";
    private static final Set<String> ALLOWED_UPLOAD_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            SVG_MIME_TYPE
    );

    private final S3Client s3;
    private final String bucket;
    private final long maxImageBytes;
    private final int normalizedMaxImageDimension;

    public CommunityLogoMediaService(@Qualifier("mediaS3Client") S3Client s3,
                                     @Value("${s3.bucket}") String bucket,
                                     @Value("${media.maxImageSize}") DataSize maxImageSize,
                                     @Value("${media.normalizedMaxImageDimension:2048}") int normalizedMaxImageDimension) {
        this.s3 = s3;
        this.bucket = bucket;
        this.maxImageBytes = maxImageSize.toBytes();
        this.normalizedMaxImageDimension = Math.max(1, normalizedMaxImageDimension);
    }

    public Set<String> allowedUploadMimeTypes() {
        return ALLOWED_UPLOAD_MIME_TYPES;
    }

    public boolean isSvgMimeType(String mimeType) {
        return SVG_MIME_TYPE.equals(MediaService.normalizeMimeType(mimeType));
    }

    public ProcessedImage rasterizeSvgInPlace(String key, String mimeType) {
        if (!isSvgMimeType(mimeType)) {
            throw new InvalidAssetException("unsupported_image_format");
        }
        if (key == null || key.isBlank()) {
            throw new InvalidAssetException("invalid_key");
        }

        byte[] sourceBytes = readObjectBytes(key);
        BufferedImage rasterized = decodeSvg(sourceBytes);
        byte[] pngBytes = encodePng(rasterized);
        writeObjectBytes(key, pngBytes);
        return new ProcessedImage("image/png", rasterized.getWidth(), rasterized.getHeight());
    }

    private byte[] readObjectBytes(String key) {
        try {
            var head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            long contentLength = head.contentLength();
            if (contentLength <= 0) {
                throw new InvalidAssetException("image_empty");
            }
            if (contentLength > maxImageBytes) {
                throw new InvalidAssetException("image_size_exceeds_limit");
            }
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new InvalidAssetException("image_not_found");
            }
            throw new MediaUnavailableException("image_storage_unavailable", e);
        }

        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build())) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                throw new InvalidAssetException("image_empty");
            }
            return bytes;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new InvalidAssetException("image_not_found");
            }
            throw new MediaUnavailableException("image_storage_unavailable", e);
        } catch (IOException e) {
            throw new InvalidAssetException("image_decode_failed");
        }
    }

    private BufferedImage decodeSvg(byte[] bytes) {
        try {
            SvgRasterSize rasterSize = svgRasterSize(bytes);
            BufferedImageTranscoder transcoder = new BufferedImageTranscoder();
            transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, (float) rasterSize.width());
            transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, (float) rasterSize.height());
            transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(bytes)), null);
            BufferedImage image = transcoder.image();
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new InvalidAssetException("invalid_image");
            }
            return image;
        } catch (TranscoderException e) {
            throw new InvalidAssetException("invalid_image");
        }
    }

    private SvgRasterSize svgRasterSize(byte[] bytes) {
        double[] intrinsicSize = readSvgIntrinsicSize(bytes);
        double intrinsicWidth = intrinsicSize[0];
        double intrinsicHeight = intrinsicSize[1];
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            return new SvgRasterSize(normalizedMaxImageDimension, normalizedMaxImageDimension);
        }

        double scale = Math.min(
                (double) normalizedMaxImageDimension / intrinsicWidth,
                (double) normalizedMaxImageDimension / intrinsicHeight
        );
        int width = Math.max(1, (int) Math.round(intrinsicWidth * scale));
        int height = Math.max(1, (int) Math.round(intrinsicHeight * scale));
        return new SvgRasterSize(width, height);
    }

    private double[] readSvgIntrinsicSize(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);

            var builder = factory.newDocumentBuilder();
            var document = builder.parse(new InputSource(new ByteArrayInputStream(bytes)));
            var root = document.getDocumentElement();
            if (root == null) return new double[]{0d, 0d};

            Double width = parseSvgDimension(root.getAttribute("width"));
            Double height = parseSvgDimension(root.getAttribute("height"));
            if (width != null && width > 0 && height != null && height > 0) {
                return new double[]{width, height};
            }

            String viewBox = root.getAttribute("viewBox");
            if (viewBox != null && !viewBox.isBlank()) {
                String[] parts = viewBox.trim().split("[,\\s]+");
                if (parts.length == 4) {
                    double viewBoxWidth = Double.parseDouble(parts[2]);
                    double viewBoxHeight = Double.parseDouble(parts[3]);
                    if (viewBoxWidth > 0 && viewBoxHeight > 0) {
                        return new double[]{viewBoxWidth, viewBoxHeight};
                    }
                }
            }
        } catch (Exception ignored) {
            return new double[]{0d, 0d};
        }
        return new double[]{0d, 0d};
    }

    private Double parseSvgDimension(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank() || value.contains("%")) return null;
        int end = 0;
        while (end < value.length()) {
            char c = value.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+') {
                end++;
            } else {
                break;
            }
        }
        if (end == 0) return null;
        try {
            return Double.parseDouble(value.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new InvalidAssetException("image_encode_failed");
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0) {
                throw new InvalidAssetException("image_encode_failed");
            }
            if (bytes.length > maxImageBytes) {
                throw new InvalidAssetException("image_size_exceeds_limit");
            }
            return bytes;
        } catch (IOException e) {
            throw new InvalidAssetException("image_encode_failed");
        }
    }

    private void writeObjectBytes(String key, byte[] bytes) {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("image/png")
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            throw new MediaUnavailableException("image_storage_unavailable", e);
        }
    }

    public record ProcessedImage(String mimeType, int width, int height) {}

    private record SvgRasterSize(int width, int height) {}

    public static final class InvalidAssetException extends RuntimeException {
        public InvalidAssetException(String message) {
            super(message);
        }
    }

    public static class MediaUnavailableException extends RuntimeException {
        public MediaUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class BufferedImageTranscoder extends ImageTranscoder {
        private BufferedImage image;

        @Override
        public BufferedImage createImage(int width, int height) {
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public void writeImage(BufferedImage image, TranscoderOutput output) {
            this.image = image;
        }

        public BufferedImage image() {
            return image;
        }
    }
}
