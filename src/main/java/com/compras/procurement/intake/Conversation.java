package com.compras.procurement.intake;

import com.compras.procurement.llm.AgentMessage;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * One intake conversation. It keeps two views of the same dialogue:
 * {@code turns} is what the employee sees; {@code agentMessages} is what the model sees
 * (including tool calls and tool results), replayed on every step like a coding agent's history.
 */
@Document("conversations")
public class Conversation {

    public enum Status { ACTIVE, AWAITING_ANSWER, AWAITING_CONFIRMATION, SUBMITTED, FULFILLED_INTERNAL, CANCELLED }

    /** role: "user", "assistant" or "tool" (a one-line trace of a tool call, shown greyed out). */
    public record Turn(String role, String text, Instant at) {
    }

    public record PlanItem(String step, String status) {
    }

    /** A request (or internal use) the employee confirmed in this conversation. */
    public record Submitted(String requestId, String number, String title, String kind, Instant at) {
    }

    @Id
    private String id;
    private String employeeId;
    private Status status = Status.ACTIVE;
    private List<Turn> turns = new ArrayList<>();
    private List<AgentMessage> agentMessages = new ArrayList<>();
    private List<PlanItem> plan = new ArrayList<>();
    private String planExplanation;
    /** The tool call that is waiting for the employee (ask_user or a proposal). */
    private String pendingToolCallId;
    private List<AgentQuestion> pendingQuestions = new ArrayList<>();
    private Proposal proposal;
    private List<WebSource> sources = new ArrayList<>();
    /** Budget verdicts already obtained, by "department|amount" (verdicts only: no budget figures). */
    private Map<String, String> budgetChecks = new LinkedHashMap<>();
    private String requestId;
    /** Everything confirmed in this conversation: one conversation can produce several requests. */
    private List<Submitted> submitted = new ArrayList<>();
    /** Strategic routes the agent was told about, by reasons key: the employee's turn count at that moment. */
    private Map<String, Integer> strategicGates = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    protected Conversation() {
    }

    public Conversation(String employeeId, Instant now) {
        this.employeeId = employeeId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void say(String role, String text, Instant at) {
        turns.add(new Turn(role, text, at));
        updatedAt = at;
    }

    public void addAgentMessage(AgentMessage message) {
        agentMessages.add(message);
    }

    public void waitForAnswers(String toolCallId, List<AgentQuestion> questions) {
        this.pendingToolCallId = toolCallId;
        this.pendingQuestions = new ArrayList<>(questions);
        this.status = Status.AWAITING_ANSWER; // an unconfirmed proposal card stays on screen
    }

    public void waitForConfirmation(String toolCallId, Proposal proposal) {
        this.pendingToolCallId = toolCallId;
        this.proposal = proposal;
        this.pendingQuestions = new ArrayList<>();
        this.status = Status.AWAITING_CONFIRMATION;
    }

    /**
     * Clears what the employee was asked (the tool call is answered now); returns that call's id, or null when
     * nothing was pending. An unconfirmed proposal card is kept: see {@link #clearProposal()}.
     */
    public String resolvePending() {
        String id = pendingToolCallId;
        pendingToolCallId = null;
        pendingQuestions = new ArrayList<>();
        status = Status.ACTIVE;
        return id;
    }

    /** The proposal card is gone: confirmed, rejected or replaced. */
    public void clearProposal() {
        proposal = null;
    }

    /** True when the pending tool call is the proposal itself (not a question asked after it). */
    public boolean proposalCallPending() {
        return pendingToolCallId != null && proposal != null && getPendingQuestions().isEmpty();
    }

    public void addSubmitted(Submitted s) {
        if (submitted == null) {
            submitted = new ArrayList<>();
        }
        submitted.add(s);
        if (s.requestId() != null) {
            requestId = s.requestId();
        }
    }

    public List<Submitted> getSubmitted() { return submitted == null ? List.of() : submitted; }

    public long userTurns() {
        return turns.stream().filter(t -> "user".equals(t.role())).count();
    }

    public Integer strategicGate(String key) {
        return strategicGates == null ? null : strategicGates.get(key);
    }

    public void openStrategicGate(String key) {
        if (strategicGates == null) {
            strategicGates = new LinkedHashMap<>();
        }
        strategicGates.putIfAbsent(key, (int) userTurns());
    }

    public boolean isOpen() {
        return status == Status.ACTIVE || status == Status.AWAITING_ANSWER || status == Status.AWAITING_CONFIRMATION;
    }

    /** Everything the employee typed, verbatim. */
    public String originalWords() {
        return String.join("\n", turns.stream().filter(t -> "user".equals(t.role())).map(Turn::text).toList());
    }

    public void setPlan(List<PlanItem> plan, String explanation) {
        this.plan = new ArrayList<>(plan);
        this.planExplanation = explanation;
    }

    public void finish(Status status, String requestId) {
        this.status = status;
        this.requestId = requestId;
        this.pendingToolCallId = null;
        this.pendingQuestions = new ArrayList<>();
    }

    public String getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public List<Turn> getTurns() { return turns; }
    public List<AgentMessage> getAgentMessages() { return agentMessages; }
    public List<PlanItem> getPlan() { return plan == null ? List.of() : plan; }
    public String getPlanExplanation() { return planExplanation; }
    public String getPendingToolCallId() { return pendingToolCallId; }
    public List<AgentQuestion> getPendingQuestions() { return pendingQuestions == null ? List.of() : pendingQuestions; }
    public Proposal getProposal() { return proposal; }
    public Map<String, String> getBudgetChecks() { return budgetChecks == null ? Map.of() : budgetChecks; }

    public void recordBudgetCheck(String key, String verdict) {
        if (budgetChecks == null) {
            budgetChecks = new LinkedHashMap<>();
        }
        budgetChecks.put(key, verdict);
    }

    public List<WebSource> getSources() { return sources == null ? List.of() : sources; }

    public void addSource(String url, String title, String via, Instant at) {
        if (sources == null) {
            sources = new ArrayList<>();
        }
        if (url != null && !url.isBlank() && !hasSource(url)) {
            sources.add(new WebSource(url, title, via, at));
        }
    }

    public boolean hasSource(String url) {
        String key = WebSource.normalize(url);
        return getSources().stream().anyMatch(s -> WebSource.normalize(s.url()).equals(key));
    }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
