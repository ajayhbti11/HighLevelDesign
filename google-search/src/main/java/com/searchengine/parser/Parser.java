package com.searchengine.parser;

import com.searchengine.common.ContentRecord;
import com.searchengine.common.Normalizer;
import com.searchengine.common.ParseStatus;
import com.searchengine.common.ParsedDocument;
import com.searchengine.common.Token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser subsystem: extracts human-readable text, outbound links, and title from a raw
 * {@link ContentRecord}, normalizes the text via the shared {@link Normalizer}, and computes a
 * content hash for duplicate detection (design §2, Requirement 4).
 *
 * <p>{@link #parse(ContentRecord)} is deterministic: the same {@link ContentRecord} always yields
 * an equal {@link ParsedDocument}. It records a parse-failure status and skips indexing when the
 * declared content type is not in the configured supported set (Req 4.4) or when the raw body
 * exceeds the configured maximum parseable size (Req 4.5).
 *
 * <p>{@link #resolveDuplicate(String, String)} maintains a content-hash index (Req 4.6): documents
 * with identical normalized content (equal {@code contentHash}) collapse to the lexicographically
 * smallest Canonical_URL seen for that hash.
 *
 * <p>The HTML text/link/title extraction is intentionally lightweight and regex-based, but fully
 * deterministic. It strips {@code <script>} and {@code <style>} element bodies, removes all
 * remaining markup tags, and decodes a small set of common HTML entities so that the surviving
 * text is human-readable content only (Req 4.1).
 *
 * <p>This class is not thread-safe: the content-hash index backing {@link #resolveDuplicate} is a
 * plain map guarded by the caller.
 */
public final class Parser {

    /** Default set of content types eligible for parsing/indexing (Req 4.4). */
    public static final Set<String> DEFAULT_SUPPORTED_CONTENT_TYPES =
            Set.of("text/html", "application/xhtml+xml", "text/plain");

    /** Default maximum parseable size in bytes (Req 4.5): 10 MiB. */
    public static final int DEFAULT_MAX_PARSEABLE_BYTES = 10 * 1024 * 1024;

    // Element bodies whose content is not human-readable text and must be dropped entirely.
    private static final Pattern SCRIPT_OR_STYLE =
            Pattern.compile("<(script|style)\\b[^>]*>.*?</\\1>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // HTML comments.
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    // The <title>...</title> metadata element.
    private static final Pattern TITLE =
            Pattern.compile("<title\\b[^>]*>(.*?)</title>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // An <a ... href="..."> / href='...' / href=bare outbound link.
    private static final Pattern ANCHOR_HREF =
            Pattern.compile("<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Any remaining markup tag.
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);

    private final Set<String> supportedContentTypes;
    private final int maxParseableBytes;

    /**
     * Content-hash index (Req 4.6): maps a normalized-content hash to the lexicographically
     * smallest Canonical_URL registered for that hash. Backs {@link #resolveDuplicate}.
     */
    private final Map<String, String> canonicalByContentHash = new HashMap<>();

    /** Creates a Parser with the default supported content types and max parseable size. */
    public Parser() {
        this(DEFAULT_SUPPORTED_CONTENT_TYPES, DEFAULT_MAX_PARSEABLE_BYTES);
    }

    /**
     * Creates a Parser with explicit configuration.
     *
     * @param supportedContentTypes the set of content types eligible for parsing (Req 4.4);
     *     compared case-insensitively against the record's declared type (parameters stripped)
     * @param maxParseableBytes the maximum raw-body size in bytes eligible for parsing (Req 4.5)
     */
    public Parser(Set<String> supportedContentTypes, int maxParseableBytes) {
        Objects.requireNonNull(supportedContentTypes, "supportedContentTypes must not be null");
        if (maxParseableBytes < 0) {
            throw new IllegalArgumentException("maxParseableBytes must be non-negative");
        }
        // Normalize the supported set to lower case for case-insensitive comparison.
        Set<String> normalized = new LinkedHashSet<>();
        for (String type : supportedContentTypes) {
            if (type != null) {
                normalized.add(type.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.supportedContentTypes = Set.copyOf(normalized);
        this.maxParseableBytes = maxParseableBytes;
    }

    /**
     * Parse a raw {@link ContentRecord} into a {@link ParsedDocument} (Req 4.1, 4.2, 4.3).
     *
     * <p>Before extraction, the declared content type is checked against the supported set
     * (Req 4.4) and the raw-body size is checked against the maximum parseable size (Req 4.5).
     * When either check fails, a parse-failure {@link ParsedDocument} is returned with empty
     * text/links, a {@code null} title, and an empty content hash so the document is skipped for
     * indexing.
     *
     * @param raw the raw fetched document; must not be {@code null}
     * @return the parsed document, always non-null, carrying the appropriate {@link ParseStatus}
     * @throws NullPointerException if {@code raw} is {@code null}
     */
    public ParsedDocument parse(ContentRecord raw) {
        Objects.requireNonNull(raw, "raw ContentRecord must not be null");

        // Req 4.4: unsupported content type -> parse failure, skip indexing.
        if (!isSupportedContentType(raw.contentType())) {
            return failure(raw, ParseStatus.PARSE_FAILED_UNSUPPORTED_TYPE);
        }

        // Req 4.5: too-large document -> parse failure, skip indexing.
        if (raw.rawBody().length > maxParseableBytes) {
            return failure(raw, ParseStatus.PARSE_FAILED_TOO_LARGE);
        }

        String html = new String(raw.rawBody(), StandardCharsets.UTF_8);

        // Req 4.2: extract title metadata and outbound links from the original markup.
        String title = extractTitle(html);
        List<String> outboundLinks = extractLinks(html);

        // Req 4.1: extract human-readable text with markup, scripts, and styles removed.
        String humanText = extractText(html);

        // Req 4.3: normalize via the shared Normalizer (single source of truth).
        List<Token> normalizedTokens = Normalizer.normalize(humanText);

        // Req 4.6: hash over the normalized content for duplicate detection.
        String contentHash = computeContentHash(normalizedTokens);

        return new ParsedDocument(
                null,
                raw.canonicalUrl(),
                title,
                normalizedTokens,
                outboundLinks,
                contentHash,
                ParseStatus.PARSED);
    }

    /**
     * Register {@code contentHash} for {@code canonicalUrl} and return the Canonical_URL that
     * represents the duplicate set: the lexicographically smallest Canonical_URL seen for that
     * hash (Req 4.6).
     *
     * <p>Documents that produce identical normalized content share a {@code contentHash}, so
     * repeatedly calling this method for the same hash with different URLs always converges on
     * (and returns) the smallest URL among them, independent of registration order.
     *
     * @param contentHash the normalized-content hash from {@link ParsedDocument#contentHash()}
     * @param canonicalUrl the Canonical_URL of the document producing that content
     * @return the lexicographically smallest Canonical_URL registered for {@code contentHash}
     * @throws NullPointerException if either argument is {@code null}
     */
    public String resolveDuplicate(String contentHash, String canonicalUrl) {
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");

        String current = canonicalByContentHash.get(contentHash);
        if (current == null || canonicalUrl.compareTo(current) < 0) {
            current = canonicalUrl;
            canonicalByContentHash.put(contentHash, current);
        }
        return current;
    }

    // ---------------------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------------------

    private boolean isSupportedContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        // Strip any parameters (e.g. "text/html; charset=utf-8") and compare case-insensitively.
        String mediaType = contentType;
        int semicolon = mediaType.indexOf(';');
        if (semicolon >= 0) {
            mediaType = mediaType.substring(0, semicolon);
        }
        return supportedContentTypes.contains(mediaType.trim().toLowerCase(Locale.ROOT));
    }

    private ParsedDocument failure(ContentRecord raw, ParseStatus status) {
        return new ParsedDocument(
                null,
                raw.canonicalUrl(),
                null,
                List.of(),
                List.of(),
                "",
                status);
    }

    /** Extract the {@code <title>} text, decoding entities and collapsing whitespace; null if absent. */
    private static String extractTitle(String html) {
        Matcher m = TITLE.matcher(html);
        if (!m.find()) {
            return null;
        }
        String raw = ANY_TAG.matcher(m.group(1)).replaceAll(" ");
        String title = collapseWhitespace(decodeEntities(raw));
        return title.isEmpty() ? null : title;
    }

    /**
     * Extract outbound link targets from anchor {@code href} attributes, in document order,
     * de-duplicated while preserving first-seen order (Req 4.2).
     */
    private static List<String> extractLinks(String html) {
        Set<String> ordered = new LinkedHashSet<>();
        Matcher m = ANCHOR_HREF.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            if (href == null) {
                href = m.group(2);
            }
            if (href == null) {
                href = m.group(3);
            }
            if (href == null) {
                continue;
            }
            String link = decodeEntities(href).trim();
            if (!link.isEmpty()) {
                ordered.add(link);
            }
        }
        return new ArrayList<>(ordered);
    }

    /**
     * Extract human-readable text: drop script/style bodies and comments, replace remaining tags
     * with a space separator, then decode entities (Req 4.1). Normalization is applied later by
     * the shared {@link Normalizer}.
     */
    private static String extractText(String html) {
        String stripped = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        stripped = COMMENT.matcher(stripped).replaceAll(" ");
        stripped = ANY_TAG.matcher(stripped).replaceAll(" ");
        return decodeEntities(stripped);
    }

    /** Decode a minimal, deterministic set of common HTML entities. */
    private static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                // Ampersand is decoded last so it does not re-open the sequences above.
                .replace("&amp;", "&");
    }

    private static String collapseWhitespace(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    /**
     * Compute a stable SHA-256 hash over the normalized token sequence (Req 4.6). Tokens are
     * joined with a unit-separator delimiter so that token boundaries are part of the hashed
     * content, making the hash a faithful fingerprint of the normalized text.
     */
    private static String computeContentHash(List<Token> tokens) {
        StringBuilder joined = new StringBuilder();
        for (Token token : tokens) {
            joined.append(token.text()).append('\u001f');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every conformant JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
