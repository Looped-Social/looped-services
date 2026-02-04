package com.looped.communities;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SpecializationIcons {
    public static final int EMOJI_MAX_CHARS = 64;
    public static final int VALUE_MAX_CHARS = 128;

    private SpecializationIcons() {}

    public static Map<String, Object> payloadOrNull(String iconKind, String iconValue) {
        String kind = normalizeKindOrNull(iconKind);
        if (kind == null) return null;
        String value = normalizeValueOrNull(iconValue);
        if (value == null) return null;
        if (!isValid(kind, value)) return null;
        return Map.of(
                "kind", kind,
                "value", value
        );
    }

    public static NormalizedIcon normalizeAndValidateForWrite(IconRequest icon,
                                                              boolean sfSymbolsEnabled,
                                                              Set<String> sfSymbolAllowlist,
                                                              boolean imageUrlEnabled,
                                                              String imageUrlAllowedPrefix) {
        if (icon == null) return null;
        String kind = normalizeKindOrNull(icon.kind());
        if (kind == null) {
            throw new IconValidationException("invalid_icon_kind", "icon.kind must be emoji, sf_symbol, or image_url");
        }
        String value = icon.value() == null ? "" : icon.value().trim();
        if (value.isBlank()) {
            return NormalizedIcon.clear();
        }
        if (value.length() > VALUE_MAX_CHARS) {
            throw new IconValidationException("invalid_icon_value", "icon.value is too long");
        }
        if (containsControlChars(value)) {
            throw new IconValidationException("invalid_icon_value", "icon.value contains invalid characters");
        }

        return switch (kind) {
            case "emoji" -> {
                if (value.length() > EMOJI_MAX_CHARS) {
                    throw new IconValidationException("invalid_emoji", "emoji icon.value is too long");
                }
                yield new NormalizedIcon("emoji", value);
            }
            case "sf_symbol" -> {
                if (!sfSymbolsEnabled) {
                    throw new IconValidationException("sf_symbol_not_supported", "sf_symbol icons are not enabled on this environment");
                }
                if (!isValidSfSymbolName(value)) {
                    throw new IconValidationException("invalid_sf_symbol", "sf_symbol icon.value is invalid");
                }
                if (sfSymbolAllowlist == null || sfSymbolAllowlist.isEmpty()) {
                    throw new IconValidationException("sf_symbol_allowlist_missing", "sf_symbol allowlist is not configured");
                }
                if (!sfSymbolAllowlist.contains(value)) {
                    throw new IconValidationException("invalid_sf_symbol", "sf_symbol icon.value is not allowed");
                }
                yield new NormalizedIcon("sf_symbol", value);
            }
            case "image_url" -> {
                if (!imageUrlEnabled) {
                    throw new IconValidationException("image_url_not_supported", "image_url icons are not enabled on this environment");
                }
                if (!value.startsWith("https://")) {
                    throw new IconValidationException("invalid_image_url", "image_url icon.value must be https");
                }
                if (imageUrlAllowedPrefix != null && !imageUrlAllowedPrefix.isBlank()) {
                    String prefix = imageUrlAllowedPrefix.trim();
                    if (!value.startsWith(prefix)) {
                        throw new IconValidationException("invalid_image_url", "image_url icon.value must be on the allowed domain");
                    }
                }
                yield new NormalizedIcon("image_url", value);
            }
            default -> throw new IconValidationException("invalid_icon_kind", "icon.kind must be emoji, sf_symbol, or image_url");
        };
    }

    private static boolean isValid(String kind, String value) {
        if (value == null) return false;
        if (value.isBlank()) return false;
        if (value.length() > VALUE_MAX_CHARS) return false;
        if (containsControlChars(value)) return false;
        return switch (kind) {
            case "emoji" -> value.length() <= EMOJI_MAX_CHARS;
            case "sf_symbol" -> isValidSfSymbolName(value);
            case "image_url" -> value.startsWith("https://");
            default -> false;
        };
    }

    private static String normalizeKindOrNull(String raw) {
        if (raw == null) return null;
        String kind = raw.trim().toLowerCase(Locale.ROOT);
        if (kind.isBlank()) return null;
        return switch (kind) {
            case "emoji", "sf_symbol", "image_url" -> kind;
            default -> null;
        };
    }

    private static String normalizeValueOrNull(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank()) return null;
        return value;
    }

    private static boolean containsControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') return true;
            if (Character.isISOControl(c)) return true;
        }
        return false;
    }

    private static boolean isValidSfSymbolName(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isBlank()) return false;
        if (v.length() > VALUE_MAX_CHARS) return false;
        if (v.startsWith(".") || v.endsWith(".")) return false;
        if (v.contains("..")) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    public record IconRequest(String kind, String value) {}

    public record NormalizedIcon(String kind, String value) {
        public static NormalizedIcon clear() {
            return new NormalizedIcon(null, null);
        }

        public boolean isClear() {
            return kind == null && value == null;
        }
    }

    public static final class IconValidationException extends RuntimeException {
        private final String error;
        private final String message;

        public IconValidationException(String error, String message) {
            super(message);
            this.error = error;
            this.message = message;
        }

        public String error() {
            return error;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}

