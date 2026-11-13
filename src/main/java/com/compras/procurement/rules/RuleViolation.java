package com.compras.procurement.rules;

/** A finding from a rule. ERROR blocks submission; WARNING is shown and passed on to the approver. */
public record RuleViolation(String field, Severity severity, String message) {

    public enum Severity { ERROR, WARNING }

    public static RuleViolation error(String field, String message) {
        return new RuleViolation(field, Severity.ERROR, message);
    }

    public static RuleViolation warning(String field, String message) {
        return new RuleViolation(field, Severity.WARNING, message);
    }

    public boolean blocking() {
        return severity == Severity.ERROR;
    }
}
