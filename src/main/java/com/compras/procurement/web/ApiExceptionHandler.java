package com.compras.procurement.web;

import com.compras.procurement.llm.LlmUnavailableException;
import com.compras.procurement.shared.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Turns exceptions into JSON errors the UI can show. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(com.compras.procurement.shared.ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(com.compras.procurement.shared.ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Malformed JSON or a value of the wrong type (e.g. "€470" where a number is expected). */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        String detail = e.getMostSpecificCause() == null ? "" : e.getMostSpecificCause().getMessage();
        String field = detail == null ? null : detail.replaceAll("(?s).*through reference chain: .*\\[\"([^\"]+)\"\\].*", "$1");
        return error(HttpStatus.BAD_REQUEST, "A value could not be read"
                + (field != null && !field.equals(detail) ? " (" + field + ")" : "")
                + ": numbers must be plain numbers, e.g. 470.50.");
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> concurrent(org.springframework.dao.OptimisticLockingFailureException e) {
        return error(HttpStatus.CONFLICT, "Someone else changed this at the same time. Reload and check again.");
    }

    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<Map<String, String>> llm(LlmUnavailableException e) {
        log.warn("Model unavailable: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
