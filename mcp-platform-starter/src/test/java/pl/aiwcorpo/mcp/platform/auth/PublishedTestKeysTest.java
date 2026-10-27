package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** The digest list from the contracts jar covers every EC key file of the test-vectors jar. */
class PublishedTestKeysTest {

    @Test
    void everyPublishedEcKeyIsRecognisedWhateverItsKid() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/keys/*.public.jwk.json");
        int ecKeys = 0;
        for (Resource file : files) {
            try (InputStream in = file.getInputStream()) {
                JWK jwk = JWK.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (jwk instanceof ECKey ec) {
                    ecKeys++;
                    assertThat(PublishedTestKeys.isTestKey("gw-prod-2026-11", ec)).as(file.getFilename()).isTrue();
                }
            }
        }
        assertThat(ecKeys).isEqualTo(4);
    }

    @Test
    void aFreshKeyUnderAProductionKidIsNotATestKey() throws Exception {
        ECKey fresh = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).generate();

        assertThat(PublishedTestKeys.isTestKey("gw-prod-2026-11", fresh.toPublicJWK())).isFalse();
        assertThat(PublishedTestKeys.isTestKey("test-anything", fresh.toPublicJWK())).isTrue();
    }
}
