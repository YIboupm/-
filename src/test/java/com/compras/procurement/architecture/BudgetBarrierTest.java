package com.compras.procurement.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The information barrier, enforced on the code: everything the AI agent can reach (the intake module and its
 * web endpoint) may use only BudgetGate and BudgetVerdict from the budget module. If someone adds a tool that
 * reads balances, this test fails the build — whatever the prompt says.
 */
class BudgetBarrierTest {

    private static final Path MAIN = Path.of("src/main/java/com/compras/procurement");
    private static final List<String> ALLOWED = List.of("BudgetGate", "BudgetVerdict");
    private static final Pattern BUDGET_REF = Pattern.compile("com\\.compras\\.procurement\\.(budget|approval)\\.(\\w+)");

    @Test
    void requesterFacingCodeSeesVerdictsOnly() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN.resolve("intake"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        files.add(MAIN.resolve("web/IntakeController.java"));
        List<String> violations = new ArrayList<>();
        for (Path f : files) {
            String code = Files.readString(f);
            Matcher m = BUDGET_REF.matcher(code);
            while (m.find()) {
                if (m.group(1).equals("approval") || !ALLOWED.contains(m.group(2))) {
                    violations.add(MAIN.relativize(f) + " uses " + m.group());
                }
            }
            for (String forbidden : List.of("BudgetService", "BudgetAccount", "BudgetEntry", "BudgetStore",
                    "BudgetAccess")) {
                if (code.contains(forbidden)) {
                    violations.add(MAIN.relativize(f) + " mentions " + forbidden);
                }
            }
        }
        assertTrue(violations.isEmpty(), "Budget figures must not reach the agent: " + violations);
        assertTrue(files.size() > 10, "scanned " + files.size() + " files");
    }
}
