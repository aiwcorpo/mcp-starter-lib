package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class NoIdentityProviderResolverTest {

    private final NoIdentityProviderResolver resolver = new NoIdentityProviderResolver();

    @Test
    void withoutAProviderEveryoneIsAnonymous() {
        assertThat(resolver.resolve(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void aTokenThatCannotBeCheckedIsRejectedNotIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(IdentityResolver.InvalidCredentialsException.class);
    }

    @Test
    void anotherSchemeIsNotACredentialForThisServer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic Z2F0ZXdheTpzZWNyZXQ=");

        assertThat(resolver.resolve(request)).isEmpty();
    }
}
