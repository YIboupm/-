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

    public record ApproveRequest(String description, String supplierName, int quantity, BigDecimal unitPrice,
                                 String note) {
    }

    public record RejectRequest(String reason) {
    }

    private final RequestService requests;

    public BuyerController(RequestService requests) {
        this.requests = requests;
    }

    @GetMapping
    public List<PurchaseRequest> waiting() {
        return requests.waitingForBuyer();
    }

    /** The buyer records what was sourced; the request is approved and ordered directly. */
    @PostMapping("/{id}/approve")
    public PurchaseRequest approve(@PathVariable String id, @RequestBody ApproveRequest body) {
        requests.buyerApprove(id, body.description(), body.supplierName(), body.quantity(), body.unitPrice(),
                body.note());
        return requests.get(id);
    }

    @PostMapping("/{id}/reject")
    public PurchaseRequest reject(@PathVariable String id, @RequestBody RejectRequest body) {
        return requests.reject(id, body.reason());
    }
}
