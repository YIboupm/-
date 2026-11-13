package com.compras.procurement.intake;

import com.compras.procurement.matching.Requirement;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One intake dialogue and the draft request it is building. */
@Document("conversations")
public class Conversation {

    public enum Status { COLLECTING, AWAITING_CONFIRMATION, SUBMITTED, HANDED_OFF, CANCELLED }

    /** Where a field value came from: said by the employee, inferred by the model, or known by the system. */
    public enum Source { STATED, INFERRED, CONTEXT }

    public record FieldValue(Object value, Source source) {
    }

    public record Turn(String role, String text, Instant at) {
    }

    @Id
    private String id;
    private String employeeId;
    private Status status = Status.COLLECTING;
    private String categoryCode;
    private Map<String, FieldValue> fields = new LinkedHashMap<>();
    private List<Turn> turns = new ArrayList<>();
    private List<String> quickReplies = new ArrayList<>();
    private List<String> fieldProblems = new ArrayList<>();
    private List<Requirement> requirements = new ArrayList<>();
    /** Open multiple-choice questions shown to the employee. */
    private List<Question> pendingQuestions = new ArrayList<>();
    private String rationale;
    private int clarificationRounds;
    private String handoffReason;
    private String requestId;
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

    /** Plain values (no provenance), as rules and resolvers expect them. */
    public Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        fields.forEach((k, v) -> values.put(k, v.value()));
        return values;
    }

    public Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        fields.forEach((k, v) -> sources.put(k, v.source().name()));
        return sources;
    }

    public void put(String field, Object value, Source source) {
        fields.put(field, new FieldValue(value, source));
    }

    public void startCategory(String code) {
        this.categoryCode = code;
        this.fields.clear();
        this.requirements = new ArrayList<>();
        this.rationale = null;
        this.clarificationRounds = 0;
    }

    public int nextClarificationRound() {
        return ++clarificationRounds;
    }

    public void handOff(String reason) {
        this.status = Status.HANDED_OFF;
        this.handoffReason = reason;
    }

    public void submitted(String requestId) {
        this.status = Status.SUBMITTED;
        this.requestId = requestId;
    }

    public boolean isOpen() {
        return status == Status.COLLECTING || status == Status.AWAITING_CONFIRMATION;
    }

    public String getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getCategoryCode() { return categoryCode; }
    public Map<String, FieldValue> getFields() { return fields; }
    public List<Turn> getTurns() { return turns; }
    public List<String> getQuickReplies() { return quickReplies; }
    public void setQuickReplies(List<String> quickReplies) { this.quickReplies = quickReplies == null ? new ArrayList<>() : new ArrayList<>(quickReplies); }
    public List<String> getFieldProblems() { return fieldProblems; }
    public void setFieldProblems(List<String> problems) { this.fieldProblems = new ArrayList<>(problems); }
    public List<Requirement> getRequirements() { return requirements == null ? List.of() : requirements; }
    public void setRequirements(List<Requirement> list) { this.requirements = list == null ? new ArrayList<>() : new ArrayList<>(list); }

    /** Everything the employee typed, verbatim, in order. Stored with the request so nothing is lost to a summary. */
    public String originalWords() {
        return String.join("\n", turns.stream().filter(t -> "user".equals(t.role())).map(Turn::text).toList());
    }
    public List<Question> getPendingQuestions() { return pendingQuestions == null ? List.of() : pendingQuestions; }
    public void setPendingQuestions(List<Question> qs) { this.pendingQuestions = qs == null ? new ArrayList<>() : new ArrayList<>(qs); }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public int getClarificationRounds() { return clarificationRounds; }
    public String getHandoffReason() { return handoffReason; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
