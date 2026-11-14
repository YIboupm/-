package com.compras.procurement.research;

/** Web research could not be done (no key, no balance, site blocked, timeout...). */
public class WebResearchException extends RuntimeException {
    public WebResearchException(String message) {
        super(message);
    }
}
