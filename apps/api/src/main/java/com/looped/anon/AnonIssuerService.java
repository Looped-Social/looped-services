package com.looped.anon;

import com.looped.anon.crypto.BlindRsaSigner;
import com.looped.communities.CommunitiesRepository;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AnonIssuerService {
    private final AnonIssuerProperties props;
    private final AnonIssuerRepository issuers;
    private final CommunitiesRepository communities;
    private final AnonKeyCipher cipher;
    private final Map<Long, IssuerKey> communityCache = new ConcurrentHashMap<>();

    public AnonIssuerService(AnonIssuerProperties props, AnonIssuerRepository issuers, CommunitiesRepository communities) {
        this.props = props;
        this.issuers = issuers;
        this.communities = communities;
        this.cipher = new AnonKeyCipher(props.getKek());
    }

    public IssuerKey issuerForCommunity(long communityId, Long companyId) {
        IssuerKey cached = communityCache.get(communityId);
        if (cached != null) {
            if (isExpired(cached.expiresAt)) {
                communityCache.remove(communityId);
            } else {
                if (companyId != null && cached.companyId != null && !cached.companyId.equals(companyId)) {
                    throw new IllegalStateException("Community issuer company mismatch");
                }
                if (companyId != null && cached.companyId == null) {
                    issuers.upsert(cached.kid, "RSABSSA", cached.publicKey.getEncoded(),
                            cipher.encrypt(cached.privateKey.getEncoded()), companyId, "community", communityId, cached.expiresAt);
                    cached = new IssuerKey(cached.kid, cached.publicKey, cached.privateKey, cached.expiresAt, companyId);
                    communityCache.put(communityId, cached);
                }
                return cached;
            }
        }

        var existing = issuers.findByScope("community", communityId);
        IssuerKey issuerKey;
        if (existing.isPresent()) {
            issuerKey = toIssuerKey(existing.get());
            if (isExpired(issuerKey.expiresAt)) {
                issuerKey = rotateCommunityIssuer(existing.get(), communityId, companyId);
            } else {
                if (companyId != null && issuerKey.companyId != null && !issuerKey.companyId.equals(companyId)) {
                    throw new IllegalStateException("Community issuer company mismatch");
                }
                if (companyId != null && issuerKey.companyId == null) {
                    issuers.upsert(issuerKey.kid, "RSABSSA", issuerKey.publicKey.getEncoded(),
                            cipher.encrypt(issuerKey.privateKey.getEncoded()), companyId, "community", communityId, issuerKey.expiresAt);
                    issuerKey = new IssuerKey(issuerKey.kid, issuerKey.publicKey, issuerKey.privateKey, issuerKey.expiresAt, companyId);
                }
            }
        } else {
            issuerKey = createCommunityIssuer(communityId, companyId);
        }
        communityCache.put(communityId, issuerKey);
        return issuerKey;
    }

    public byte[] signBlinded(long communityId, Long companyId, byte[] blindedMessage) {
        var issuer = issuerForCommunity(communityId, companyId);
        var signer = new BlindRsaSigner(issuer.privateKey, issuer.publicKey);
        return signer.signBlinded(blindedMessage);
    }

    public IssuerKey issuerByKid(String kid) {
        var row = issuers.findByKid(kid).orElse(null);
        if (row == null) return null;
        return toIssuerKey(row);
    }

    public IssuerPublic issuerInfo(long communityId, Long companyId) {
        var issuer = issuerForCommunity(communityId, companyId);
        return new IssuerPublic(issuer.kid, issuer.publicKey, issuer.expiresAt, issuer.companyId);
    }

    private IssuerKey createCommunityIssuer(long communityId, Long companyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            int bits = props.getKeyBits() == null ? 2048 : props.getKeyBits();
            generator.initialize(bits);
            KeyPair pair = generator.generateKeyPair();
            RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            OffsetDateTime expiresAt = resolveExpiresAt(communityId);
            String kid = "anon-community-" + communityId + "-" + UUID.randomUUID();
            byte[] privateKeyEnc = cipher.encrypt(privateKey.getEncoded());
            issuers.upsert(kid, "RSABSSA", publicKey.getEncoded(), privateKeyEnc, companyId, "community", communityId, expiresAt);
            return new IssuerKey(kid, publicKey, privateKey, expiresAt, companyId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create community issuer key", e);
        }
    }

    private IssuerKey toIssuerKey(AnonIssuerRepository.IssuerRow row) {
        if (row.privateKeyEnc == null || row.privateKeyEnc.length == 0) {
            throw new IllegalStateException("Missing issuer private key for " + row.kid);
        }
        RSAPublicKey publicKey = parsePublicKey(row.publicKey);
        RSAPrivateKey privateKey = parsePrivateKey(cipher.decrypt(row.privateKeyEnc));
        return new IssuerKey(row.kid, publicKey, privateKey, row.expiresAt, row.companyId);
    }

    private RSAPublicKey parsePublicKey(byte[] encoded) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RSA public key", e);
        }
    }

    private RSAPrivateKey parsePrivateKey(byte[] encoded) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RSA private key", e);
        }
    }

    private OffsetDateTime resolveExpiresAt(long communityId) {
        Integer ttlDays = communities.findById(communityId)
                .map(c -> c.verificationTtlDays)
                .orElse(null);
        if (ttlDays != null && ttlDays > 0) {
            return OffsetDateTime.now().plusDays(ttlDays);
        }
        return props.getMaxAge() != null ? OffsetDateTime.now().plus(props.getMaxAge()) : null;
    }

    private boolean isExpired(OffsetDateTime expiresAt) {
        return expiresAt != null && expiresAt.isBefore(OffsetDateTime.now());
    }

    private IssuerKey rotateCommunityIssuer(AnonIssuerRepository.IssuerRow existing, long communityId, Long companyId) {
        Long effectiveCompanyId = companyId != null ? companyId : existing.companyId;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            int bits = props.getKeyBits() == null ? 2048 : props.getKeyBits();
            generator.initialize(bits);
            KeyPair pair = generator.generateKeyPair();
            RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            OffsetDateTime expiresAt = resolveExpiresAt(communityId);
            String kid = "anon-community-" + communityId + "-" + UUID.randomUUID();
            byte[] privateKeyEnc = cipher.encrypt(privateKey.getEncoded());
            issuers.updateById(existing.id, kid, "RSABSSA", publicKey.getEncoded(), privateKeyEnc,
                    effectiveCompanyId, "community", communityId, expiresAt);
            return new IssuerKey(kid, publicKey, privateKey, expiresAt, effectiveCompanyId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rotate community issuer key", e);
        }
    }

    public record IssuerKey(String kid, RSAPublicKey publicKey, RSAPrivateKey privateKey, OffsetDateTime expiresAt, Long companyId) {}

    public record IssuerPublic(String kid, RSAPublicKey publicKey, OffsetDateTime expiresAt, Long companyId) {}
}
