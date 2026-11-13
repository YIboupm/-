package com.compras.procurement.consolidation;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/** The three release rules of the demo. */
public final class ReleasePolicies {

    private ReleasePolicies() {
    }

    /** Release everything on the weekly consolidation day. */
    @Component
    public static class WindowPolicy implements ReleasePolicy {
        private final ConsolidationProperties properties;

        public WindowPolicy(ConsolidationProperties properties) {
            this.properties = properties;
        }

        @Override
        public Optional<ReleaseReason> evaluate(PoolGroup group, LocalDate asOf) {
            return asOf.getDayOfWeek() == properties.windowDay()
                    ? Optional.of(ReleaseReason.CONSOLIDATION_WINDOW) : Optional.empty();
        }
    }

    /** Release early when the pool already reaches the supplier's best price tier: waiting gains nothing. */
    @Component
    public static class BestPricePolicy implements ReleasePolicy {
        @Override
        public Optional<ReleaseReason> evaluate(PoolGroup group, LocalDate asOf) {
            int best = group.item().bestBreakQty();
            return best > 0 && group.totalQuantity() >= best
                    ? Optional.of(ReleaseReason.BEST_PRICE_REACHED) : Optional.empty();
        }
    }

    /** Never let pooling make anyone late: release on the latest order date at the latest. */
    @Component
    public static class DeadlinePolicy implements ReleasePolicy {
        private final ConsolidationProperties properties;

        public DeadlinePolicy(ConsolidationProperties properties) {
            this.properties = properties;
        }

        @Override
        public Optional<ReleaseReason> evaluate(PoolGroup group, LocalDate asOf) {
            LocalDate latest = group.latestOrderDate(properties.safetyBufferDays());
            return latest != null && !asOf.isBefore(latest)
                    ? Optional.of(ReleaseReason.DEADLINE) : Optional.empty();
        }
    }
}
