package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityDomainsRepository;
import com.looped.communities.CommunitySectorLinksRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/communities")
public class AdminCommunityImportController {
    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final CommunityDomainsRepository domains;
    private final CommunitySectorLinksRepository links;
    private final AdminAuditRepository audit;

    public AdminCommunityImportController(AdminAuthService auth,
                                          CommunitiesRepository communities,
                                          CommunityDomainsRepository domains,
                                          CommunitySectorLinksRepository links,
                                          AdminAuditRepository audit) {
        this.auth = auth;
        this.communities = communities;
        this.domains = domains;
        this.links = links;
        this.audit = audit;
    }

    @PostMapping(value = "/import-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importCsv(@AuthenticationPrincipal Jwt jwt,
                                       @RequestPart("file") MultipartFile file) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "file_required"));
        }
        try (InputStream input = file.getInputStream()) {
            ImportSummary summary = processCsv(input, authRes.admin().id);
            if (summary.error != null) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(summary.toMap());
            }
            return ResponseEntity.ok(summary.toMap());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_csv"));
        }
    }

    private ImportSummary processCsv(InputStream input, long adminId) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();
        try (CSVParser parser = new CSVParser(new InputStreamReader(input, StandardCharsets.UTF_8), format)) {
            Map<String, String> headerMap = normalizeHeaders(parser.getHeaderMap().keySet());
            List<String> missing = missingHeaders(headerMap, List.of("community_type", "display_name"));
            if (!missing.isEmpty()) {
                return ImportSummary.error("missing_columns", missing);
            }

            ImportSummary summary = new ImportSummary();
            for (CSVRecord record : parser) {
                summary.rowsTotal += 1;
                int row = (int) record.getRecordNumber() + 1;
                String rawKind = field(record, headerMap, "community_type");
                String rawSpecializationType = field(record, headerMap, "specialization_type");
                KindResult kindResult = normalizeKind(rawKind, rawSpecializationType);
                String kind = kindResult != null ? kindResult.kind : null;
                String specializationType = kindResult != null ? kindResult.specializationType : null;
                String name = normalizeName(field(record, headerMap, "display_name"));
                if (kind == null || name == null) {
                    summary.addError(row, "invalid_kind_or_name");
                    continue;
                }
                if ("specialization".equals(kind) && specializationType == null) {
                    summary.addError(row, "specialization_type_required");
                    continue;
                }
                String description = normalizeDescription(field(record, headerMap, "description"));
                String sectorName = normalizeName(field(record, headerMap, "sector"));
                String domainsRaw = field(record, headerMap, "authorized_domains");

                CommunityResult communityResult = getOrCreateCommunity(kind, specializationType, name, description);
                if (communityResult.created) summary.communitiesCreated += 1;
                else summary.communitiesSkipped += 1;

                List<String> domainList = parseDomains(domainsRaw);
                for (String domain : domainList) {
                    String normalizedDomain = domains.normalizeDomain(domain);
                    if (normalizedDomain == null) {
                        summary.addError(row, "invalid_domain");
                        continue;
                    }
                    if (domains.insert(communityResult.id, normalizedDomain)) {
                        summary.domainsAdded += 1;
                    }
                }

                if (isSectorLinkableKind(kind) && sectorName != null) {
                    CommunityResult sectorResult = getOrCreateCommunity("sector", null, sectorName, null);
                    if (sectorResult.created) summary.sectorsCreated += 1;
                    if (links.insert(sectorResult.id, communityResult.id)) {
                        summary.linksCreated += 1;
                    }
                }
            }
            audit.log(adminId, "community.import_csv", "community", null,
                    "rows=" + summary.rowsTotal + ",created=" + summary.communitiesCreated);
            return summary;
        }
    }

    private Map<String, String> normalizeHeaders(Iterable<String> headers) {
        Map<String, String> normalized = new HashMap<>();
        for (String header : headers) {
            if (header == null) continue;
            String key = header.trim().toLowerCase(Locale.ROOT);
            if (!key.isBlank()) normalized.put(key, header);
        }
        return normalized;
    }

    private List<String> missingHeaders(Map<String, String> headerMap, List<String> required) {
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (!headerMap.containsKey(key)) missing.add(key);
        }
        return missing;
    }

    private String field(CSVRecord record, Map<String, String> headers, String key) {
        String actual = headers.get(key);
        if (actual == null) return null;
        String raw = record.isMapped(actual) ? record.get(actual) : null;
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private KindResult normalizeKind(String rawKind, String rawSpecializationType) {
        if (rawKind == null) return null;
        String normalized = rawKind.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (normalized.equals("profession") || normalized.equals("proffesion")) {
            normalized = "sector";
        }
        if (normalized.equals("major") || normalized.equals("department")) {
            return new KindResult("specialization", normalized);
        }
        if (normalized.equals("specialization")) {
            return new KindResult("specialization", normalizeSpecializationType(rawSpecializationType));
        }
        if (!normalized.equals("company") && !normalized.equals("school") && !normalized.equals("sector")) {
            return null;
        }
        return new KindResult(normalized, null);
    }

    private String normalizeName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeDescription(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeSpecializationType(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!normalized.equals("major") && !normalized.equals("department")) return null;
        return normalized;
    }

    private List<String> parseDomains(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split(",");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String cleaned = part == null ? "" : part.trim();
            if (!cleaned.isBlank()) out.add(cleaned);
        }
        return out;
    }

    private boolean isSectorLinkableKind(String kind) {
        return "company".equals(kind) || "school".equals(kind);
    }

    private CommunityResult getOrCreateCommunity(String kind, String specializationType, String name, String description) {
        var existing = communities.findByKindAndName(kind, name, specializationType);
        if (existing.isPresent()) {
            return new CommunityResult(existing.get().id, false);
        }
        long id;
        try {
            id = communities.insert(kind, name, description, null, null, specializationType);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            var fallback = communities.findByKindAndName(kind, name, specializationType);
            if (fallback.isPresent()) {
                return new CommunityResult(fallback.get().id, false);
            }
            throw e;
        }
        return new CommunityResult(id, true);
    }

    private record CommunityResult(long id, boolean created) {}
    private record KindResult(String kind, String specializationType) {}

    private static class ImportSummary {
        int rowsTotal;
        int communitiesCreated;
        int communitiesSkipped;
        int sectorsCreated;
        int linksCreated;
        int domainsAdded;
        List<Map<String, Object>> errors = new ArrayList<>();
        String error;
        List<String> missing;

        void addError(int row, String code) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("row", row);
            item.put("error", code);
            errors.add(item);
        }

        static ImportSummary error(String code, List<String> missing) {
            ImportSummary summary = new ImportSummary();
            summary.error = code;
            summary.missing = missing;
            return summary;
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (error != null) {
                out.put("error", error);
                if (missing != null && !missing.isEmpty()) out.put("missing", missing);
                return out;
            }
            out.put("rows_total", rowsTotal);
            out.put("communities_created", communitiesCreated);
            out.put("communities_skipped", communitiesSkipped);
            out.put("sectors_created", sectorsCreated);
            out.put("links_created", linksCreated);
            out.put("domains_added", domainsAdded);
            if (!errors.isEmpty()) out.put("errors", errors);
            return out;
        }
    }
}
