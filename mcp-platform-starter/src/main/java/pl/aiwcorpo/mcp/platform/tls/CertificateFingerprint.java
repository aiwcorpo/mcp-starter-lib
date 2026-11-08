package pl.aiwcorpo.mcp.platform.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/**
 * The fingerprint of a certificate in the format of the contracts ({@code CertificateFingerprint}): {@code sha256:} and 64 lower-case hex
 * digits of the SHA-256 of the DER encoding, without colons. It is what the portal shows when the connection to this server is approved,
 * so an author compares it with the line of the server log.
 */
public final class CertificateFingerprint {

    private CertificateFingerprint() {
    }

    public static String of(X509Certificate certificate) {
        try {
            return of(certificate.getEncoded());
        } catch (CertificateEncodingException ex) {
            throw new IllegalStateException("the certificate has no DER encoding", ex);
        }
    }

    public static String of(byte[] der) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is part of every JDK", ex);
        }
    }
}
