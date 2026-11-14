package com.compras.procurement.research;

/** One web search hit. {@code authority}: 0-4, 3+ = highly authoritative, -1 unknown. */
public record WebResult(String title, String url, String snippet, String summary, String siteName, String date,
                        int authority) {
}
