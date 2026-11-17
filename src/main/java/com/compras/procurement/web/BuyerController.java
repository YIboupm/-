package com.compras.procurement.web;

import com.compras.procurement.approval.StaffMember;
import com.compras.procurement.approval.StaffRepository;
import com.compras.procurement.approval.StaffRole;
import com.compras.procurement.flow.SourcingReopenService;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.Quote;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.shared.ForbiddenException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The buyer's desk: special requests that the standard catalog does not cover. Every change is made by a named
 * buyer (or the category manager for strategic sourcing): demo identity X-Staff-Id, production single sign-on.
 */
@RestController
@RequestMapping("/api/buyer/requests")
public class BuyerController {

    public record RejectRequest(String reason) {
    }

    /** @param itemId the line of the request the quote covers */
    public record QuoteInput(String supplierName, String description, int quantity, java.math.BigDecimal unitPrice,
                             String reference, String note, String itemId) {
    }

    /** One-line award, as before lines (kept for scripts and tests). */
    public record AwardInput(int quoteIndex, String description, String substitutionReason,
                             List<com.compras.procurement.request.BuyerApproval.Confirmation> confirmations,
                             String quoteException, String note) {
    }

    public record AwardLines(List<RequestService.LineAward> lines, String note) {
    }

    public record Reason(String reason) {
    }

    private final RequestService requests;
    private final com.compras.procurement.approval.StrategicService strategic;
    private final com.compras.procurement.policy.PolicyRouter policy;
    private final com.compras.procurement.policy.PolicySource policies;
    private final StaffRepository staff;
    private final SourcingReopenService reopen;

    public BuyerController(RequestService requests, com.compras.procurement.approval.StrategicService strategic,
                           com.compras.procurement.policy.PolicyRouter policy,
                           com.compras.procurement.policy.PolicySource policies, StaffRepository staff,
                           SourcingReopenService reopen) {
        this.policies = policies;
        this.requests = requests;
        this.strategic = strategic;
        this.policy = policy;
        this.staff = staff;
        this.reopen = reopen;
    }

    /** Waiting to be accepted, and being sourced (by a buyer or the category manager). */
    @GetMapping
    public List<PurchaseRequest> waiting() {
        return requests.waitingForBuyer();
    }

    /** Ordered and not delivered yet: an order that turns out wrong can still be cancelled and sourced again. */
    @GetMapping("/ordered")
    public List<PurchaseRequest> ordered() {
        return reopen.correctable();
    }

    /** Step 1: the buyer takes the request. */
    @PostMapping("/{id}/accept")
    public PurchaseRequest accept(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId) {
        StaffMember who = sourcer(id, staffId);
        PurchaseRequest r = requests.get(id);
        if (r.getStatus() != com.compras.procurement.request.RequestStatus.BUYER_REVIEW) {
            throw new IllegalStateException(r.getNumber() + " is not waiting to be accepted.");
        }
        return requests.startSourcing(id, "BUYER", "Accepted by " + who.name() + ": collecting quotes");
    }

    /** Step 2: record a quote for one line (policy 5: kept with the request, with its evidence). */
    @PostMapping("/{id}/quotes")
    public PurchaseRequest quote(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId,
                                 @RequestBody QuoteInput q) {
        StaffMember who = sourcer(id, staffId);
        return requests.addQuote(id, new Quote(q.supplierName(), q.description(), q.quantity(), q.unitPrice(), null,
                q.reference(), q.note(), who.name(), null, q.itemId(), null));
    }

    /** A wrong quote is withdrawn with a reason; it stays on the request. */
    @PostMapping("/{id}/quotes/{index}/withdraw")
    public PurchaseRequest withdraw(@PathVariable String id, @PathVariable int index,
                                    @RequestHeader("X-Staff-Id") String staffId, @RequestBody Reason body) {
        StaffMember who = sourcer(id, staffId);
        return requests.withdrawQuote(id, index, body.reason(), who.name());
    }

    /** Step 3: award every line. Then the price goes through approval and the budget. */
    @PostMapping("/{id}/award")
    public PurchaseRequest award(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId,
                                 @RequestBody AwardLines a) {
        StaffMember who = sourcer(id, staffId);
        // judged under the policy version the request was submitted with
        var version = policies.version(requests.get(id).getPolicyVersion());
        requests.awardLines(id, a.lines(), a.note(), value -> policy.quotesRequired(version, value),
                version.priceDeviationPercent(), who.name());
        return requests.get(id);
    }

    /** The order was wrong and nothing has arrived: cancel it, release the commitment, source again. */
    @PostMapping("/{id}/reopen")
    public PurchaseRequest reopen(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId,
                                  @RequestBody Reason body) {
        return reopen.reopen(id, staffId, body.reason());
    }

    /** Policy 7: the buyer sends it to the category manager. */
    @PostMapping("/{id}/strategic")
    public PurchaseRequest escalate(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId,
                                    @RequestBody Reason body) {
        sourcer(id, staffId);
        return strategic.escalateByBuyer(id, body.reason());
    }

    /** What the policy in force asks of an award (for the form): read from the rules, not hard-coded. */
    @GetMapping("/policy")
    public java.util.Map<String, Object> policy() {
        var p = policies.active();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("version", p.version());
        p.quotes().stream().flatMap(q -> q.when().stream())
                .filter(c -> com.compras.procurement.policy.Facts.AWARD_VALUE.equals(c.fact()) && c.value() instanceof Number)
                .findFirst().ifPresent(c -> out.put("singleQuoteUpTo", c.value()));
        p.routing().stream().filter(r -> p.isStrategic(r.route())).flatMap(r -> r.when().stream())
                .filter(c -> com.compras.procurement.policy.Facts.CONTRACT_VALUE.equals(c.fact()) && c.value() instanceof Number)
                .findFirst().ifPresent(c -> out.put("strategicAbove", c.value()));
        out.put("priceDeviationPercent", p.priceDeviationPercent());
        return out;
    }

    @PostMapping("/{id}/reject")
    public PurchaseRequest reject(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId,
                                  @RequestBody RejectRequest body) {
        StaffMember who = sourcer(id, staffId);
        return requests.reject(id, body.reason() + " (" + who.name() + ")");
    }

    /** A buyer; or the category manager when the request is sourced under strategic review. */
    private StaffMember sourcer(String requestId, String staffId) {
        StaffMember who = staff.findById(staffId == null ? "" : staffId)
                .orElseThrow(() -> new IllegalStateException("Unknown staff member"));
        PurchaseRequest r = requests.get(requestId);
        StaffRole needed = "CATEGORY_MANAGER".equals(r.getSourcingOwner()) ? StaffRole.CATEGORY_MANAGER : StaffRole.BUYER;
        if (who.role() != needed) {
            throw new ForbiddenException(r.getNumber() + " is sourced by the " + needed.label() + ".");
        }
        return who;
    }
}
