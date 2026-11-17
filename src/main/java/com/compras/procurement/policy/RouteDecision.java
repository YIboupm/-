package com.compras.procurement.policy;

import java.util.List;

/**
 * The route the policy chose for a request, with the reasons (policy sections) and the rules that fired.
 *
 * @param strategic the route starts with the category manager's review
 */
public record RouteDecision(String route, List<String> reasons, boolean strategic, List<String> ruleIds) {
}
