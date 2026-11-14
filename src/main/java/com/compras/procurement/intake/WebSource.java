package com.compras.procurement.intake;

import java.time.Instant;

/** A web page the agent saw in this conversation (search result or fetched page). Claims must cite these. */
public record WebSource(String url, String title, String via, Instant seenAt) {

    /** Comparable form of a URL: no fragment, no trailing slash, lower-case scheme and host. */
    public static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) {
            u = u.substring(0, hash);
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        int scheme = u.indexOf("://");
        int pathStart = scheme < 0 ? -1 : u.indexOf('/', scheme + 3);
        String head = pathStart < 0 ? u : u.substring(0, pathStart);
        String tail = pathStart < 0 ? "" : u.substring(pathStart);
        return head.toLowerCase().replaceFirst("^http://", "https://").replace("://www.", "://") + tail;
    }
}
