package com.compras.procurement.approval;

/** The people in the purchasing process besides the requester (one or two levels per department). */
public enum StaffRole {
    LINE_MANAGER("Line manager (budget holder)"),
    DEPARTMENT_HEAD("Department head"),
    FINANCE_CONTROLLER("Finance controller"),
    CFO("CFO"),
    IT_ASSET_MANAGER("IT asset manager"),
    CLOUD_PLATFORM("Cloud platform lead"),
    IT_SECURITY("IT security & data protection"),
    BUYER("Buyer"),
    CATEGORY_MANAGER("Category manager");

    private final String label;

    StaffRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Who takes over when this approver may not decide (e.g. they are the requester). */
    public StaffRole escalation() {
        return switch (this) {
            case LINE_MANAGER -> DEPARTMENT_HEAD;
            default -> CFO;
        };
    }

    /** Finance roles may see every department's budget figures. */
    public boolean seesAllBudgets() {
        return this == FINANCE_CONTROLLER || this == CFO;
    }

    /** Budget holders may see their own departments' figures. */
    public boolean holdsBudget() {
        return this == LINE_MANAGER || this == DEPARTMENT_HEAD;
    }
}
