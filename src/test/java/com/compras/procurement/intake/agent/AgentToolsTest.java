package com.compras.procurement.intake.agent;

import com.compras.procurement.audit.AuditService;
import com.compras.procurement.intake.Conversation;
import com.compras.procurement.intake.tools.AskUserTool;
import com.compras.procurement.intake.tools.UpdatePlanTool;
import com.compras.procurement.llm.ToolCall;
import com.compras.procurement.matching.Requirement;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The feedback loop: invalid tool calls come back to the model as errors instead of being silently fixed. */
class AgentToolsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> audited = new ArrayList<>();
    private final AuditService audit = new AuditService(null, json, Clock.systemUTC()) {
        @Override
        public void record(String type, String aggregateId, Map<String, Object> payload) {
            audited.add(type + ":" + payload.get("tool") + ":" + payload.get("ok"));
        }
    };
    private final ToolRegistry registry = new ToolRegistry(List.of(new AskUserTool(), new UpdatePlanTool()), json, audit);
    private final ToolContext context = new ToolContext(new Conversation("E001", Instant.now()), null,
            LocalDate.of(2026, 11, 13));

    @Test
    void invalidArgumentsAreReturnedToTheModelWithEveryProblem() {
        ToolRegistry.Executed ex = registry.execute(new ToolCall("c1", "ask_user",
                "{\"questions\":[{\"id\":\"q\",\"question\":\"When?\",\"options\":[{\"label\":\"Other\"}]}]}"), context);
        assertFalse(ex.ok());
        assertTrue(ex.output().contains("questions[0] (\\\"When?\\\") has 0 usable option(s)"));
        assertTrue(ex.output().contains("other_label"));
        assertEquals(List.of("ToolCall:ask_user:false"), audited);
    }

    @Test
    void aRealOptionStartingWithOtherIsKeptAndABareOtherIsDropped() {
        // the exact call that looped 14 times in testing: "其他部门" is a real answer, not the free-text "Other"
        ToolRegistry.Executed ex = registry.execute(new ToolCall("c6", "ask_user", "{\"questions\":["
                + "{\"id\":\"who_for\",\"question\":\"给哪个部门用？\",\"options\":[{\"label\":\"运营部门\"},"
                + "{\"label\":\"其他部门\"},{\"label\":\"其他\"}],\"other_label\":\"请说明部门\"}]}"), context);
        assertTrue(ex.ok(), ex.output());
        assertEquals(List.of("运营部门", "其他部门"),
                ex.result().questions().get(0).options().stream().map(o -> o.label()).toList());
    }

    @Test
    void brokenJsonAndUnknownToolsAreAlsoFeedbackNotCrashes() {
        assertTrue(registry.execute(new ToolCall("c2", "ask_user", "{not json"), context).output()
                .contains("not valid JSON"));
        assertTrue(registry.execute(new ToolCall("c3", "buy_now", "{}"), context).output()
                .contains("Unknown tool 'buy_now'"));
    }

    @Test
    void validQuestionsPauseTheLoopForTheEmployee() {
        ToolRegistry.Executed ex = registry.execute(new ToolCall("c4", "ask_user",
                "{\"questions\":[{\"id\":\"use\",\"question\":\"Daily device or compute?\","
                        + "\"options\":[{\"label\":\"Daily device\"},{\"label\":\"Compute access\"}],\"other_label\":\"其他理由\"}]}"), context);
        assertTrue(ex.ok());
        assertTrue(ex.result().pauses());
        assertEquals("use", ex.result().questions().get(0).id());
        assertEquals("其他理由", ex.result().questions().get(0).otherLabel());
    }

    @Test
    void planIsStoredOnTheConversation() {
        registry.execute(new ToolCall("c5", "update_plan", "{\"plan\":[{\"step\":\"Check resources\","
                + "\"status\":\"in_progress\"},{\"step\":\"Check catalog\",\"status\":\"pending\"}]}"), context);
        assertEquals(2, context.conversation().getPlan().size());
    }

    @Test
    void requirementMeaningIsKeptAsWrittenIncludingNegation() throws Exception {
        List<String> problems = new ArrayList<>();
        List<Requirement> r = ArgParser.requirements(json.readTree(
                "[{\"description\":\"Does not need Metal\",\"attribute\":\"metalSupported\",\"operator\":\"neq\","
                        + "\"value\":true,\"strength\":\"preference\"}]"), problems);
        assertTrue(problems.isEmpty());
        assertEquals("neq", r.get(0).operator());
        assertEquals(true, r.get(0).value());
        assertEquals("preference", r.get(0).strength());
    }
}
