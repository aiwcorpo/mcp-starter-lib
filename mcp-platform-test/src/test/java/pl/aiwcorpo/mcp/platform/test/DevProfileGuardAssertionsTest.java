package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The helper servers use instead of starting the application with a network address; it must not depend on the host. */
class DevProfileGuardAssertionsTest {

    @Test
    void theLoopbackRuleHoldsWhateverTheHostLooksLike() {
        assertThatCode(DevProfileGuardAssertions::assertDevProfileLoopbackRule).doesNotThrowAnyException();
    }

    @Test
    void anAddressTheGuardAcceptsFailsTheAssertion() {
        assertThatThrownBy(() -> DevProfileGuardAssertions.assertDevProfileRefusesAddress("127.0.0.1"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("without naming the address");
    }
}
