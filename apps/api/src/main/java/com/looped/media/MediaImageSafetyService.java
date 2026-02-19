package com.looped.media;

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

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

@Service
public class MediaImageSafetyService {
    private final S3Client s3;
    private final String bucket;
    private final boolean sanitizeImagesOnCallback;
    private final long maxImageBytes;
    private final int maxImageDimension;
    private final long maxImagePixels;
    private final int normalizedMaxImageDimension;
    private final float jpegQuality;

    public MediaImageSafetyService(@Qualifier("mediaS3Client") S3Client s3,
                                   @Value("${s3.bucket}") String bucket,
                                   @Value("${media.sanitizeImagesOnCallback:true}") boolean sanitizeImagesOnCallback,
                                   @Value("${media.maxImageSize}") DataSize maxImageSize,
                                   @Value("${media.maxImageDimension:8192}") int maxImageDimension,
                                   @Value("${media.maxImagePixels:40000000}") long maxImagePixels,
                                   @Value("${media.normalizedMaxImageDimension:2048}") int normalizedMaxImageDimension,
                                   @Value("${media.normalizedJpegQuality:0.82}") float jpegQuality) {
        this.s3 = s3;
        this.bucket = bucket;
        this.sanitizeImagesOnCallback = sanitizeImagesOnCallback;
        this.maxImageBytes = maxImageSize.toBytes();
        this.maxImageDimension = Math.max(1, maxImageDimension);
        this.maxImagePixels = Math.max(1, maxImagePixels);
        this.normalizedMaxImageDimension = Math.max(1, normalizedMaxImageDimension);
        this.jpegQuality = Math.max(0.1f, Math.min(1.0f, jpegQuality));
    }

    public ImageResult validateAndNormalizeUploadedImage(String key,
                                                         String declaredMimeType,
                                                         Integer declaredWidth,
                                                         Integer declaredHeight) {
        validateDeclaredDimensions(declaredMimeType, declaredWidth, declaredHeight);
        if (!sanitizeImagesOnCallback || key == null || key.isBlank()) {
            return new ImageResult(declaredMimeType, declaredWidth, declaredHeight);
        }
        try {
            return sanitizeImageInPlace(key, declaredMimeType);
        } catch (InvalidImageException e) {
            // Preserve compatibility for formats the runtime cannot decode (for example HEIC on some JVMs).
            if ("unsupported_image_format".equals(e.getMessage())) {
                return new ImageResult(declaredMimeType, declaredWidth, declaredHeight);
            }
            throw e;
        }
    }

    public boolean isUnsafeForClient(String mimeType, Integer width, Integer height) {
        if (!isImageMime(mimeType)) {
            return false;
        }
        if (width == null || height == null || width <= 0 || height <= 0) {
            return false;
        }
        return exceedsSafeBounds(width, height);
    }

    private void validateDeclaredDimensions(String mimeType, Integer width, Integer height) {
        if (!isImageMime(mimeType) || width == null || height == null) {
            return;
        }
        if (width <= 0 || height <= 0) {
            throw new InvalidImageException("image_dimensions_invalid");
        }
        if (exceedsSafeBounds(width, height)) {
            throw new InvalidImageException("image_dimensions_exceed_limit");
        }
    }

    private boolean exceedsSafeBounds(int width, int height) {
        if (width > maxImageDimension || height > maxImageDimension) {
            return true;
        }
        long pixels = (long) width * (long) height;
        return pixels > maxImagePixels;
    }

