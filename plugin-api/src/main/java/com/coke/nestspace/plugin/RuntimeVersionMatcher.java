package com.coke.nestspace.plugin;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Matcher for Android version codes. Exact matching is the default; plugins
 * can opt into a glob, range or any-version match for upgrades.
 */
public final class RuntimeVersionMatcher {

    private enum Kind {
        EXACT,
        GLOB,
        RANGE,
        ANY
    }

    private final Kind kind;
    private final long minVersionCode;
    private final long maxVersionCode;
    private final String glob;
    private final Pattern pattern;

    private RuntimeVersionMatcher(
        Kind kind,
        long minVersionCode,
        long maxVersionCode,
        String glob
    ) {
        this.kind = kind;
        this.minVersionCode = minVersionCode;
        this.maxVersionCode = maxVersionCode;
        this.glob = glob;
        this.pattern = glob == null ? null : Pattern.compile(toRegex(glob));
    }

    public static RuntimeVersionMatcher exact(long versionCode) {
        validateVersionCode(versionCode);
        return new RuntimeVersionMatcher(
            Kind.EXACT,
            versionCode,
            versionCode,
            null
        );
    }

    /**
     * Matches the decimal versionCode with '*' and '?' wildcards.
     * Example: {@code glob("74*")}.
     */
    public static RuntimeVersionMatcher glob(String pattern) {
        String value = Objects.requireNonNull(pattern, "pattern").trim();
        if (!value.matches("[0-9*?]+")) {
            throw new IllegalArgumentException(
                "versionCode glob may contain digits, '*' and '?'"
            );
        }
        return new RuntimeVersionMatcher(
            Kind.GLOB,
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            value
        );
    }

    public static RuntimeVersionMatcher prefix(String prefix) {
        String value = Objects.requireNonNull(prefix, "prefix").trim();
        if (!value.matches("[0-9]+")) {
            throw new IllegalArgumentException(
                "versionCode prefix must contain digits"
            );
        }
        return glob(value + "*");
    }

    public static RuntimeVersionMatcher atLeast(long minVersionCode) {
        return range(minVersionCode, Long.MAX_VALUE);
    }

    public static RuntimeVersionMatcher atMost(long maxVersionCode) {
        return range(0, maxVersionCode);
    }

    public static RuntimeVersionMatcher range(
        long minVersionCode,
        long maxVersionCode
    ) {
        validateVersionCode(minVersionCode);
        validateVersionCode(maxVersionCode);
        if (maxVersionCode < minVersionCode) {
            throw new IllegalArgumentException("invalid versionCode range");
        }
        return new RuntimeVersionMatcher(
            Kind.RANGE,
            minVersionCode,
            maxVersionCode,
            null
        );
    }

    public static RuntimeVersionMatcher anyVersion() {
        return new RuntimeVersionMatcher(
            Kind.ANY,
            0,
            Long.MAX_VALUE,
            null
        );
    }

    public boolean isExact() {
        return kind == Kind.EXACT;
    }

    public long getExactVersionCode() {
        if (!isExact()) {
            throw new IllegalStateException("matcher is not exact");
        }
        return minVersionCode;
    }

    public long getMinVersionCode() {
        return minVersionCode;
    }

    public long getMaxVersionCode() {
        return maxVersionCode;
    }

    public String getGlob() {
        return glob;
    }

    public boolean matches(long versionCode) {
        switch (kind) {
            case EXACT:
                return versionCode == minVersionCode;
            case GLOB:
                return pattern.matcher(Long.toString(versionCode)).matches();
            case RANGE:
                return versionCode >= minVersionCode &&
                    versionCode <= maxVersionCode;
            case ANY:
                return true;
            default:
                return false;
        }
    }

    boolean mayOverlap(RuntimeVersionMatcher other) {
        Objects.requireNonNull(other, "other");
        if (kind == Kind.ANY || other.kind == Kind.ANY) return true;
        if (kind == Kind.EXACT) return other.matches(minVersionCode);
        if (other.kind == Kind.EXACT) return matches(other.minVersionCode);
        if (kind == Kind.RANGE && other.kind == Kind.RANGE) {
            return minVersionCode <= other.maxVersionCode &&
                other.minVersionCode <= maxVersionCode;
        }
        // A glob can cover sparse values. Treat another fuzzy matcher on the
        // same package as overlapping so policy ownership stays deterministic.
        return true;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof RuntimeVersionMatcher)) return false;
        RuntimeVersionMatcher other = (RuntimeVersionMatcher) value;
        return kind == other.kind &&
            minVersionCode == other.minVersionCode &&
            maxVersionCode == other.maxVersionCode &&
            Objects.equals(glob, other.glob);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, minVersionCode, maxVersionCode, glob);
    }

    @Override
    public String toString() {
        switch (kind) {
            case EXACT:
                return Long.toString(minVersionCode);
            case GLOB:
                return "glob:" + glob;
            case RANGE:
                return minVersionCode + ".." + maxVersionCode;
            case ANY:
                return "*";
            default:
                return kind.name();
        }
    }

    private static void validateVersionCode(long versionCode) {
        if (versionCode < 0) {
            throw new IllegalArgumentException(
                "versionCode must be non-negative"
            );
        }
    }

    private static String toRegex(String value) {
        StringBuilder result = new StringBuilder(value.length() + 4);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '*') {
                result.append("[0-9]*");
            } else if (current == '?') {
                result.append("[0-9]");
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
