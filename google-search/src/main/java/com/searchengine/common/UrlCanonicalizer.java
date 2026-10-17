package com.searchengine.common;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure URL canonicalization producing a {@link CanonicalUrl} (design LLD §A).
 *
 * <p>{@link #canonicalize(String)} is a deterministic total function: every input maps to
 * exactly one result and the same input always yields the same result. Any input that is not
 * a syntactically valid absolute {@code http}/{@code https} URL maps to {@link CanonicalUrl#INVALID}
 * (Req 1.2). This backs deterministic Frontier ordering/dedup (Req 1.4, 1.6) and duplicate-content
 * collapse to the lexicographically smallest Canonical_URL (Req 4.6).
 *
 * <p>Normalization procedure (design §A):
 * <ol>
 *   <li>Validate the URL is absolute with an {@code http}/{@code https} scheme and a host.</li>
 *   <li>Lower-case the scheme.</li>
 *   <li>Lower-case and punycode (IDNA) the host; strip a single trailing dot.</li>
 *   <li>Drop the port when it is the default for the scheme (80 for http, 443 for https).</li>
 *   <li>Percent-decode unreserved characters in the path, remove dot-segments, collapse
 *       repeated {@code /}; default an empty path to {@code /}.</li>
 *   <li>Drop tracking query parameters, then sort the remaining parameters stably.</li>
 *   <li>Drop the fragment.</li>
 * </ol>
 *
 * <p>This class is stateless; all methods are static.
 */
public final class UrlCanonicalizer {

    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;

    /**
     * Common tracking/analytics query parameters that do not affect document identity and are
     * dropped during canonicalization so that URLs differing only by tracking params dedup
     * (Req 1.6). Matched case-insensitively.
     */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
            "gclid", "gclsrc", "dclid", "fbclid", "msclkid", "mc_eid", "mc_cid",
            "_ga", "yclid", "igshid", "ref", "ref_src");

    private UrlCanonicalizer() {
        // no instances
    }

    /**
     * Canonicalizes a raw URL string into a {@link CanonicalUrl}, or {@link CanonicalUrl#INVALID}
     * if the input is not a syntactically valid absolute http/https URL (Req 1.2).
     *
     * @param url the raw URL string (may be null)
     * @return the Canonical_URL, or {@link CanonicalUrl#INVALID}
     */
    public static CanonicalUrl canonicalize(String url) {
        if (url == null) {
            return CanonicalUrl.INVALID;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return CanonicalUrl.INVALID;
        }

        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return CanonicalUrl.INVALID;
        }

        // Must be absolute with a scheme we support and a host (Req 1.2).
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return CanonicalUrl.INVALID;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return CanonicalUrl.INVALID;
        }

        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isEmpty()) {
            // A host is required; opaque or authority-less URLs are not valid absolute web URLs.
            return CanonicalUrl.INVALID;
        }

        String host = canonicalizeHost(rawHost);
        if (host.isEmpty()) {
            return CanonicalUrl.INVALID;
        }

        int port = uri.getPort();
        boolean includePort = port != -1 && !isDefaultPort(scheme, port);

        String path = canonicalizePath(uri.getRawPath());
        String query = canonicalizeQuery(uri.getRawQuery());

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (includePort) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        // Fragment is always dropped.

        return CanonicalUrl.of(sb.toString());
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return (scheme.equals("http") && port == DEFAULT_HTTP_PORT)
                || (scheme.equals("https") && port == DEFAULT_HTTPS_PORT);
    }

    private static String canonicalizeHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        // Strip a single trailing dot (fully-qualified form) per design §A.
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        try {
            // IDNA/punycode encode Unicode hostnames deterministically.
            host = IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            // Leave the lower-cased host as-is if it cannot be IDNA-encoded.
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static String canonicalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/"; // default empty path to "/"
        }
        String decoded = percentDecodeUnreserved(rawPath);
        String collapsed = collapseSlashes(decoded);
        String noDotSegments = removeDotSegments(collapsed);
        if (noDotSegments.isEmpty()) {
            return "/";
        }
        if (!noDotSegments.startsWith("/")) {
            noDotSegments = "/" + noDotSegments;
        }
        return noDotSegments;
    }

    /** Collapses runs of {@code /} into a single {@code /} (design §A "collapse //"). */
    private static String collapseSlashes(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        boolean prevSlash = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (!prevSlash) {
                    sb.append(c);
                }
                prevSlash = true;
            } else {
                sb.append(c);
                prevSlash = false;
            }
        }
        return sb.toString();
    }

    /**
     * Removes {@code .} and {@code ..} dot-segments per RFC 3986 §5.2.4, operating on the
     * path segments while preserving a leading and trailing slash where appropriate.
     */
    private static String removeDotSegments(String path) {
        boolean absolute = path.startsWith("/");
        boolean trailingSlash = path.endsWith("/") && path.length() > 1;
        String[] rawSegments = path.split("/", -1);

        List<String> out = new ArrayList<>();
        for (String seg : rawSegments) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                // For absolute paths, ".." at root is discarded (cannot go above root).
                continue;
            }
            out.add(seg);
        }

        StringBuilder sb = new StringBuilder();
        if (absolute) {
            sb.append('/');
        }
        sb.append(String.join("/", out));
        if (trailingSlash && !out.isEmpty()) {
            sb.append('/');
        }
        String result = sb.toString();
        if (result.isEmpty()) {
            result = absolute ? "/" : "";
        }
        return result;
    }

    /**
     * Percent-decodes only unreserved characters (ALPHA / DIGIT / {@code -._~}) per RFC 3986,
     * leaving all other percent-encoded octets untouched and normalizing surviving percent-encodings
     * to upper-case hex digits. Deterministic.
     */
    private static String percentDecodeUnreserved(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && isHex(s, i)) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                int val = (hi << 4) + lo;
                if (isUnreserved(val)) {
                    sb.append((char) val);
                } else {
                    // Preserve the octet but normalize hex to upper-case.
                    sb.append('%')
                            .append(Character.toUpperCase(s.charAt(i + 1)))
                            .append(Character.toUpperCase(s.charAt(i + 2)));
                }
                i += 3;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isHex(String s, int percentIndex) {
        if (percentIndex + 2 >= s.length()) {
            return false;
        }
        return Character.digit(s.charAt(percentIndex + 1), 16) != -1
                && Character.digit(s.charAt(percentIndex + 2), 16) != -1;
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /**
     * Drops tracking parameters then sorts the remaining query parameters by key (then value)
     * to produce a stable, deterministic query string (design §A). Returns null when the query
     * is absent or empty after filtering.
     */
    private static String canonicalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        String[] pairs = rawQuery.split("&", -1);
        List<String[]> kept = new ArrayList<>();
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : null;
            String decodedKey = percentDecodeUnreserved(key);
            if (TRACKING_PARAMS.contains(decodedKey.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String decodedValue = value == null ? null : percentDecodeUnreserved(value);
            kept.add(new String[]{decodedKey, decodedValue});
        }
        if (kept.isEmpty()) {
            return null;
        }
        kept.sort(Comparator
                .comparing((String[] p) -> p[0])
                .thenComparing(p -> p[1] == null ? "" : p[1]));

        List<String> rendered = new ArrayList<>(kept.size());
        for (String[] p : kept) {
            rendered.add(p[1] == null ? p[0] : p[0] + "=" + p[1]);
        }
        return String.join("&", rendered);
    }
}
