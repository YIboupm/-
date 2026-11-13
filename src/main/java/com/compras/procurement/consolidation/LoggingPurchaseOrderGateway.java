package com.compras.procurement.consolidation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingPurchaseOrderGateway implements PurchaseOrderGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingPurchaseOrderGateway.class);

    @Override
    public void issue(ConsolidatedOrder order) {
        log.info("[ERP stub] Issuing {}: {} x {} to {} for {} ({} {})", order.number(), order.totalQuantity(),
                order.sku(), order.supplierName(), order.deliverySite(), order.totalAmount(), order.currency());
    }
}
