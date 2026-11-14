package com.compras.procurement.intake.tools;

import com.compras.procurement.intake.Conversation;
import com.compras.procurement.intake.agent.AgentTool;
import com.compras.procurement.intake.agent.ArgParser;
import com.compras.procurement.intake.agent.Schemas;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolException;
import com.compras.procurement.intake.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The agent's own plan, shown to the employee. Same idea as Codex CLI's update_plan. */
@Component
public class UpdatePlanTool implements AgentTool {

    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");

    @Override
    public String name() {
        return "update_plan";
    }

    @Override
    public String description() {
        return "Record or update your plan for this request: the steps you intend to take (understand the need, "
                + "check company resources, check the catalog, ask the employee, propose ...). At most one step "
                + "can be in_progress. The employee sees the plan.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schemas.object(Schemas.props(
                "explanation", Schemas.string("Optional one-line reason for this update."),
                "plan", Schemas.array(Schemas.object(Schemas.props(
                        "step", Schemas.string("Short step text."),
                        "status", Schemas.enumOf("Step status.", "pending", "in_progress", "completed")),
                        "step", "status"), "The steps.")), "plan");
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        List<String> problems = new ArrayList<>();
        List<Conversation.PlanItem> plan = new ArrayList<>();
        int inProgress = 0;
        for (JsonNode item : args.path("plan")) {
            String step = ArgParser.text(item, "step");
            String status = ArgParser.text(item, "status");
            if (step == null || status == null || !STATUSES.contains(status)) {
                problems.add("Each plan item needs a 'step' and a 'status' in " + STATUSES + ".");
                continue;
            }
            inProgress += "in_progress".equals(status) ? 1 : 0;
            plan.add(new Conversation.PlanItem(step, status));
        }
        if (plan.isEmpty()) {
            problems.add("'plan' must contain at least one step.");
        }
        if (inProgress > 1) {
            problems.add("At most one step can be in_progress.");
        }
        if (!problems.isEmpty()) {
            throw new ToolException("The plan was not saved.", problems);
        }
        context.conversation().setPlan(plan, ArgParser.text(args, "explanation"));
        return ToolResult.of(Map.of("saved", true), "update_plan · " + plan.size() + " steps");
    }
}
