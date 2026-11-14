package com.compras.procurement.request;

import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.rules.RuleViolation;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The standardised, storable purchase request: the output of intake and the input of
 * approval and consolidation. {@code attributes} keeps the category-specific fields as-is,
 * which is the part a document database handles well.
 */
@Document("purchase_requests")
public class PurchaseRequest {

    public record Requester(String employeeId, String name, String department, String costCenter, String site) {
    }

    public record Line(String lineId, String sku, String name, String categoryCode, int quantity,
                       BigDecimal estimatedUnitPrice, BigDecimal estimatedTotal,
                       String supplierId, String supplierName, int leadTimeDays,
                       Map<String, String> specs, String note) {
    }

    public record StatusChange(RequestStatus from, RequestStatus to, Instant at, String note) {
    }

    /** Who pays: may differ from the requester's own department ("I'm ordering this for IT"). */
    public record ChargeTo(String department, String costCenter) {
    }

    /** What a buyer actually sourced for a special request. */
    /**
     * What the buyer bought.
     *
     * @param chosenModel        the model the employee chose with the agent, if any
     * @param substituted        true when the buyer bought something else
     * @param substitutionReason why (e.g. "M4 discontinued; current model")
     * @param confirmations      the buyer's confirmation of each employee requirement for the bought item
     */
    public record SourcedItem(String description, String supplierName, int quantity, BigDecimal unitPrice,
                              BigDecimal total, String buyerNote, Instant sourcedAt, String chosenModel,
                              Boolean substituted, String substitutionReason,
                              List<BuyerApproval.Confirmation> confirmations) {
    }

    /** The model the employee chose (or the agent's first recommendation), as the buyer form shows it. */
    public String chosenModelLabel() {
        List<SuggestedModel> models = getSuggestedModels();
        if (models.isEmpty()) {
            return null;
        }
        SuggestedModel m = models.stream().filter(SuggestedModel::preferred).findFirst().orElse(models.get(0));
        return m.model() + " · " + m.configuration();
    }

    @Id
    private String id;
    @Indexed(unique = true)
    private String number;
    @Version
    private Long version;
    private String conversationId;
    private String channel;
    private Requester requester;
    private ChargeTo chargeTo;
    /** Everything the employee typed, verbatim. The AI summaries are stored separately and labelled. */
    private String originalRequest;
    private String mappingRationale;
    private List<RequirementCheck> requirementChecks;
    private SourcedItem sourced;
    private String rejectionReason;
    private String categoryCode;
    private String categoryName;
    private Map<String, Object> attributes;
    private Map<String, String> attributeSources;
    private List<Line> lines;
    private BigDecimal estimatedTotal;
    private String currency;
    private LocalDate neededBy;
    private String deliverySite;
    private boolean urgent;
    private List<RuleViolation> warnings;
    /** True when the employee needs something the standard catalog does not cover. */
    private boolean nonStandard;
    private List<String> specialRequirements;
    private String title;
    private List<com.compras.procurement.matching.Requirement> requirements = new ArrayList<>();
    private List<AlternativeConsidered> alternativesConsidered = new ArrayList<>();
    private List<SuggestedModel> suggestedModels = new ArrayList<>();
    @Indexed
    private RequestStatus status;
    private List<StatusChange> history = new ArrayList<>();
    private Instant submittedAt;

    protected PurchaseRequest() {
    }

