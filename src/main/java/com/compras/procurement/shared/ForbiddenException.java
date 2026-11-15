package com.compras.procurement.shared;

/** The caller's role may not see or do this (e.g. budget figures outside finance). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
