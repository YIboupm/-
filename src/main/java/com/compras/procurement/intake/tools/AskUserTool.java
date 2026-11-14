package com.compras.procurement.intake.tools;

import com.compras.procurement.intake.AgentQuestion;
import com.compras.procurement.intake.agent.AgentTool;
import com.compras.procurement.intake.agent.ArgParser;
import com.compras.procurement.intake.agent.Schemas;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolException;
import com.compras.procurement.intake.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Multiple-choice questions to the employee, modelled on Codex CLI's request_user_input. */
@Component
public class AskUserTool implements AgentTool {

    @Override
    public String name() {
        return "ask_user";
    }

    @Override
    public String description() {
        return "Ask the employee 1 to 3 multiple-choice questions and wait for the answers. Each question has 2 to 4 "
                + "options; put the recommended option first. Do not add an 'Other' option to the list: the interface "
                + "adds a free-text answer labelled with 'other_label'. Write questions, options and other_label in "
                + "the employee's language. Ask only what changes the outcome.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schemas.object(Schemas.props("questions", Schemas.array(Schemas.object(Schemas.props(
                "id", Schemas.string("Stable snake_case id, e.g. needed_by."),
                "question", Schemas.string("One short question."),
                "options", Schemas.array(Schemas.object(Schemas.props(
                        "label", Schemas.string("What the employee sees (a few words)."),
                        "value", Schemas.string("Optional machine value, e.g. 2026-12-01 or LAP-DES."),
                        "description", Schemas.string("Optional one-line consequence of choosing it.")),
                        "label"), "2 to 4 options."),
                "other_label", Schemas.string("Label of the free-text answer, in the employee's language and fitting "
                        + "the question, e.g. \"其他理由\", \"其他日期\", \"Another reason\".")),
                "id", "question", "options", "other_label"), "1 to 3 questions.")),
                "questions");
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        List<String> problems = new ArrayList<>();
        List<AgentQuestion> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        JsonNode qs = args.path("questions");
        if (!qs.isArray() || qs.isEmpty() || qs.size() > 3) {
            problems.add("Give 1 to 3 questions.");
        }
        int i = -1;
        for (JsonNode q : qs) {
            i++;
            String id = ArgParser.text(q, "id");
            String text = ArgParser.text(q, "question");
            String where = "questions[" + i + "]" + (text == null ? "" : " (\"" + text + "\")");
            if (id == null || !ids.add(id)) {
                problems.add(where + ": 'id' is missing or not unique.");
            }
            if (text == null) {
                problems.add(where + ": 'question' is required.");
            }
            String otherLabel = ArgParser.text(q, "other_label");
            if (otherLabel == null) {
                problems.add(where + ": 'other_label' is required (the free-text answer's label, in the "
                        + "employee's language).");
            }
            List<AgentQuestion.Option> options = new ArrayList<>();
            for (JsonNode o : q.path("options")) {
                String label = ArgParser.text(o, "label");
                if (label == null) {
                    problems.add(where + " has an option without a label.");
                } else if (!isGenericOther(label, otherLabel)) {
                    // a bare "Other" duplicates the free-text answer the interface adds: dropped, not an error.
                    // A real option that happens to start with "其他" (e.g. "其他部门") is kept.
                    options.add(new AgentQuestion.Option(label, ArgParser.text(o, "value"),
                            ArgParser.text(o, "description")));
                }
            }
            if (options.size() < 2 || options.size() > 4) {
                problems.add(where + " has " + options.size() + " usable option(s); give 2 to 4 concrete options "
                        + "(the free-text answer labelled other_label is added automatically and does not count).");
            }
            questions.add(new AgentQuestion(id, text, options, otherLabel));
        }
        if (!problems.isEmpty()) {
            throw new ToolException("The questions were not shown to the employee.", problems);
        }
        return ToolResult.ask(questions, "ask_user · " + questions.size() + " question(s)");
    }

    private static final java.util.Set<String> GENERIC_OTHER = java.util.Set.of("other", "others", "otro", "otros",
            "otra", "something else", "其他", "其它", "其他选项", "其他情况", "其他答案", "以上都不是");

    /** True for a catch-all "Other" option (or one equal to other_label), which the interface adds itself. */
    static boolean isGenericOther(String label, String otherLabel) {
        String key = label.trim().toLowerCase().replaceAll("[\\s:：.。…()（）/-]+", "");
        String other = otherLabel == null ? null : otherLabel.trim().toLowerCase().replaceAll("[\\s:：.。…()（）/-]+", "");
        return GENERIC_OTHER.contains(key) || key.equals(other);
    }
}
