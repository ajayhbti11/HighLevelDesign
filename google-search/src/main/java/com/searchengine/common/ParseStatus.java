package com.searchengine.common;

/**
 * ParseStatus: the outcome of parsing a raw Document (design §2, Req 4.4, 4.5).
 *
 * <ul>
 *   <li>{@link #PARSED} - the document was parsed successfully and is eligible for indexing.</li>
 *   <li>{@link #PARSE_FAILED_UNSUPPORTED_TYPE} - content type not in the supported set (Req 4.4).</li>
 *   <li>{@link #PARSE_FAILED_TOO_LARGE} - document exceeds the max parseable size (Req 4.5).</li>
 * </ul>
 */
public enum ParseStatus {
    PARSED,
    PARSE_FAILED_UNSUPPORTED_TYPE,
    PARSE_FAILED_TOO_LARGE
}