    private ImageResult sanitizeImageInPlace(String key, String declaredMimeType) {
        byte[] sourceBytes = readObjectBytes(key);
        if (sourceBytes.length == 0) {
            throw new InvalidImageException("image_empty");
        }
        if (sourceBytes.length > maxImageBytes) {
            throw new InvalidImageException("image_size_exceeds_limit");
        }

        DecodedImage decoded = decode(sourceBytes);
        if (exceedsSafeBounds(decoded.width(), decoded.height())) {
            throw new InvalidImageException("image_dimensions_exceed_limit");
        }

        BufferedImage normalized = toSrgb(decoded.image());
        BufferedImage bounded = resizeIfNeeded(normalized, normalizedMaxImageDimension);
        EncodedImage encoded = encode(bounded, declaredMimeType);
        if (encoded.bytes().length > maxImageBytes) {
            throw new InvalidImageException("image_size_exceeds_limit");
        }
        writeObjectBytes(key, encoded.mimeType(), encoded.bytes());
        return new ImageResult(encoded.mimeType(), bounded.getWidth(), bounded.getHeight());
    }

    private byte[] readObjectBytes(String key) {
        try {
            var head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            long contentLength = head.contentLength();
            if (contentLength <= 0) {
                throw new InvalidImageException("image_empty");
            }
            if (contentLength > maxImageBytes) {
                throw new InvalidImageException("image_size_exceeds_limit");
            }
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new InvalidImageException("image_not_found");
            }
            throw new MediaUnavailableException("image_storage_unavailable", e);
        }

        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build())) {
            return in.readAllBytes();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new InvalidImageException("image_not_found");
            }
            throw new MediaUnavailableException("image_storage_unavailable", e);
        } catch (IOException e) {
            throw new InvalidImageException("image_decode_failed");
        }
    }

    private void writeObjectBytes(String key, String mimeType, byte[] bytes) {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(mimeType)
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            throw new MediaUnavailableException("image_storage_unavailable", e);
        }
    }

    private DecodedImage decode(byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes);
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw new InvalidImageException("image_decode_failed");
            }
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new InvalidImageException("unsupported_image_format");
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new InvalidImageException("image_dimensions_invalid");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidImageException("image_decode_failed");
                }
                return new DecodedImage(image, width, height);
            } finally {
                reader.dispose();
            }
        } catch (InvalidImageException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidImageException("image_decode_failed");
        }
    }

    private BufferedImage toSrgb(BufferedImage source) {
        boolean hasAlpha = source.getColorModel().hasAlpha();
        int targetType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage normalized = new BufferedImage(source.getWidth(), source.getHeight(), targetType);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private BufferedImage resizeIfNeeded(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int largestEdge = Math.max(width, height);
        if (largestEdge <= maxEdge) {
            return source;
        }
        double scale = (double) maxEdge / (double) largestEdge;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage resized = new BufferedImage(
                targetWidth,
                targetHeight,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private EncodedImage encode(BufferedImage image, String declaredMimeType) {
        boolean keepPng = "image/png".equals(normalizeMime(declaredMimeType));
        boolean usePng = image.getColorModel().hasAlpha() || keepPng;
        String format = usePng ? "png" : "jpeg";
        String mimeType = usePng ? "image/png" : "image/jpeg";

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageWriter writer = null;
        try {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
            if (!writers.hasNext()) {
                throw new InvalidImageException("image_encode_failed");
            }
            writer = writers.next();
            try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (!usePng && param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(jpegQuality);
                }
                writer.write(null, new IIOImage(image, null, null), param);
            }
        } catch (InvalidImageException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidImageException("image_encode_failed");
        } finally {
            if (writer != null) {
                writer.dispose();
            }
        }

        byte[] bytes = output.toByteArray();
        if (bytes.length == 0) {
            throw new InvalidImageException("image_encode_failed");
        }
        return new EncodedImage(bytes, mimeType);
    }

    private String normalizeMime(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        return mimeType.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isImageMime(String mimeType) {
        return normalizeMime(mimeType) != null && normalizeMime(mimeType).startsWith("image/");
    }

    private record DecodedImage(BufferedImage image, int width, int height) {}
    private record EncodedImage(byte[] bytes, String mimeType) {}

    public record ImageResult(String mimeType, Integer width, Integer height) {}

    public static class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    public static class MediaUnavailableException extends RuntimeException {
        public MediaUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
