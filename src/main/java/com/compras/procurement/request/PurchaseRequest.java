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
    /** Lines of a special request (empty on catalog requests and on special requests from before lines). */
    private List<SpecialItem> items = new ArrayList<>();
    /** Why the last award was returned to the buyer, until the next award. */
    private SendBack sentBack;
    /** What the buyer awarded, one per line. */
    private List<Award> awards = new ArrayList<>();
    private List<ApprovalStep> approvals = new ArrayList<>();
    /** Policy route (STANDARD / BUYER_SOURCED / STRATEGIC) and why. */
    private String route;
    private List<String> routeReasons = new ArrayList<>();
    /** The policy version this request is judged under, from submission to the end. */
    private String policyVersion;
    /** Delivery progress once ordered: units ordered, checked in, sent back; the assets registered for it. */
    private Delivery delivery;
    /** The route as an ordered list of steps (building blocks), with where the request stands. */
    private List<FlowStep> flow = new ArrayList<>();
    /** Set when the split check (policy 2) added up related requests. */
    private String splitNote;
    private java.math.BigDecimal policyValue;
    private StrategicCase strategic;
    private List<Quote> quotes = new ArrayList<>();
    /** Who sources it: BUYER or CATEGORY_MANAGER. */
    private String sourcingOwner;
    /** WITHIN_BUDGET / OVER_BUDGET at the last check: a verdict only, never an amount. */
    private String budgetCheck;
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

    public String getRoute() { return route; }
    public List<String> getRouteReasons() { return routeReasons == null ? List.of() : routeReasons; }
    public String getSplitNote() { return splitNote; }
    public java.math.BigDecimal getPolicyValue() { return policyValue; }
    public String getPolicyVersion() { return policyVersion; }
    public Delivery getDelivery() { return delivery; }
    public void setDelivery(Delivery delivery) { this.delivery = delivery; }

    public record Delivery(int ordered, int received, int rejected, List<String> assetTags) {
    }
    public List<FlowStep> getFlow() { return flow == null ? List.of() : flow; }

    public void setFlow(String policyVersion, List<FlowStep> flow) {
        this.policyVersion = policyVersion;
        this.flow = new ArrayList<>(flow);
    }
    public StrategicCase getStrategic() { return strategic; }
    public List<Quote> getQuotes() { return quotes == null ? List.of() : quotes; }
    public String getSourcingOwner() { return sourcingOwner; }

    public void recordRoute(String route, List<String> reasons, java.math.BigDecimal policyValue, String splitNote) {
        this.route = route;
        this.routeReasons = reasons == null ? List.of() : new ArrayList<>(reasons);
        this.policyValue = policyValue;
        this.splitNote = splitNote;
    }

    public void setStrategic(StrategicCase strategic) { this.strategic = strategic; }

    public void setSourcingOwner(String owner) { this.sourcingOwner = owner; }

    public void addQuote(Quote quote) {
        if (quotes == null) {
            quotes = new ArrayList<>();
        }
        quotes.add(quote);
    }

    /** Value the approval matrix and the policy look at: the split-check total when larger. */
    public java.math.BigDecimal matrixValue() {
        java.math.BigDecimal own = amount() == null ? java.math.BigDecimal.ZERO : amount();
        return policyValue != null && policyValue.compareTo(own) > 0 ? policyValue : own;
    }

    public List<ApprovalStep> getApprovals() { return approvals == null ? List.of() : approvals; }
    public void setApprovals(List<ApprovalStep> approvals) { this.approvals = new ArrayList<>(approvals); }
    public String getBudgetCheck() { return budgetCheck; }
    public void setBudgetCheck(String budgetCheck) { this.budgetCheck = budgetCheck; }

    /** The step someone has to decide now, if any. */
    public ApprovalStep currentStep() {
        return getApprovals().stream().filter(ApprovalStep::isPending).findFirst().orElse(null);
    }

    /** What this request costs: the buyer's price once sourced, otherwise the catalog estimate. */
    public BigDecimal amount() {
        return sourced != null ? sourced.total() : estimatedTotal;
    }

    /** The department whose budget pays. */
    public String chargeDepartment() {
        return chargeTo != null && chargeTo.department() != null ? chargeTo.department() : requester.department();
    }

    public String getTitle() { return title; }
    public List<com.compras.procurement.matching.Requirement> getRequirements() { return requirements; }
    public List<AlternativeConsidered> getAlternativesConsidered() { return alternativesConsidered; }
    /** For the buyer form and the employee's view (JSON only). */
    public String getChosenModel() { return chosenModelLabel(); }
    public List<SuggestedModel> getSuggestedModels() { return suggestedModels == null ? List.of() : suggestedModels; }
    public List<SpecialItem> getItems() { return items == null ? List.of() : items; }
    public List<Award> getAwards() { return awards == null ? List.of() : awards; }

    public void setItems(List<SpecialItem> items) {
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    }

    /** The lines a buyer sources: the request's own, or the request itself as one line. */
    public List<SpecialItem> itemsOrDefault() {
        if (!getItems().isEmpty()) {
            return getItems();
        }
        Object q = attributes == null ? null : attributes.get("quantity");
        int quantity;
        try {
            quantity = q == null ? 1 : (int) Double.parseDouble(String.valueOf(q));
        } catch (NumberFormatException e) {
            quantity = 1;
        }
        String name = title != null ? title : chosenModelLabel() != null ? chosenModelLabel() : categoryName;
        return List.of(new SpecialItem("1", name, Math.max(1, quantity), getRequirements(), null, null,
                chosenModelLabel()));
    }

    /** For the buyer's desk (JSON): the lines to source, the request's own or the request as one line. */
    public List<SpecialItem> getSourcingItems() {
        return isNonStandard() || sourced != null ? itemsOrDefault() : List.of();
    }

    public void recordAwards(List<Award> awards, SourcedItem summary) {
        this.awards = new ArrayList<>(awards);
        this.sourced = summary;
        this.sentBack = null;
    }

    public record SendBack(String by, String role, String reason, Instant at, String previousAward) {
    }

    public SendBack getSentBack() { return sentBack; }

    /**
     * An approver returned the award to the buyer: the need stands, the price does not. Approvals of that price
     * lapse, the approver's step is recorded as sent back, the steps still to come are dropped.
     */
    public void sendBackToSourcing(ApprovalStep step, Instant at, String note) {
        String previous = sourced == null ? null : sourced.description() + " · " + sourced.supplierName() + " · "
                + sourced.total() + " " + currency;
        List<ApprovalStep> kept = new ArrayList<>();
        for (ApprovalStep s : getApprovals()) {
            if (s.equals(step)) {
                kept.add(s.sendBack(at, note));
            } else if (s.isApproved() && !"NEED".equals(s.stage())) {
                kept.add(s.supersede(at, "Award sent back by " + step.approverName()));
            } else if (!s.isPending()) {
                kept.add(s);
            }
        }
        this.approvals = kept;
        this.sentBack = new SendBack(step.approverName(), step.role(), note, at, previous);
        this.sourced = null;
        this.awards = new ArrayList<>();
        moveTo(RequestStatus.SOURCING, at, "Sent back to the buyer by " + step.approverName() + ": " + note);
    }

    /** The order was cancelled before delivery: the awards and the approvals of their price no longer stand. */
    public void reopenSourcing(Instant at, String note) {
        moveTo(RequestStatus.SOURCING, at, note);
        this.sourced = null;
        this.awards = new ArrayList<>();
        this.delivery = null;
        List<ApprovalStep> kept = new ArrayList<>();
        for (ApprovalStep s : getApprovals()) {
            kept.add(s.isApproved() && !"NEED".equals(s.stage()) ? s.supersede(at, "Price no longer valid: " + note) : s);
        }
        this.approvals = kept;
    }

    public void replaceQuote(int index, Quote quote) {
        quotes.set(index, quote);
    }

    /** Moves the request along its state machine; rejects transitions the lifecycle does not allow. */
    public void moveTo(RequestStatus target, Instant at, String note) {
        if (!status.canMoveTo(target)) {
            throw new IllegalStateException("Request " + number + " cannot move from " + status + " to " + target);
        }
        history.add(new StatusChange(status, target, at, note));
        status = target;
    }

    /** A history note without a status change (e.g. the buyer priced the request). */
    public void addNote(Instant at, String note) {
        history.add(new StatusChange(status, status, at, note));
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
