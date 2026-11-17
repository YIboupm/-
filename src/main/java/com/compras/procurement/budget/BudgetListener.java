package com.compras.procurement.budget;

import com.compras.procurement.shared.Events;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Moves money as requests move: release on rejection or cancellation, commit on order, spend on delivery. */
@Component
public class BudgetListener {

    private final BudgetService budgets;

    public BudgetListener(BudgetService budgets) {
        this.budgets = budgets;
    }

    @EventListener
    public void on(Events.RequestRejected e) {
        budgets.release(e.requestId(), e.requestNumber(), "system", "Request rejected: reservation released");
    }

    @EventListener
    public void on(Events.RequestCancelled e) {
        budgets.release(e.requestId(), e.requestNumber(), e.by(), "Request cancelled: reservation released");
    }

    @EventListener
    public void on(Events.RequestOrdered e) {
        if (e.amount() != null) {
            budgets.commit(e.requestId(), e.requestNumber(), e.department(), e.amount(), "system");
        }
    }

    @EventListener
    public void on(Events.RequestReceived e) {
        budgets.spend(e.requestId(), e.requestNumber(), "receiving");
    }
}
