package com.compras.procurement.policy;

import java.util.List;

/** The route and, for a strategic purchase, every policy trigger that made it strategic. */
public record RouteDecision(Route route, List<String> reasons) {

    public boolean strategic() {
        return route == Route.STRATEGIC;
    }
}
