package com.searchengine.common;

/**
 * Reason a fetch permanently failed (design §1.3).
 *
 * <ul>
 *   <li>{@link #MAX_RETRIES} - URL reached the configured maximum retry count (Req 3.3).</li>
 *   <li>{@link #REDIRECT_LIMIT} - redirect chain exceeded the configured maximum (Req 3.5).</li>
 *   <li>{@link #CLIENT_ERROR_4XX} - the host returned an HTTP 4xx response (Req 3.6).</li>
 * </ul>
 */
public enum FailReason {
    MAX_RETRIES,
    REDIRECT_LIMIT,
    CLIENT_ERROR_4XX
}
