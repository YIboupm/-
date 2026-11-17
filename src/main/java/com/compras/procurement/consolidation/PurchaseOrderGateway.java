package com.compras.procurement.consolidation;

/**
 * Hand-off to the ERP / supplier. The demo implementation only logs; a real one would call the
 * ERP's purchase-order API (SAP OData, Yonyou/Kingdee OpenAPI ...) or send EDI to the supplier.
 */
public interface PurchaseOrderGateway {

    void issue(ConsolidatedOrder order);

    /** Tells the supplier the order is cancelled (a real one: ERP change order / EDI ORDCHG). */
    default void cancel(ConsolidatedOrder order) {
    }
}
