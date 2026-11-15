package com.compras.procurement.request;

import java.time.Instant;
import java.util.List;

/**
 * Why a request is strategic and what the category manager decided (policy 7).
 *
 * @param source  AUTO (a policy trigger) or MANUAL (an approver or buyer raised it)
 * @param outcome null while open; USE_EXISTING_AGREEMENT, SOURCE, NEW_FRAMEWORK or REJECT
 */
public record StrategicCase(String source, List<String> reasons, String raisedBy, Instant raisedAt,
                            String outcome, String decidedBy, String note, Instant decidedAt) {

    public StrategicCase decide(String outcome, String by, String note, Instant at) {
        return new StrategicCase(source, reasons, raisedBy, raisedAt, outcome, by, note, at);
    }
}
