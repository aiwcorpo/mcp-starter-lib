package pl.aiwcorpo.mcp.platform.tls;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * A self-signed X.509 certificate for the server port, built with the JDK alone (EC P-256, ECDSA with SHA-256): the JDK has no public API
 * to make one and the starter takes no library for it. The certificate is a leaf (no CA), for {@code serverAuth}, and names the host
 * names and addresses it is given; the gateway pins it by fingerprint (C5, {@code pinned}), so the names matter only to a client that
 * checks them.
 */
public final class SelfSignedCertificate {

    private static final byte[] OID_ECDSA_SHA256 = {0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02};
    private static final byte[] OID_COMMON_NAME = {0x55, 0x04, 0x03};
    private static final byte[] OID_KEY_USAGE = {0x55, 0x1D, 0x0F};
    private static final byte[] OID_SUBJECT_ALT_NAME = {0x55, 0x1D, 0x11};
    private static final byte[] OID_BASIC_CONSTRAINTS = {0x55, 0x1D, 0x13};
    private static final byte[] OID_EXT_KEY_USAGE = {0x55, 0x1D, 0x25};
    private static final byte[] OID_SERVER_AUTH = {0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01};
    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter GENERALIZED_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    /** The certificate and its private key, as the PEM texts that go into two files. */
    public record Generated(X509Certificate certificate, String certificatePem, String privateKeyPem) {
    }

    private SelfSignedCertificate() {
    }

    /**
     * @param commonName subject and issuer
     * @param dnsNames   host names for the subject alternative name
     * @param addresses  IP addresses for the subject alternative name
     * @param validFrom  start of validity
     * @param validity   how long it is valid
     */
    public static Generated generate(String commonName, List<String> dnsNames, List<InetAddress> addresses, Instant validFrom,
                                     Duration validity) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keys = generator.generateKeyPair();

        byte[] serial = new byte[20];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x3F;
        serial[0] |= 0x01;
        byte[] algorithm = sequence(oid(OID_ECDSA_SHA256));
        byte[] name = sequence(set(sequence(oid(OID_COMMON_NAME), tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8)))));
        byte[] tbs = sequence(
                tlv(0xA0, tlv(0x02, new byte[] {0x02})),
                tlv(0x02, serial),
                algorithm,
                name,
                sequence(time(validFrom), time(validFrom.plus(validity))),
                name,
                keys.getPublic().getEncoded(),
                tlv(0xA3, sequence(extensions(dnsNames, addresses))));

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keys.getPrivate());
        signature.update(tbs);
        byte[] signed = signature.sign();
        byte[] der = sequence(tbs, algorithm, tlv(0x03, concat(new byte[] {0x00}, signed)));

        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
        return new Generated(certificate, pem("CERTIFICATE", der), pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
    }

    private static byte[] extensions(List<String> dnsNames, List<InetAddress> addresses) {
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        for (String dns : dnsNames) {
            names.writeBytes(tlv(0x82, dns.getBytes(StandardCharsets.US_ASCII)));
        }
        for (InetAddress address : addresses) {
            names.writeBytes(tlv(0x87, address.getAddress()));
        }
        return concat(
                // A leaf: cA is false (the default, so an empty sequence), and the extension is critical.
                extension(OID_BASIC_CONSTRAINTS, true, sequence()),
                // digitalSignature only, which is what ECDSA key exchange in TLS needs.
                extension(OID_KEY_USAGE, true, tlv(0x03, new byte[] {0x07, (byte) 0x80})),
                extension(OID_EXT_KEY_USAGE, false, sequence(oid(OID_SERVER_AUTH))),
                extension(OID_SUBJECT_ALT_NAME, false, tlv(0x30, names.toByteArray())));
    }

    private static byte[] extension(byte[] oid, boolean critical, byte[] value) {
        return critical
                ? sequence(oid(oid), tlv(0x01, new byte[] {(byte) 0xFF}), tlv(0x04, value))
                : sequence(oid(oid), tlv(0x04, value));
    }

    private static byte[] time(Instant instant) {
        // RFC 5280: UTCTime up to 2049, GeneralizedTime from 2050.
        boolean utc = instant.atZone(ZoneOffset.UTC).getYear() < 2050;
        return tlv(utc ? 0x17 : 0x18, (utc ? UTC_TIME : GENERALIZED_TIME).format(instant).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(byte[] encoded) {
        return tlv(0x06, encoded);
    }

    private static byte[] sequence(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (value.length < 0x80) {
            out.write(value.length);
        } else {
            byte[] length = BigInteger.valueOf(value.length).toByteArray();
            int skip = length[0] == 0 ? 1 : 0;
            out.write(0x80 | (length.length - skip));
            out.write(length, skip, length.length - skip);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
