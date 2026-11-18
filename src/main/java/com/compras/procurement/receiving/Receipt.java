package com.compras.procurement.receiving;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a purchase order is expected to deliver, and what was checked in. Created when the order is issued (the
 * "pre-registration": item, supplier, price, who it is for, whether it becomes an asset and who receives it are all
 * known then); completed at the dock, where only the facts that exist on arrival are added (quantities, serial
 * numbers, the model actually delivered, damage).
 */
@Document("receipts")
public class Receipt {

    public enum Status { EXPECTED, PARTIAL, RECEIVED, CANCELLED }

    /** One request's share of the order. */
    public static class Line {
        private String requestId;
        private String requestNumber;
        private String requesterId;
        private String requesterName;
        private String department;
        private int ordered;
        private int received;
        private int rejected;
        /** One unit for the requester's own use: the asset is handed to them; otherwise it is reserved. */
        private boolean forRequester;
        private java.time.LocalDate neededBy;

        protected Line() {
        }

        public Line(String requestId, String requestNumber, String requesterId, String requesterName,
                    String department, int ordered, boolean forRequester, java.time.LocalDate neededBy) {
            this.requestId = requestId;
            this.requestNumber = requestNumber;
            this.requesterId = requesterId;
            this.requesterName = requesterName;
            this.department = department;
            this.ordered = ordered;
            this.forRequester = forRequester;
            this.neededBy = neededBy;
        }

        public int outstanding() {
            return ordered - received;
        }

        void take(int units) {
            received += units;
        }

        void sentBack(int units) {
            rejected += units;
        }

        public String getRequestId() { return requestId; }
        public String getRequestNumber() { return requestNumber; }
        public String getRequesterId() { return requesterId; }
        public String getRequesterName() { return requesterName; }
        public String getDepartment() { return department; }
        public int getOrdered() { return ordered; }
        public int getReceived() { return received; }
        public int getRejected() { return rejected; }
        public boolean isForRequester() { return forRequester; }
        public java.time.LocalDate getNeededBy() { return neededBy; }
        public int getOutstanding() { return outstanding(); }
    }

    /** One check-in at the dock. */
    public record Delivery(Instant at, String receivedById, String receivedBy, int quantity, int rejected,
                           String rejectionReason, String modelAsDelivered, List<String> serialNumbers,
                           List<String> assetTags, String note, boolean aiAssisted, List<String> aiWarnings) {
    }

    public record SupplierReturn(String operationId, String requestId, int quantity, List<String> assetTags,
                                 String reason, String receivedBy, Instant at) {}

    private List<SupplierReturn> supplierReturns = new ArrayList<>();
    private boolean returnPending;

    boolean returnPending() { return returnPending; }
    void finishReturn() { returnPending = false; }

    public List<SupplierReturn> getSupplierReturns() {
        return supplierReturns == null ? List.of() : List.copyOf(supplierReturns);
    }

    void recordReturn(SupplierReturn returned, Line line) {
        if (supplierReturns == null) supplierReturns = new ArrayList<>();
        supplierReturns.add(returned);
        returnPending = true;
        line.take(-returned.quantity());
        line.sentBack(returned.quantity());
        status = Status.PARTIAL;
    }

    @Id
    private String id;
    @Version
    private Long version;
    private String orderId;
    private String orderNumber;
    private String sku;
    private String itemName;
    private String supplierName;
    private String deliverySite;
    private String categoryCode;
    private BigDecimal unitPrice;
    private String currency;
    private Map<String, Object> attributes;
    /** What it is: from the catalog, or classified by the model for a non-catalog item, or corrected by the receiver. */
    private String assetClass;
    private String classifiedBy;
    private String classificationNote;
    /** Section 13 of the policy, decided when the order is issued (re-decided if the receiver corrects the class). */
    private boolean tracked;
    private String receiverRole;
    private String ruleId;
    private String ruleReason;
    private String policyVersion;
    /** For a sourced item: what the buyer awarded, its evidence and the must-haves to check the goods against. */
    private String awardedDescription;
    private String quoteReference;
    private List<String> requirements = new ArrayList<>();
    private String cancelReason;
    private List<Line> lines = new ArrayList<>();
    private List<Delivery> deliveries = new ArrayList<>();
    private Status status = Status.EXPECTED;
    private Instant createdAt;

    protected Receipt() {
    }

    public Receipt(String orderId, String orderNumber, String sku, String itemName, String supplierName,
                   String deliverySite, String categoryCode, BigDecimal unitPrice, String currency,
                   Map<String, Object> attributes, List<Line> lines, Instant createdAt) {
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.sku = sku;
        this.itemName = itemName;
        this.supplierName = supplierName;
        this.deliverySite = deliverySite;
        this.categoryCode = categoryCode;
        this.unitPrice = unitPrice;
        this.currency = currency;
        this.attributes = attributes == null ? Map.of() : attributes;
        this.lines = new ArrayList<>(lines);
        this.createdAt = createdAt;
    }

    void classify(AssetClass cls, String by, String note) {
        this.assetClass = cls.name();
        this.classifiedBy = by;
        this.classificationNote = note;
    }

    void decide(boolean tracked, String receiverRole, String ruleId, String ruleReason, String policyVersion) {
        this.tracked = tracked;
        this.receiverRole = receiverRole;
        this.ruleId = ruleId;
        this.ruleReason = ruleReason;
        this.policyVersion = policyVersion;
    }

    void award(String description, String reference, List<String> requirements) {
        this.awardedDescription = description;
        this.quoteReference = reference;
        this.requirements = requirements == null ? new ArrayList<>() : new ArrayList<>(requirements);
    }

    void cancel(String reason) {
        this.status = Status.CANCELLED;
        this.cancelReason = reason;
    }

    void record(Delivery delivery) {
        deliveries.add(delivery);
        status = outstanding() == 0 ? Status.RECEIVED : Status.PARTIAL;
    }

    public int ordered() {
        return lines.stream().mapToInt(Line::getOrdered).sum();
    }

    public int outstanding() {
        return lines.stream().mapToInt(Line::outstanding).sum();
    }

    public AssetClass assetClassOrOther() {
        return AssetClass.parse(assetClass).orElse(AssetClass.OTHER);
    }

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public String getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public String getSku() { return sku; }
    public String getItemName() { return itemName; }
    public String getSupplierName() { return supplierName; }
    public String getDeliverySite() { return deliverySite; }
    public String getCategoryCode() { return categoryCode; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public String getCurrency() { return currency; }
    public Map<String, Object> getAttributes() { return attributes == null ? Map.of() : attributes; }
    public String getAssetClass() { return assetClass; }
    public String getClassifiedBy() { return classifiedBy; }
    public String getClassificationNote() { return classificationNote; }
    public boolean isTracked() { return tracked; }
    public boolean isItDevice() { return assetClassOrOther().itDevice(); }
    public String getReceiverRole() { return receiverRole; }
    public String getRuleId() { return ruleId; }
    public String getRuleReason() { return ruleReason; }
    public String getPolicyVersion() { return policyVersion; }
    public List<Line> getLines() { return lines; }
    public String getAwardedDescription() { return awardedDescription; }
    public String getQuoteReference() { return quoteReference; }
    public List<String> getRequirements() { return requirements == null ? List.of() : requirements; }
    public String getCancelReason() { return cancelReason; }
    public List<Delivery> getDeliveries() { return deliveries; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public int getOrdered() { return ordered(); }
    public int getOutstanding() { return outstanding(); }
    public int getRejected() { return lines.stream().mapToInt(Line::getRejected).sum(); }
}
