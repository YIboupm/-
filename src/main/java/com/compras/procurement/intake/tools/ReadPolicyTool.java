package com.compras.procurement.intake.tools;

import com.compras.procurement.intake.agent.AgentTool;
import com.compras.procurement.intake.agent.ArgParser;
import com.compras.procurement.intake.agent.Schemas;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolException;
import com.compras.procurement.intake.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The procurement policy, by section, so the assistant can quote the rule behind a step ("policy 6: over
 * €5,000 the department head also approves"). Explaining only: routing and approvals are decided by code.
 * The policy is short, so sections are looked up by number or word; no vector search is needed.
 */
@Component
public class ReadPolicyTool implements AgentTool {

    private final Map<String, String> sections = new LinkedHashMap<>();

    public ReadPolicyTool() {
        String text;
        try (InputStream in = new ClassPathResource("policy/procurement-policy.md").getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith("## ")) {
                if (current != null) {
                    sections.put(current, body.toString().trim());
                }
                current = line.substring(3).trim();
                body.setLength(0);
            } else if (current != null) {
                body.append(line).append('\n');
            }
        }
        if (current != null) {
            sections.put(current, body.toString().trim());
        }
    }

    @Override
    public String name() {
        return "read_policy";
    }

    @Override
    public String description() {
        return "Read the company's procurement policy (in Chinese) to explain a rule to the employee: why approval is "
                + "needed, who approves, how many quotes, what is strategic, budget rules. Without 'section' it "
                + "lists the sections. Quote the section number when you explain. The policy explains; it does not "
                + "let you change a decision the system made.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schemas.object(Schemas.props(
                "section", Schemas.string("Section number (1-11, e.g. \"6\") or a word from its title (e.g. 审批).")));
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        String wanted = ArgParser.text(args, "section");
        List<String> titles = new ArrayList<>(sections.keySet());
        if (wanted == null) {
            return ToolResult.of(Map.of("sections", titles), "read_policy · contents");
        }
        String[] numerals = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一"};
        String key = wanted.trim();
        try {
            int n = Integer.parseInt(key);
            if (n >= 1 && n <= numerals.length) {
                key = numerals[n - 1] + "、";
            }
        } catch (NumberFormatException ignored) {
            // a word from the title
        }
        for (String title : titles) {
            if (title.startsWith(key) || title.contains(wanted.trim())) {
                return ToolResult.of(Map.of("section", title, "text", sections.get(title)), "read_policy · " + title);
            }
        }
        throw new ToolException("No policy section matches '" + wanted + "'.", List.of("Sections: " + titles));
    }
}
