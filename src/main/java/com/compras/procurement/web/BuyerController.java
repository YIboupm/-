package com.compras.procurement.web;

import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** The buyer's desk: special requests that the standard catalog does not cover. */
@RestController
@RequestMapping("/api/buyer/requests")
public class BuyerController {

    public record RejectRequest(String reason) {
    }

    public record QuoteInput(String supplierName, String description, int quantity, java.math.BigDecimal unitPrice,
                             String reference, String note) {
    }

    public record AwardInput(int quoteIndex, String description, String substitutionReason,
                             List<com.compras.procurement.request.BuyerApproval.Confirmation> confirmations,
                             String quoteException, String note) {
    }

    public record Reason(String reason) {
    }

    private final RequestService requests;
    private final com.compras.procurement.approval.StrategicService strategic;
    private final com.compras.procurement.policy.PolicyRouter policy;

    public BuyerController(RequestService requests, com.compras.procurement.approval.StrategicService strategic,
                           com.compras.procurement.policy.PolicyRouter policy) {
        this.requests = requests;
        this.strategic = strategic;
        this.policy = policy;
    }

    /** Waiting to be accepted, and being sourced (by a buyer or the category manager). */
    @GetMapping
    public List<PurchaseRequest> waiting() {
        return requests.waitingForBuyer();
    }

    /** Step 1: the buyer takes the request. */
    @PostMapping("/{id}/accept")
    public PurchaseRequest accept(@PathVariable String id) {
        PurchaseRequest r = requests.get(id);
        if (r.getStatus() != com.compras.procurement.request.RequestStatus.BUYER_REVIEW) {
            throw new IllegalStateException(r.getNumber() + " is not waiting to be accepted.");
        }
        return requests.startSourcing(id, "BUYER", "Accepted by a buyer: collecting quotes");
    }

    /** Step 2: record a quote (policy 5: kept with the request). */
    @PostMapping("/{id}/quotes")
    public PurchaseRequest quote(@PathVariable String id, @RequestBody QuoteInput q) {
        return requests.addQuote(id, new com.compras.procurement.request.Quote(q.supplierName(), q.description(),
                q.quantity(), q.unitPrice(), null, q.reference(), q.note(), "buyer", null));
    }

    /** Step 3: award a quote; enough quotes for the value or a written reason. Then approval + budget. */
    @PostMapping("/{id}/award")
    public PurchaseRequest award(@PathVariable String id, @RequestBody AwardInput a) {
        requests.award(id, a.quoteIndex(), a.description(), a.substitutionReason(), a.confirmations(),
                a.quoteException(), a.note(), policy::quotesRequired);
        return requests.get(id);
    }

    /** Policy 7: the buyer sends it to the category manager. */
    @PostMapping("/{id}/strategic")
    public PurchaseRequest escalate(@PathVariable String id, @RequestBody Reason body) {
        return strategic.escalateByBuyer(id, body.reason());
    }

    /** What the policy asks for an award of this value (for the form). */
    @GetMapping("/policy")
    public java.util.Map<String, Object> policy() {
        return java.util.Map.of("singleQuoteUpTo", policy.policy().singleQuoteUpTo(),
                "strategicAbove", policy.policy().strategicAbove());
    }

    @PostMapping("/{id}/reject")
    public PurchaseRequest reject(@PathVariable String id, @RequestBody RejectRequest body) {
        return requests.reject(id, body.reason());
    }
}
