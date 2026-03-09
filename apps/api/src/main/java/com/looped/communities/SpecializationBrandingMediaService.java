package com.looped.communities;

import com.looped.media.MediaRepository;
import com.looped.media.MediaService;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import org.xml.sax.InputSource;

@Service
public class SpecializationBrandingMediaService {
    private static final Set<String> ALLOWED_SOURCE_MIME_TYPES = Set.of("image/png", "image/svg+xml");
    private static final String SOURCE_PREFIX = "media/specializations/source/";
    private static final String PROCESSED_PREFIX = "media/specializations/processed/";
    private static final int ICON_SIZE = 512;
    private static final int BANNER_WIDTH = 1200;
    private static final int BANNER_HEIGHT = 600;

    private final S3Client s3;
    private final String bucket;
    private final long maxSourceBytes;
    private final MediaRepository mediaRepository;

    public SpecializationBrandingMediaService(@Qualifier("mediaS3Client") S3Client s3,
                                              @Value("${s3.bucket}") String bucket,
                                              @Value("${media.maxImageSize}") DataSize maxImageSize,
                                              MediaRepository mediaRepository) {
        this.s3 = s3;
        this.bucket = bucket;
        this.maxSourceBytes = maxImageSize.toBytes();
        this.mediaRepository = mediaRepository;
    }

    public PresignSpec validatePresign(String slot, String contentType, long sizeBytes) {
        AssetSlot assetSlot = AssetSlot.from(slot);
        if (assetSlot == null) {
            throw new InvalidAssetException("invalid_slot", "slot must be icon or banner");
        }
        String normalizedMimeType = MediaService.normalizeMimeType(contentType);
        if (!ALLOWED_SOURCE_MIME_TYPES.contains(normalizedMimeType)) {
            throw new InvalidAssetException("unsupported_content_type", "contentType must be image/png or image/svg+xml");
        }
        if (sizeBytes <= 0 || sizeBytes > maxSourceBytes) {
            throw new InvalidAssetException("size_exceeds_limit", "file exceeds the allowed size");
        }
        return new PresignSpec(assetSlot, normalizedMimeType, SOURCE_PREFIX + assetSlot.pathSegment() + "/" + UUID.randomUUID());
    }

    public ProcessedAsset process(String slot, String sourceKey, String mimeType) {
        AssetSlot assetSlot = AssetSlot.from(slot);
        if (assetSlot == null) {
            throw new InvalidAssetException("invalid_slot", "slot must be icon or banner");
        }
        if (sourceKey == null || sourceKey.isBlank() || !sourceKey.startsWith(SOURCE_PREFIX + assetSlot.pathSegment() + "/")) {
            throw new InvalidAssetException("invalid_key", "key does not match the expected slot prefix");
        }
        String normalizedMimeType = MediaService.normalizeMimeType(mimeType);
        if (!ALLOWED_SOURCE_MIME_TYPES.contains(normalizedMimeType)) {
            throw new InvalidAssetException("unsupported_content_type", "mimeType must be image/png or image/svg+xml");
        }

        byte[] sourceBytes = readObjectBytes(sourceKey);
        BufferedImage sourceImage = "image/svg+xml".equals(normalizedMimeType)
                ? decodeSvg(sourceBytes, assetSlot)
                : decodePng(sourceBytes);
        BufferedImage processed = switch (assetSlot) {
            case ICON -> renderIcon(sourceImage);
            case BANNER -> renderBanner(sourceImage);
        };
        byte[] pngBytes = encodePng(processed);
        String processedKey = PROCESSED_PREFIX + assetSlot.pathSegment() + "/" + UUID.randomUUID() + ".png";
        writeObjectBytes(processedKey, pngBytes);
        Long mediaAssetId = mediaRepository.insert(null, processedKey, "image/png", processed.getWidth(), processed.getHeight(), null, null);
        if (mediaAssetId == null) {
            throw new MediaUnavailableException("media_persist_failed");
        }
        return new ProcessedAsset(assetSlot, sourceKey, processedKey, mediaAssetId, processed.getWidth(), processed.getHeight());
    }

