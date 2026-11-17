package com.compras.procurement.policy;

/** Where the policy in force comes from (the registry in production; a fixed version in unit tests). */
public interface PolicySource {

    PolicySet active();

    /** The version a request was judged under; the active one when it is unknown. */
    default PolicySet version(String version) {
        return active();
    }
}
