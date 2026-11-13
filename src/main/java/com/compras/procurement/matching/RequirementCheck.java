package com.compras.procurement.matching;

/**
 * Result of comparing one requirement with the selected catalog item(s).
 *
 * @param actual what the catalog says, e.g. "Windows 11"; null when the catalog does not specify it
 */
public record RequirementCheck(Requirement requirement, Status status, String actual) {

    public enum Status {
        /** The catalog value satisfies the requirement. */
        MET,
        /** The catalog value contradicts the requirement. */
        NOT_MET,
        /** The catalog does not describe this property, so the system cannot tell. */
        UNKNOWN
    }

    /** A must-have that the standard item does not provably satisfy: a reason to involve a buyer. */
    public boolean blocksStandard() {
        return requirement.isMust() && status != Status.MET;
    }

    /** Short text for the employee, the buyer and the stored record. */
    public String explain() {
        String base = requirement.description();
        return switch (status) {
            case MET -> base + " (catalog: " + actual + ")";
            case NOT_MET -> base + " (catalog: " + actual + ")";
            case UNKNOWN -> base + " (not specified in the catalog)";
        };
    }
}
