package com.compras.procurement.policy;

import com.compras.procurement.approval.ApprovalPolicy;
import com.compras.procurement.flow.RequestFacts;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The checks a policy version must pass before it is published, since a rule that is data can be changed by
 * mistake: (1) the structural guarantees, (2) the version's own standard cases, (3) a replay of every past request
 * under the new rules, listing each one whose outcome would differ. Nothing is changed by a replay.
 */
@Service
public class PolicySimulator {

    private static final String SAMPLE_CHANNEL = "demo-loader";

    public record Snapshot(String route, List<String> reasons, Map<String, List<String>> approvers, Integer quotes,
                           List<String> steps) {
    }

    public record Change(String requestId, String number, String title, String status, String judgedUnder,
                         Snapshot before, Snapshot after, List<String> differences) {
    }

    /**
     * @param problems    structural problems: the version cannot be published
     * @param failedCases standard cases the rules get wrong: the version cannot be published
     * @param replay      past requests whose outcome would differ (for the publisher to read and accept)
     */
    public record Report(String version, String comparedWith, List<String> problems, List<String> failedCases,
                         int casesRun, int requestsReplayed, List<Change> replay, boolean publishable) {
    }

    private final RequestService requests;
    private final PolicySource policies;
    private final PolicyRouter router;
    private final ApprovalPolicy approvals;
    private final RequestFacts facts;

    public PolicySimulator(RequestService requests, PolicySource policies, PolicyRouter router,
                           ApprovalPolicy approvals, RequestFacts facts) {
        this.requests = requests;
        this.policies = policies;
        this.router = router;
        this.approvals = approvals;
        this.facts = facts;
    }

    public Report check(PolicySet candidate) {
        List<String> problems = PolicyValidator.problems(candidate);
        if (!problems.isEmpty()) {
            return new Report(candidate.version(), policies.active().version(), problems, List.of(), 0, 0, List.of(),
                    false);
        }
        List<String> failed = PolicyValidator.failedCases(candidate, router, approvals);
        List<PurchaseRequest> past = requests.all().stream()
                .filter(r -> !SAMPLE_CHANNEL.equals(r.getChannel())).toList();
        List<Change> changes = new ArrayList<>();
        for (PurchaseRequest r : past) {
            PolicySet judgedUnder = policies.version(r.getPolicyVersion());
            Snapshot before = evaluate(judgedUnder, r);
            Snapshot after = evaluate(candidate, r);
            List<String> differences = differences(before, after);
            if (!differences.isEmpty()) {
                changes.add(new Change(r.getId(), r.getNumber(), r.getTitle(), r.getStatus().name(),
                        judgedUnder.version(), before, after, differences));
            }
        }
        return new Report(candidate.version(), policies.active().version(), List.of(), failed,
                candidate.cases().size(), past.size(), changes, failed.isEmpty());
    }

    /** What the rules of this version say about a request: route, approvers per stage, quotes. */
    Snapshot evaluate(PolicySet policy, PurchaseRequest r) {
        Map<String, Object> atSubmission = Facts.of(r.isNonStandard(), !r.isNonStandard(), facts.frameworkCategory(r),
                r.isNonStandard() ? BigDecimal.ZERO : r.matrixValue(), r.getCategoryCode(), r.chargeDepartment(), false,
                r.getAttributes());
        RouteDecision decision = router.route(policy, atSubmission);
        if (!decision.strategic() && r.getSourced() != null) {
            RouteDecision priced = router.route(policy, facts.routingAfterAward(r));
            decision = priced.strategic() ? priced : decision;
        }
        boolean over = "OVER_BUDGET".equals(r.getBudgetCheck());
        Map<String, List<String>> approvers = new LinkedHashMap<>();
        List<String> steps = new ArrayList<>();
        for (PolicySet.StepDef step : policy.steps(decision.route())) {
            steps.add(step.type() + (step.stage() != null ? " " + step.stage() : step.owner() != null ? " " + step.owner() : ""));
            if ("APPROVAL".equals(step.type())) {
                approvers.put(step.stage(), approvals.roles(policy, step.stage(),
                                facts.approval(r, step.stage(), over, List.of()).facts()).stream()
                        .map(n -> n.role().name()).toList());
            }
        }
        Integer quotes = r.getSourced() == null ? null : router.quotesRequired(policy, r.getSourced().total());
        return new Snapshot(decision.route(), decision.reasons(), approvers, quotes, steps);
    }

    private static List<String> differences(Snapshot a, Snapshot b) {
        List<String> out = new ArrayList<>();
        if (!Objects.equals(a.route(), b.route())) {
            out.add("Route: " + a.route() + " → " + b.route()
                    + (b.reasons().isEmpty() ? "" : " (" + String.join("; ", b.reasons()) + ")"));
        } else if (!Objects.equals(a.steps(), b.steps())) {
            out.add("Steps: " + String.join(" › ", a.steps()) + " → " + String.join(" › ", b.steps()));
        }
        java.util.Set<String> stages = new java.util.LinkedHashSet<>(a.approvers().keySet());
        stages.addAll(b.approvers().keySet());
        for (String stage : stages) {
            List<String> x = a.approvers().get(stage);
            List<String> y = b.approvers().get(stage);
            if (!Objects.equals(x, y)) {
                out.add("Approvers " + stage + ": " + show(x) + " → " + show(y));
            }
        }
        if (!Objects.equals(a.quotes(), b.quotes())) {
            out.add("Quotes required: " + a.quotes() + " → " + b.quotes());
        }
        return out;
    }

    private static String show(List<String> roles) {
        return roles == null ? "(no such stage)" : roles.isEmpty() ? "nobody (auto-approved)" : String.join(", ", roles);
    }
}