    private byte[] readObjectBytes(String key) {
        try {
            var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            long contentLength = head.contentLength();
            if (contentLength <= 0) {
                throw new InvalidAssetException("image_empty", "uploaded file is empty");
            }
            if (contentLength > maxSourceBytes) {
                throw new InvalidAssetException("size_exceeds_limit", "file exceeds the allowed size");
            }
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new InvalidAssetException("image_not_found", "uploaded file was not found");
            }
            throw new MediaUnavailableException("image_storage_unavailable", e);
        }

        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build())) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                throw new InvalidAssetException("image_empty", "uploaded file is empty");
            }
            return bytes;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new InvalidAssetException("image_not_found", "uploaded file was not found");
            }
            throw new MediaUnavailableException("image_storage_unavailable", e);
        } catch (IOException e) {
            throw new InvalidAssetException("image_decode_failed", "uploaded file could not be read");
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

    private BufferedImage decodePng(byte[] bytes) {
        try (var in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new InvalidAssetException("invalid_image", "PNG file could not be decoded");
            }
            return image;
        } catch (IOException e) {
            throw new InvalidAssetException("invalid_image", "PNG file could not be decoded");
        }
    }

    private BufferedImage decodeSvg(byte[] bytes, AssetSlot assetSlot) {
        try {
            SvgRasterSize rasterSize = svgRasterSize(bytes, assetSlot);
            BufferedImageTranscoder transcoder = new BufferedImageTranscoder();
            transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, (float) rasterSize.width());
            transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, (float) rasterSize.height());
            transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(bytes)), null);
            BufferedImage image = transcoder.image();
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new InvalidAssetException("invalid_image", "SVG file could not be rasterized");
            }
            return image;
        } catch (TranscoderException e) {
            throw new InvalidAssetException("invalid_image", "SVG file could not be rasterized");
        }
    }

    private SvgRasterSize svgRasterSize(byte[] bytes, AssetSlot assetSlot) {
        double[] intrinsicSize = readSvgIntrinsicSize(bytes);
        double intrinsicWidth = intrinsicSize[0];
        double intrinsicHeight = intrinsicSize[1];
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            return switch (assetSlot) {
                case ICON -> new SvgRasterSize(ICON_SIZE, ICON_SIZE);
                case BANNER -> new SvgRasterSize(BANNER_WIDTH, BANNER_HEIGHT);
            };
        }

        double targetWidth = assetSlot == AssetSlot.ICON ? ICON_SIZE : BANNER_WIDTH;
        double targetHeight = assetSlot == AssetSlot.ICON ? ICON_SIZE : BANNER_HEIGHT;
        double scale = Math.min(targetWidth / intrinsicWidth, targetHeight / intrinsicHeight);
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
                    double vbWidth = Double.parseDouble(parts[2]);
                    double vbHeight = Double.parseDouble(parts[3]);
                    if (vbWidth > 0 && vbHeight > 0) {
                        return new double[]{vbWidth, vbHeight};
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

    private BufferedImage renderIcon(BufferedImage source) {
        BufferedImage canvas = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        drawContained(source, canvas);
        return canvas;
    }

    private BufferedImage renderBanner(BufferedImage source) {
        BufferedImage canvas = new BufferedImage(BANNER_WIDTH, BANNER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        drawContained(source, canvas);
        return canvas;
    }

    private void drawContained(BufferedImage source, BufferedImage canvas) {
        double scale = Math.min(
                (double) canvas.getWidth() / (double) source.getWidth(),
                (double) canvas.getHeight() / (double) source.getHeight()
        );
        int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int x = (canvas.getWidth() - drawWidth) / 2;
        int y = (canvas.getHeight() - drawHeight) / 2;
        Graphics2D graphics = canvas.createGraphics();
        try {
            applyQualityHints(graphics);
            graphics.drawImage(source, x, y, drawWidth, drawHeight, null);
        } finally {
            graphics.dispose();
        }
    }

    private void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) {
                throw new InvalidAssetException("image_encode_failed", "processed asset could not be encoded");
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) {
                throw new InvalidAssetException("image_encode_failed", "processed asset could not be encoded");
            }
            return bytes;
        } catch (IOException e) {
            throw new InvalidAssetException("image_encode_failed", "processed asset could not be encoded");
        }
    }

    public record PresignSpec(AssetSlot slot, String mimeType, String key) {}

    public record ProcessedAsset(AssetSlot slot,
                                 String sourceKey,
                                 String processedKey,
                                 Long mediaAssetId,
                                 int width,
                                 int height) {}

    private record SvgRasterSize(int width, int height) {}

    public enum AssetSlot {
        ICON("icon"),
        BANNER("banner");

        private final String pathSegment;

        AssetSlot(String pathSegment) {
            this.pathSegment = pathSegment;
        }

        public String pathSegment() {
            return pathSegment;
        }

        public static AssetSlot from(String raw) {
            if (raw == null) return null;
            String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (normalized) {
                case "icon" -> ICON;
                case "banner" -> BANNER;
                default -> null;
            };
        }
    }

    public static final class InvalidAssetException extends RuntimeException {
        private final String error;

        public InvalidAssetException(String error, String message) {
            super(message);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }

    public static class MediaUnavailableException extends RuntimeException {
        public MediaUnavailableException(String message) {
            super(message);
        }

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
