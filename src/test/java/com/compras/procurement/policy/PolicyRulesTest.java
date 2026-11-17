package com.compras.procurement.policy;

import com.compras.procurement.approval.ApprovalPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The policy as data: the shipped version is sound, and a changed version changes the outcome without code. */
class PolicyRulesTest {

    private final PolicySet shipped = PolicyFiles.builtin();
    private final PolicyRouter router = new PolicyRouter(() -> shipped);
    private final ApprovalPolicy approvals = new ApprovalPolicy(() -> shipped);

    private static PolicySet with(PolicySet p, List<PolicySet.RoutingRule> routing,
                                  Map<String, List<PolicySet.StepDef>> routes, List<PolicySet.ApprovalRule> rules) {
        return new PolicySet("test", PolicySet.DRAFT, null, null, p.version(), null, null, p.parameters(),
                routing == null ? p.routing() : routing, rules == null ? p.approvals() : rules, p.autoApprovals(),
                p.quotes(), routes == null ? p.routes() : routes, p.cases(), p.assets());
    }

    @Test
    void theShippedVersionIsValidAndPassesItsOwnCases() {
        assertEquals(List.of(), PolicyValidator.problems(shipped));
        assertEquals(List.of(), PolicyValidator.failedCases(shipped, router, approvals));
        assertTrue(shipped.cases().size() >= 10);
    }

    @Test
    void aVersionSurvivesTheJsonRoundTripTheEditorUses() {
        String json = PolicyFiles.json(shipped);
        assertEquals(json, PolicyFiles.json(PolicyFiles.parse(json)));
    }

    @Test
    void changingAThresholdIsADataChange() {
        List<PolicySet.RoutingRule> routing = new ArrayList<>(shipped.routing());
        PolicySet.RoutingRule value = routing.get(0);
        assertEquals("R-VALUE", value.id());
        routing.set(0, new PolicySet.RoutingRule(value.id(), value.section(),
                List.of(new PolicySet.Condition(Facts.CONTRACT_VALUE, "gt", 50000)), value.route(), value.reason()));
        PolicySet raised = with(shipped, routing, null, null);
        PolicyRouter.Input thirty = new PolicyRouter.Input(false, false, new BigDecimal("30000"), Map.of());
        assertEquals("STRATEGIC", router.route(shipped, thirty).route());
        assertEquals("STANDARD", router.route(raised, thirty).route());
        RouteDecision sixty = router.route(raised, new PolicyRouter.Input(false, false, new BigDecimal("60000"), Map.of()));
        assertEquals(List.of("Contract value €60,000 is over €50,000 (policy 7)"), sixty.reasons());
        // the version's own case "Over 25,000: strategic" now fails: it cannot be published until the case is updated
        assertEquals(1, PolicyValidator.failedCases(raised, router, approvals).size());
        // the split check watches whatever thresholds the rules use
        assertTrue(raised.valueThresholds().contains(new BigDecimal("50000")));
    }

    @Test
    void aNewStepAndItsApproversAreADataChange() {
        Map<String, List<PolicySet.StepDef>> routes = new LinkedHashMap<>(shipped.routes());
        List<PolicySet.StepDef> buyer = new ArrayList<>(routes.get("BUYER_SOURCED"));
        buyer.add(2, new PolicySet.StepDef("APPROVAL", "COMPLIANCE", null, "Compliance review"));
        routes.put("BUYER_SOURCED", buyer);
        PolicySet missingRule = with(shipped, null, routes, null);
        assertTrue(PolicyValidator.problems(missingRule).toString().contains("Stage COMPLIANCE"));
        List<PolicySet.ApprovalRule> rules = new ArrayList<>(shipped.approvals());
        rules.add(new PolicySet.ApprovalRule("A-COMPLIANCE", "9", "COMPLIANCE", List.of(), "IT_SECURITY",
                "Compliance review of a new supplier", null, null));
        PolicySet extended = with(shipped, null, routes, rules);
        assertEquals(List.of(), PolicyValidator.problems(extended));
        assertEquals(List.of("IT_SECURITY"), approvals.roles(extended, "COMPLIANCE", Map.of()).stream()
                .map(n -> n.role().name()).toList());
    }

    @Test
    void theGuaranteesAreNotConfigurable() {
        // a route that orders without reserving budget
        Map<String, List<PolicySet.StepDef>> routes = new LinkedHashMap<>(shipped.routes());
        routes.put("STANDARD", List.of(new PolicySet.StepDef("APPROVAL", "SPEND", null, null),
                new PolicySet.StepDef("FULFIL", null, null, null)));
        assertTrue(PolicyValidator.problems(with(shipped, null, routes, null)).toString().contains("BUDGET_RESERVE"));
        // a step type nobody implemented, a fact nobody collects, a role that does not exist
        routes = new LinkedHashMap<>(shipped.routes());
        routes.put("STANDARD", List.of(new PolicySet.StepDef("TELEPATHY", null, null, null)));
        assertTrue(PolicyValidator.problems(with(shipped, null, routes, null)).toString().contains("unknown step type"));
        List<PolicySet.RoutingRule> routing = new ArrayList<>(shipped.routing());
        routing.add(0, new PolicySet.RoutingRule("R-X", "7", List.of(new PolicySet.Condition("moonPhase", "eq", "full")),
                "STRATEGIC", "x"));
        assertTrue(PolicyValidator.problems(with(shipped, routing, null, null)).toString().contains("unknown fact"));
        List<PolicySet.ApprovalRule> rules = new ArrayList<>(shipped.approvals());
        rules.add(new PolicySet.ApprovalRule("A-X", "6", "SPEND", List.of(), "EMPEROR", "x", null, null));
        assertTrue(PolicyValidator.problems(with(shipped, null, null, rules)).toString().contains("unknown role"));
        // no rule left that knowingly accepts going over budget
        rules = shipped.approvals().stream().filter(r -> !r.budgetDecision()).toList();
        assertTrue(PolicyValidator.problems(with(shipped, null, null, rules)).toString().contains("coversOverBudget"));
        // every request must get a route
        routing = new ArrayList<>(shipped.routing());
        routing.remove(routing.size() - 1);
        assertTrue(PolicyValidator.problems(with(shipped, routing, null, null)).toString().contains("last routing rule"));
    }

    @Test
    void conditionsCompareNumbersTextAndLists() {
        Map<String, Object> facts = Map.of("contractValue", new BigDecimal("5000.00"), "category", "laptop",
                "soleSource", "true", "termMonths", 12);
        assertTrue(RuleEngine.matches(List.of(new PolicySet.Condition("contractValue", "gte", 5000),
                new PolicySet.Condition("contractValue", "lte", 5000.0),
                new PolicySet.Condition("category", "in", List.of("LAPTOP", "CLOUD")),
                new PolicySet.Condition("soleSource", "eq", true),
                new PolicySet.Condition("region", "missing", null),
                new PolicySet.Condition("region", "ne", "Spain"),
                new PolicySet.Condition("termMonths", "exists", null)), facts));
        assertTrue(!RuleEngine.matches(List.of(new PolicySet.Condition("contractValue", "gt", 5000)), facts));
        assertTrue(!RuleEngine.matches(List.of(new PolicySet.Condition("region", "notIn", List.of("Spain"))), facts));
    }
}
