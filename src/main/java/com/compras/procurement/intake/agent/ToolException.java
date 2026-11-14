package com.compras.procurement.intake.agent;

import java.util.List;

/**
 * A tool refused the call. The message and the problems go back to the model as the tool result,
 * so it can correct itself and try again (like a compiler error for a coding agent).
 */
public class ToolException extends RuntimeException {

    private final List<String> problems;

    public ToolException(String message) {
        this(message, List.of());
    }

    public ToolException(String message, List<String> problems) {
        super(message);
        this.problems = problems;
    }

    public List<String> problems() {
        return problems;
    }
}