    public PurchaseRequest(String number, String conversationId, String channel, Requester requester,
                           ChargeTo chargeTo, String originalRequest, String mappingRationale,
                           List<RequirementCheck> requirementChecks, String categoryCode, String categoryName, Map<String, Object> attributes,
                           Map<String, String> attributeSources, List<Line> lines, BigDecimal estimatedTotal,
                           String currency, LocalDate neededBy, String deliverySite, boolean urgent,
                           List<RuleViolation> warnings, List<String> specialRequirements, Instant submittedAt) {
        this.number = number;
        this.conversationId = conversationId;
        this.channel = channel;
        this.requester = requester;
        this.chargeTo = chargeTo;
        this.originalRequest = originalRequest;
        this.mappingRationale = mappingRationale;
        this.requirementChecks = requirementChecks == null ? List.of() : requirementChecks;
        this.categoryCode = categoryCode;
        this.categoryName = categoryName;
        this.attributes = attributes;
        this.attributeSources = attributeSources;
        this.lines = lines;
        this.estimatedTotal = estimatedTotal;
        this.currency = currency;
        this.neededBy = neededBy;
        this.deliverySite = deliverySite;
        this.urgent = urgent;
        this.warnings = warnings;
        this.specialRequirements = specialRequirements == null ? List.of() : specialRequirements;
        this.nonStandard = !this.specialRequirements.isEmpty();
        this.status = RequestStatus.SUBMITTED;
        this.submittedAt = submittedAt;
        this.history.add(new StatusChange(null, RequestStatus.SUBMITTED, submittedAt, "Submitted via " + channel));
    }

    /** The structured brief: what is needed and what was ruled out. */
    public void describe(String title, List<com.compras.procurement.matching.Requirement> requirements,
                         List<AlternativeConsidered> alternativesConsidered, List<SuggestedModel> suggestedModels) {
        this.suggestedModels = suggestedModels == null ? List.of() : suggestedModels;
        this.title = title;
        this.requirements = requirements == null ? List.of() : requirements;
        this.alternativesConsidered = alternativesConsidered == null ? List.of() : alternativesConsidered;
    }

    public String getTitle() { return title; }
    public List<com.compras.procurement.matching.Requirement> getRequirements() { return requirements; }
    public List<AlternativeConsidered> getAlternativesConsidered() { return alternativesConsidered; }
    /** For the buyer form and the employee's view (JSON only). */
    public String getChosenModel() { return chosenModelLabel(); }
    public List<SuggestedModel> getSuggestedModels() { return suggestedModels == null ? List.of() : suggestedModels; }

    /** Moves the request along its state machine; rejects transitions the lifecycle does not allow. */
    public void moveTo(RequestStatus target, Instant at, String note) {
        if (!status.canMoveTo(target)) {
            throw new IllegalStateException("Request " + number + " cannot move from " + status + " to " + target);
        }
        history.add(new StatusChange(status, target, at, note));
        status = target;
    }

    public void recordSourcing(SourcedItem item) {
        this.sourced = item;
    }

    public void recordRejection(String reason) {
        this.rejectionReason = reason;
    }

    public String getId() { return id; }
    public ChargeTo getChargeTo() { return chargeTo; }
    public String getOriginalRequest() { return originalRequest; }
    public String getMappingRationale() { return mappingRationale; }
    public List<RequirementCheck> getRequirementChecks() { return requirementChecks; }
    public SourcedItem getSourced() { return sourced; }
    public String getRejectionReason() { return rejectionReason; }
    public String getNumber() { return number; }
    public String getConversationId() { return conversationId; }
    public String getChannel() { return channel; }
    public Requester getRequester() { return requester; }
    public String getCategoryCode() { return categoryCode; }
    public String getCategoryName() { return categoryName; }
    public Map<String, Object> getAttributes() { return attributes; }
    public Map<String, String> getAttributeSources() { return attributeSources; }
    public List<Line> getLines() { return lines; }
    public BigDecimal getEstimatedTotal() { return estimatedTotal; }
    public String getCurrency() { return currency; }
    public LocalDate getNeededBy() { return neededBy; }
    public String getDeliverySite() { return deliverySite; }
    public boolean isUrgent() { return urgent; }
    public List<RuleViolation> getWarnings() { return warnings; }
    public boolean isNonStandard() { return nonStandard; }
    public List<String> getSpecialRequirements() { return specialRequirements; }
    public RequestStatus getStatus() { return status; }
    public List<StatusChange> getHistory() { return history; }
    public Instant getSubmittedAt() { return submittedAt; }
}
