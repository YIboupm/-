package com.compras.procurement.consolidation;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Decides whether a pooled group should be ordered now. Policies are independent beans; the group
 * is released if ANY policy says so. Adding a policy (e.g. "release when the pool value exceeds the
 * supplier's free-shipping threshold") is one new class.
 */
public interface ReleasePolicy {

    Optional<ReleaseReason> evaluate(PoolGroup group, LocalDate asOf);
}
