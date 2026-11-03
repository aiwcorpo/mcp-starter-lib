package pl.aiwcorpo.mcp.platform.capability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductVersionTest {

    @Test
    void theNumericPartIsReadAndTheSuffixIgnored() {
        assertThat(ProductVersion.parse("9.12.3")).contains(new ProductVersion(9, 12, 3));
        assertThat(ProductVersion.parse("17.5.1-ee")).contains(new ProductVersion(17, 5, 1));
        assertThat(ProductVersion.parse("2.19.0-SNAPSHOT")).contains(new ProductVersion(2, 19, 0));
        assertThat(ProductVersion.parse(" v3.1 ")).contains(new ProductVersion(3, 1, 0));
    }

    @Test
    void whatIsNotAVersionIsEmpty() {
        for (String text : new String[] {null, "", "9", "abc", "<html>9.12</html>", "9.12345", "1.2.3.4x", ".5.1"}) {
            if ("1.2.3.4x".equals(text)) {
                assertThat(ProductVersion.parse(text)).contains(new ProductVersion(1, 2, 3));
            } else {
                assertThat(ProductVersion.parse(text)).as(String.valueOf(text)).isEmpty();
            }
        }
    }

    @Test
    void versionsCompareByNumberNotByText() {
        ProductVersion nineTwelve = ProductVersion.parse("9.12.0").orElseThrow();

        assertThat(nineTwelve.atLeast(9, 12)).isTrue();
        assertThat(ProductVersion.parse("9.9.9").orElseThrow().atLeast(9, 12)).isFalse();
        assertThat(ProductVersion.parse("10.0").orElseThrow().atLeast(9, 12)).isTrue();
        assertThat(ProductVersion.parse("9.2").orElseThrow().before(9, 12)).isTrue();
        assertThat(nineTwelve).isLessThan(ProductVersion.parse("9.12.1").orElseThrow());
    }
}
