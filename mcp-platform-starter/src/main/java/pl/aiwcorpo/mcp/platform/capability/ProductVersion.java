package pl.aiwcorpo.mcp.platform.capability;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numeric part of a product version ({@code 9.12.3}, {@code 17.5.1-ee}, {@code 2.19.0-SNAPSHOT}), enough to ask
 * "at least 9.12" in a {@link CapabilityProbe}. A suffix is ignored; a version with fewer than two numbers or with a
 * component above 9999 is not a version ({@link #parse(String)} is empty), so a proxy's HTML page is never read as one.
 */
public record ProductVersion(int major, int minor, int patch) implements Comparable<ProductVersion> {

    private static final Pattern NUMERIC = Pattern.compile("^v?(\\d{1,4})\\.(\\d{1,4})(?:\\.(\\d{1,4})(?!\\d)|(?!\\.\\d))(?!\\d)");

    public static Optional<ProductVersion> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = NUMERIC.matcher(text.strip());
        if (!matcher.find()) {
            return Optional.empty();
        }
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return Optional.of(new ProductVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), patch));
    }

    public boolean atLeast(int major, int minor) {
        return compareTo(new ProductVersion(major, minor, 0)) >= 0;
    }

    public boolean before(int major, int minor) {
        return !atLeast(major, minor);
    }

    @Override
    public int compareTo(ProductVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
