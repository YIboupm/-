package com.compras.procurement.consolidation;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One approved request line waiting in the demand pool. Lines from many requests with the same
 * SKU, supplier and delivery site are merged into one purchase order.
 */
@Document("demand_lines")
public class DemandLine {

    public enum Status { POOLED, ORDERED }

    @Id
    private String id;
    @Indexed
    private String requestId;
    private String requestNumber;
    private String requesterName;
    private String department;
    private String sku;
    private String itemName;
    private String supplierId;
    private String deliverySite;
    private int quantity;
    private LocalDate neededBy;
    private boolean urgent;
    private Instant pooledAt;
    @Indexed
    private Status status;
    private String orderId;
    private String orderNumber;

    protected DemandLine() {
    }

    public DemandLine(String requestId, String requestNumber, String requesterName, String department, String sku,
                      String itemName, String supplierId, String deliverySite, int quantity, LocalDate neededBy,
                      boolean urgent, Instant pooledAt) {
        this.requestId = requestId;
        this.requestNumber = requestNumber;
        this.requesterName = requesterName;
        this.department = department;
        this.sku = sku;
        this.itemName = itemName;
        this.supplierId = supplierId;
        this.deliverySite = deliverySite;
        this.quantity = quantity;
        this.neededBy = neededBy;
        this.urgent = urgent;
        this.pooledAt = pooledAt;
        this.status = Status.POOLED;
    }

    public GroupKey groupKey() {
        return new GroupKey(sku, supplierId, deliverySite);
    }

    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getRequestNumber() { return requestNumber; }
    public String getRequesterName() { return requesterName; }
    public String getDepartment() { return department; }
    public String getSku() { return sku; }
    public String getItemName() { return itemName; }
    public String getSupplierId() { return supplierId; }
    public String getDeliverySite() { return deliverySite; }
    public int getQuantity() { return quantity; }
    public LocalDate getNeededBy() { return neededBy; }
    public boolean isUrgent() { return urgent; }
    public Instant getPooledAt() { return pooledAt; }
    public Status getStatus() { return status; }
    public String getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
}
