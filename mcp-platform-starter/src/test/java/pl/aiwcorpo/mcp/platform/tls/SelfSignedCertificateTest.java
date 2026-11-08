package pl.aiwcorpo.mcp.platform.tls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.InetAddress;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SelfSignedCertificateTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static SelfSignedCertificate.Generated generate() throws Exception {
        return SelfSignedCertificate.generate("mcp-jira", List.of("localhost", "mcp-jira.corp.test"), List.of(InetAddress.getLoopbackAddress()),
                Instant.now().minus(Duration.ofMinutes(5)), Duration.ofDays(3650));
    }

    @Test
    void theCertificateIsAValidSelfSignedLeafForServerAuthWithTheNamesItWasGiven() throws Exception {
        X509Certificate certificate = generate().certificate();

        certificate.verify(certificate.getPublicKey());
        certificate.checkValidity();
        assertThat(certificate.getSubjectX500Principal().getName()).isEqualTo("CN=mcp-jira");
        assertThat(certificate.getIssuerX500Principal()).isEqualTo(certificate.getSubjectX500Principal());
        assertThat(certificate.getVersion()).isEqualTo(3);
        assertThat(certificate.getBasicConstraints()).as("a leaf, not a CA").isEqualTo(-1);
        assertThat(certificate.getExtendedKeyUsage()).containsExactly("1.3.6.1.5.5.7.3.1");
        assertThat(certificate.getKeyUsage()[0]).as("digitalSignature").isTrue();
        assertThat(certificate.getSigAlgName()).isEqualToIgnoringCase("SHA256withECDSA");
        assertThat(certificate.getSubjectAlternativeNames()).extracting(entry -> entry.get(1).toString())
                .containsExactlyInAnyOrder("localhost", "mcp-jira.corp.test", "127.0.0.1");
        assertThat(certificate.getNotAfter().toInstant()).isAfter(Instant.now().plus(Duration.ofDays(3600)));
    }

    @Test
    void thePemOfTheKeyBelongsToTheCertificate() throws Exception {
        SelfSignedCertificate.Generated generated = generate();
        String body = generated.privateKeyPem().replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        byte[] message = "proof".getBytes();
        Signature sign = Signature.getInstance("SHA256withECDSA");
        sign.initSign(key);
        sign.update(message);
        Signature check = Signature.getInstance("SHA256withECDSA");
        check.initVerify(generated.certificate());
        check.update(message);

        assertThat(check.verify(sign.sign())).isTrue();
        assertThat(CertificateFactory.getInstance("X.509").generateCertificate(
                new java.io.ByteArrayInputStream(generated.certificatePem().getBytes()))).isEqualTo(generated.certificate());
    }

    @Test
    void everyCertificateIsNewAndTheLongValidityUsesGeneralizedTime() throws Exception {
        assertThat(generate().certificate().getSerialNumber()).isNotEqualTo(generate().certificate().getSerialNumber());

        X509Certificate far = SelfSignedCertificate.generate("mcp-jira", List.of("localhost"), List.of(),
                Instant.parse("2049-12-31T00:00:00Z"), Duration.ofDays(3650)).certificate();
        assertThat(far.getNotAfter().toInstant()).isAfter(Instant.parse("2050-01-01T00:00:00Z"));
    }

    @Test
    void theFingerprintIsTheSha256OfTheDerInTheFormatOfTheContracts() throws Exception {
        JsonNode vector = JSON.readTree(read("mcp-contracts/test-vectors/certificate-fingerprint/self-signed-ec.json"));
        byte[] der = Base64.getDecoder().decode(vector.get("derBase64").asString());
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));

        assertThat(CertificateFingerprint.of(certificate)).isEqualTo(vector.get("fingerprint").asString());
        assertThat(CertificateFingerprint.of(der)).isEqualTo(vector.get("fingerprint").asString());
        assertThat(CertificateFingerprint.of(generate().certificate())).matches("^sha256:[0-9a-f]{64}$");
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = SelfSignedCertificateTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes());
        }
    }
}
