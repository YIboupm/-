package com.compras.procurement.approval;

import com.compras.procurement.budget.BudgetAccount;
import com.compras.procurement.budget.BudgetEntry;
import com.compras.procurement.budget.BudgetService;
import com.compras.procurement.shared.ForbiddenException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Who may see budget figures: finance (all departments) and budget holders (their own departments).
 * Everyone else, including requesters and the AI agent, gets nothing from here.
 */
@Service
public class BudgetAccess {

    public record Movement(String requestNumber, String type, BigDecimal amount, BigDecimal released,
                           boolean overBudget, String actor, String note, java.time.Instant at) {
    }

    public record DepartmentBudget(String department, String costCenter, String period, BigDecimal allocated,
                                   BigDecimal available, BigDecimal reserved, BigDecimal committed, BigDecimal spent,
                                   List<Movement> ledger) {
    }

    public record Overview(String viewer, String role, String period, BigDecimal pool,
                           List<DepartmentBudget> departments) {
    }

    private final BudgetService budgets;
    private final ApprovalService approvals;

    public BudgetAccess(BudgetService budgets, ApprovalService approvals) {
        this.budgets = budgets;
        this.approvals = approvals;
    }

    public Overview overview(String staffId) {
        StaffMember viewer = authorised(staffId);
        List<DepartmentBudget> visible = budgets.accounts().stream()
                .filter(a -> mayView(viewer, a.department()))
                .map(this::view).toList();
        if (visible.isEmpty()) {
            throw new ForbiddenException(viewer.name() + " (" + viewer.role().label() + ") may not see budget figures.");
        }
        return new Overview(viewer.name(), viewer.role().label(), budgets.currentPeriod(),
                viewer.role().seesAllBudgets() ? budgets.pool() : null, visible);
    }

    /** The budget panel an approver sees next to a request, or null if they may not see it. */
    public DepartmentBudget forApprover(String staffId, String department) {
        StaffMember viewer = approvals.member(staffId);
        return mayView(viewer, department) ? view(budgets.account(department)) : null;
    }

    public static boolean mayView(StaffMember viewer, String department) {
        return viewer.role().seesAllBudgets() || (viewer.role().holdsBudget() && viewer.actsFor(department));
    }

    private StaffMember authorised(String staffId) {
        if (staffId == null || staffId.isBlank()) {
            throw new ForbiddenException("Budget figures are restricted to finance and budget holders.");
        }
        try {
            return approvals.member(staffId);
        } catch (IllegalStateException e) {
            throw new ForbiddenException("Budget figures are restricted to finance and budget holders.");
        }
    }

    private DepartmentBudget view(BudgetAccount a) {
        List<Movement> ledger = budgets.ledger(a.department()).stream().map(BudgetAccess::movement).toList();
        return new DepartmentBudget(a.department(), a.costCenter(), a.period(), BudgetService.euros(a.allocated()),
                BudgetService.euros(a.available()), BudgetService.euros(a.reserved()),
                BudgetService.euros(a.committed()), BudgetService.euros(a.spent()), ledger);
    }

    private static Movement movement(BudgetEntry e) {
        return new Movement(e.requestNumber(), e.type().name(), BudgetService.euros(e.amount()),
                BudgetService.euros(e.released()), e.overBudget(), e.actor(), e.note(), e.at());
    }
}
