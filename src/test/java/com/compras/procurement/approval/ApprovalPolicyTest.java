package com.compras.procurement.approval;

import com.compras.procurement.request.ApprovalStep;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalPolicyTest {

    private final ApprovalPolicy policy = new ApprovalPolicy(com.compras.procurement.policy.PolicyFiles::builtin);
    private final List<StaffMember> staff = List.of(
            new StaffMember("S02", "Elena Torres", "Eng & Ops Manager", StaffRole.LINE_MANAGER, List.of("Engineering", "Operations")),
            new StaffMember("S11", "Ricardo Gil", "COO", StaffRole.DEPARTMENT_HEAD, List.of("Engineering", "Operations")),
            new StaffMember("S20", "Isabel Moreno", "Finance Controller", StaffRole.FINANCE_CONTROLLER, List.of("*")),
            new StaffMember("S21", "Andres Castillo", "CFO", StaffRole.CFO, List.of("*")),
            new StaffMember("S30", "David Ortega", "IT Asset Manager", StaffRole.IT_ASSET_MANAGER, List.of("*")),
            new StaffMember("S31", "Carmen Ruiz", "Cloud Platform Lead", StaffRole.CLOUD_PLATFORM, List.of("*")),
            new StaffMember("S32", "Alberto Sanz", "IT Security & DPO", StaffRole.IT_SECURITY, List.of("*")));

    private List<String> roles(String stage, boolean catalog, String amount, String category, boolean over,
                               String requester, List<ApprovalStep> done) {
        return policy.steps(new ApprovalPolicy.Input(stage, catalog, new BigDecimal(amount), category, "Engineering",
                requester, over, done), staff).stream().filter(ApprovalStep::isPending)
                .map(s -> s.approverName() + "/" + s.role()).toList();
    }

    @Test
    void smallCatalogItemWithinBudgetNeedsNobody() {
        assertEquals(List.of(), roles("SPEND", true, "400", "TONER", false, "Javier Ruiz", List.of()));
    }

    @Test
    void theMatrixAddsApproversByAmountCategoryAndBudget() {
        assertEquals(List.of("Elena Torres/LINE_MANAGER"),
                roles("SPEND", true, "1200", "ACCESSORY", false, "Javier Ruiz", List.of()));
        assertEquals(List.of("Elena Torres/LINE_MANAGER", "David Ortega/IT_ASSET_MANAGER"),
                roles("SPEND", true, "1300", "LAPTOP", false, "Javier Ruiz", List.of()));
        assertEquals(List.of("Elena Torres/LINE_MANAGER", "Ricardo Gil/DEPARTMENT_HEAD", "Isabel Moreno/FINANCE_CONTROLLER"),
                roles("SPEND", true, "8000", "ACCESSORY", false, "Javier Ruiz", List.of()));
        assertEquals(List.of("Elena Torres/LINE_MANAGER", "Ricardo Gil/DEPARTMENT_HEAD",
                        "Isabel Moreno/FINANCE_CONTROLLER", "Andres Castillo/CFO"),
                roles("SPEND", true, "30000", "ACCESSORY", false, "Javier Ruiz", List.of()));
        // over budget: even a small catalog item goes to the Finance Controller
        assertEquals(List.of("Elena Torres/LINE_MANAGER", "Isabel Moreno/FINANCE_CONTROLLER"),
                roles("SPEND", true, "300", "TONER", true, "Javier Ruiz", List.of()));
    }

    @Test
    void nobodyApprovesTheirOwnRequest() {
        // Elena requests: her own line-manager step escalates to the department head
        assertEquals(List.of("Ricardo Gil/DEPARTMENT_HEAD"),
                roles("SPEND", true, "1200", "ACCESSORY", false, "Elena Torres", List.of()));
    }

    @Test
    void approvingTheNeedIsNotApprovingThePrice() {
        List<String> need = roles("NEED", false, "0", null, false, "Javier Ruiz", List.of());
        assertEquals(List.of("Elena Torres/LINE_MANAGER"), need);
        ApprovalStep approvedNeed = ApprovalStep.pending("LINE_MANAGER", "S02", "Elena Torres", "", "need", "NEED")
                .decide(true, "ok", Instant.now());
        // sourced at 2,059: the budget holder approved the need without a price; she now approves the price
        assertEquals(List.of("Elena Torres/LINE_MANAGER"),
                roles("SPEND", false, "2059", "OTHER", false, "Javier Ruiz", List.of(approvedNeed)));
        // sourced at 6,000: department head and finance join
        assertEquals(List.of("Elena Torres/LINE_MANAGER", "Ricardo Gil/DEPARTMENT_HEAD", "Isabel Moreno/FINANCE_CONTROLLER"),
                roles("SPEND", false, "6000", "OTHER", false, "Javier Ruiz", List.of(approvedNeed)));
        // the need approval stays in the chain, approved
        assertTrue(policy.steps(new ApprovalPolicy.Input("SPEND", false, new BigDecimal("6000"), "OTHER",
                "Engineering", "Javier Ruiz", false, List.of(approvedNeed)), staff).get(0).isApproved());
        // within one decision nobody is asked twice
        ApprovalStep approvedPrice = ApprovalStep.pending("LINE_MANAGER", "S02", "Elena Torres", "", "price", "SPEND")
                .decide(true, "ok", Instant.now());
        assertEquals(List.of(), roles("SPEND", false, "2059", "OTHER", false, "Javier Ruiz",
                List.of(approvedNeed, approvedPrice)));
    }

    private List<String> cloud(String amount, String region, String data) {
        return policy.steps(new ApprovalPolicy.Input("SPEND", true, new BigDecimal(amount), "CLOUD", "Engineering",
                "Javier Ruiz", false, List.of(), java.util.Map.of("region", region, "dataClassification", data)), staff)
                .stream().map(s -> s.approverName()).toList();
    }

    @Test
    void cloudCallOffsGoThroughThePlatformTeamAndDataProtectionWhenNeeded() {
        // EU, internal data, small: manager + cloud platform
        assertEquals(List.of("Elena Torres", "Carmen Ruiz"), cloud("900", "Spain", "Internal"));
        // Hong Kong with internal data: data protection joins
        assertEquals(List.of("Elena Torres", "Carmen Ruiz", "Alberto Sanz"), cloud("900", "Hong Kong", "Internal"));
        // Hong Kong with public data only: no data protection review
        assertEquals(List.of("Elena Torres", "Carmen Ruiz"), cloud("900", "Hong Kong", "Public"));
        // personal data anywhere + contract value over 5,000: data protection, department head and finance
        assertEquals(List.of("Elena Torres", "Carmen Ruiz", "Alberto Sanz", "Ricardo Gil", "Isabel Moreno"),
                cloud("8000", "Spain", "Confidential"));
    }
}
