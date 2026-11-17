package com.compras.procurement.review;

import com.compras.procurement.approval.*;
import com.compras.procurement.budget.*;
import com.compras.procurement.catalog.*;
import com.compras.procurement.context.*;
import com.compras.procurement.consolidation.*;
import com.compras.procurement.policy.*;
import com.compras.procurement.request.*;
import com.compras.procurement.resolution.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in review: real MongoDB on an isolated port; assertions describe required behavior, not known bugs. */
@SpringBootTest(properties = {
    "spring.data.mongodb.uri=mongodb://127.0.0.1:27028/procurement_review_20261115",
    "spring.config.import=", "llm.api-key=review-unused", "procurement.consolidation.cron=-"
})
@AutoConfigureMockMvc
class ReviewRegressionIT {
    @Autowired MongoTemplate mongo;
    @Autowired RequestService requests;
    @Autowired CatalogService catalog;
    @Autowired EmployeeContextService employees;
    @Autowired ApprovalService approvals;
    @Autowired StrategicService strategic;
    @Autowired BudgetService budgets;
    @Autowired BudgetProperties budgetProperties;
    @Autowired MongoBudgetStore store;
    @Autowired PolicyRouter router;
    @Autowired ConsolidationService consolidation;
    @Autowired Clock clock;
    @Autowired MockMvc http;

    @BeforeEach void cleanIsolatedDatabase() {
        assertEquals("procurement_review_20261115", mongo.getDb().getName());
        for (Class<?> c : List.of(PurchaseRequest.class, DemandLine.class, ConsolidatedOrder.class,
                BudgetAccount.class, BudgetEntry.class)) mongo.remove(new Query(), c);
    }
    PurchaseRequest submit(String sku, int qty, boolean special, Map<String,Object> extra) {
        CatalogItem item = catalog.item(sku);
        Map<String,Object> a = new HashMap<>(extra);
        a.put("neededBy", LocalDate.now(clock).plusDays(60).toString());
        var line = ResolvedLine.of(item, qty, null);
        if (a.containsKey("monthlyEstimate")) {
            BigDecimal monthly = new BigDecimal(a.get("monthlyEstimate").toString());
            line = new ResolvedLine(item.sku(), item.name(), item.categoryCode(), qty, monthly,
                monthly.multiply(BigDecimal.valueOf(qty)), item.supplierId(), item.supplierName(),
                item.leadTimeDays(), item.specs(), null);
        }
        var r = requests.submit(new NewRequest("web", UUID.randomUUID().toString(), employees.contextFor("E002"),
            catalog.category(item.categoryCode()), a, Map.of(), List.of(line), line.lineTotal(), "EUR", List.of(),
            special ? List.of("custom item") : List.of(), List.of(), "review fixture", "review fixture"));
        return requests.get(r.getId());
    }
    PurchaseRequest approveAll(PurchaseRequest r) {
        for (int i=0; i<15 && r.getStatus()==RequestStatus.PENDING_APPROVAL; i++)
            r = approvals.decide(r.getId(), r.currentStep().approverId(), true, "review approved");
        return requests.get(r.getId());
    }
    PurchaseRequest sourcing() {
        var r = approveAll(submit("LAP-DEV", 1, true, Map.of()));
        assertEquals(RequestStatus.BUYER_REVIEW, r.getStatus());
        return requests.startSourcing(r.getId(), "BUYER", "test");
    }
    void quote(PurchaseRequest r, String supplier, String price) {
        requests.addQuote(r.getId(), new Quote(supplier, "Custom device", 1, new BigDecimal(price), null,
            "https://example.com/quote", "test", "buyer", null));
    }
    void award(PurchaseRequest r) {
        requests.award(r.getId(), 0, "Custom device", "custom requirement", List.of(), null, "test", router::quotesRequired);
    }

    @Test void standardApprovalReservationAndCancellation() {
        var r = approveAll(submit("LAP-DEV", 1, false, Map.of()));
        assertEquals(RequestStatus.POOLED, r.getStatus());
        assertTrue(budgets.position(r.getId()).reserved()>0);
        requests.cancel(r.getId(), "E002", "not needed");
        assertEquals(0, budgets.position(r.getId()).reserved());
        assertTrue(consolidation.preview(LocalDate.now(clock).plusDays(70)).isEmpty());
    }
    @Test void consolidationCommitsAndReturnsDiscount() {
        var r = approveAll(submit("LAP-DEV", 5, false, Map.of()));
        assertEquals(RequestStatus.POOLED, r.getStatus());
        assertTrue(consolidation.run(LocalDate.now(clock).plusDays(70)).ordersCreated()>0);
        assertEquals(RequestStatus.ORDERED, requests.get(r.getId()).getStatus());
        assertEquals(0, budgets.position(r.getId()).reserved());
        assertTrue(budgets.position(r.getId()).committed()>0);
    }
    @Test void smallLaptopStillNeedsItApprovalAccordingToPolicyNine() {
        var r = submit("LAP-STD", 1, false, Map.of());
        assertTrue(r.getApprovals().stream().anyMatch(s -> s.role().equals("IT_ASSET_MANAGER")),
            "Policy 9 requires IT approval for every IT device; actual status="+r.getStatus());
    }
    @Test void splitRequestsEscalateSecondRequest() {
        submit("LAP-STD", 1, false, Map.of());
        var r = submit("LAP-STD", 1, false, Map.of());
        assertEquals(RequestStatus.PENDING_APPROVAL, r.getStatus());
        assertNotNull(r.getSplitNote());
    }
    @Test void cloudSensitiveApprovalAndQuarterCharge() {
        var r = submit("CLD-AWS", 12, false, Map.of("monthlyEstimate", 100, "termMonths", 12,
            "dataClassification", "Confidential", "region", "Spain"));
        assertTrue(r.getApprovals().stream().anyMatch(s -> s.role().equals("CLOUD_PLATFORM")));
        assertTrue(r.getApprovals().stream().anyMatch(s -> s.role().equals("IT_SECURITY")));
        r = approveAll(r);
        assertEquals(RequestStatus.ORDERED, r.getStatus());
        assertEquals(BudgetService.cents(SpendProfile.quarterCharge(r, LocalDate.now(clock))),
            budgets.position(r.getId()).committed());
        assertEquals(1, consolidation.orders().size());
    }
    @Test void expensiveRequestRoutesToStrategic() {
        var r = submit("CLD-AWS", 12, false, Map.of("monthlyEstimate", 3000, "termMonths", 12));
        assertEquals(RequestStatus.STRATEGIC_REVIEW, r.getStatus());
        assertEquals(0, budgets.position(r.getId()).reserved());
    }
    @Test void strategicExistingAgreementContinuesThroughApprovals() {
        var r=submit("CLD-AWS",12,false,Map.of("monthlyEstimate",3000,"termMonths",12));
        String manager=approvals.staff().stream().filter(s -> s.role()==StaffRole.CATEGORY_MANAGER)
            .findFirst().orElseThrow().id();
        r=strategic.decide(r.getId(),manager,StrategicService.Outcome.USE_EXISTING_AGREEMENT,"Use our AWS agreement");
        assertEquals(RequestStatus.PENDING_APPROVAL,r.getStatus());
        assertTrue(r.getApprovals().stream().anyMatch(s -> s.role().equals("CFO")));
        assertEquals(RequestStatus.ORDERED,approveAll(r).getStatus());
    }
    @Test void exhaustedBudgetRequiresExplicitFinanceApproval() {
        budgets.reserve("existing","PR-existing","Engineering",new BigDecimal("30000"),false,"fixture");
        var r=submit("LAP-STD",1,false,Map.of());
        assertEquals(RequestStatus.PENDING_APPROVAL,r.getStatus());
        assertTrue(r.getApprovals().stream().anyMatch(s -> s.role().equals("FINANCE_CONTROLLER")
            && s.reason().contains("budget")));
        r=approveAll(r);
        assertEquals(RequestStatus.POOLED,r.getStatus());
        assertEquals(-89900,budgets.account("Engineering").available());
    }
    @Test void confidentialNonCloudPurchaseRequiresSecurityReview() {
        var r=approveAll(submit("LAP-DEV",1,true,Map.of("dataClassification","Confidential")));
        r=requests.startSourcing(r.getId(),"BUYER","test");
        quote(r,"Vendor A","2000"); award(r);
        assertTrue(requests.get(r.getId()).getApprovals().stream().anyMatch(s -> s.role().equals("IT_SECURITY")),
            "Policy 6 requires sensitive-data review outside CLOUD too");
    }
    @Test void distinctQuotesFollowNormalApprovalAndOrder() {
        var r = sourcing();
        for (String s: List.of("Vendor A", "Vendor B", "Vendor C")) quote(r,s,"6000");
        award(r);
        r = approveAll(requests.get(r.getId()));
        assertEquals(RequestStatus.ORDERED, r.getStatus());
        assertEquals(600000, budgets.position(r.getId()).committed());
    }
    @Test void duplicateSupplierQuotesMustNotSatisfyCompetitiveSourcing() {
        var r = sourcing();
        for (int i=0;i<3;i++) quote(r,"Same Vendor","6000");
        assertThrows(IllegalStateException.class, () -> award(r), "Three identical quotes must not count as competition");
    }
    @Test void insufficientQuotesAndWrongApproverAreRejected() {
        var r = sourcing(); quote(r,"Vendor A","6000");
        assertThrows(IllegalStateException.class, () -> award(r));
        var normal = submit("LAP-DES", 1, false, Map.of());
        assertThrows(IllegalStateException.class,
            () -> approvals.decide(normal.getId(), "unknown", true, "test"));
    }
    @Disabled("Demo identity intentionally retained by user; production authentication deferred")
    @Test void anonymousBuyerCannotAcceptRequest() throws Exception {
        var r = approveAll(submit("LAP-DEV",1,true,Map.of()));
        http.perform(post("/api/buyer/requests/"+r.getId()+"/accept")).andExpect(status().is4xxClientError());
    }
    @Disabled("Demo identity intentionally retained by user; production authentication deferred")
    @Test void arbitraryHeaderMustNotImpersonateFinance() throws Exception {
        String cfo = approvals.staff().stream().filter(s -> s.role()==StaffRole.CFO).findFirst().orElseThrow().id();
        http.perform(get("/api/budgets").header("X-Staff-Id",cfo)).andExpect(status().is4xxClientError());
    }
    @Test void nonBudgetRoleIsDeniedFigures() throws Exception {
        String it = approvals.staff().stream().filter(s -> s.role()==StaffRole.IT_ASSET_MANAGER).findFirst().orElseThrow().id();
        http.perform(get("/api/budgets").header("X-Staff-Id",it)).andExpect(status().isForbidden());
    }
    @Test void modelPolicyToolMustNotRevealDepartmentBudgetAmounts() throws Exception {
        var tool = new com.compras.procurement.intake.tools.ReadPolicyTool();
        var result = tool.execute(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"section\":\"8\"}"), null);
        assertFalse(result.payload().toString().contains("30,000"),
            "Model-facing policy text exposes Engineering allocation despite budget barrier");
    }
    @Test void reserveRetryAfterLedgerFailureMustNotDoubleCharge() {
        MongoBudgetStore flaky = spy(store);
        doThrow(new IllegalStateException("injected atomic write outage")).doCallRealMethod()
            .when(flaky).compareAndSet(any(),any());
        BudgetService service = new BudgetService(flaky,budgetProperties,employees,clock);
        long before=service.account("Engineering").available();
        assertThrows(IllegalStateException.class,
            () -> service.reserve("fault","PR-fault","Engineering",new BigDecimal("1000"),false,"test"));
        service.reserve("fault","PR-fault","Engineering",new BigDecimal("1000"),false,"test");
        assertEquals(before-100000,service.account("Engineering").available(),"Retry must reserve exactly once");
    }
    @Test void commitRetryAfterBalanceFailureMustCompleteMovement() {
        budgets.reserve("commit-fault","PR-fault","Engineering",new BigDecimal("1000"),false,"test");
        MongoBudgetStore flaky = spy(store);
        // The write succeeds but the caller loses its acknowledgement, then retries.
        doAnswer(call -> {call.callRealMethod(); throw new IllegalStateException("lost write acknowledgement");})
            .doCallRealMethod().when(flaky).compareAndSet(any(),any());
        BudgetService service = new BudgetService(flaky,budgetProperties,employees,clock);
        assertThrows(IllegalStateException.class,
            () -> service.commit("commit-fault","PR-fault","Engineering",new BigDecimal("900"),"test"));
        service.commit("commit-fault","PR-fault","Engineering",new BigDecimal("900"),"test");
        assertEquals(0,service.account("Engineering").reserved());
        assertEquals(90000,service.account("Engineering").committed());
    }
    @Test void concurrentCommitAndCancelCannotReleaseSameReservationTwice() throws Exception {
        budgets.reserve("race","PR-race","Engineering",new BigDecimal("1000"),false,"test");
        MongoBudgetStore concurrent=spy(store);
        BudgetService service=new BudgetService(concurrent,budgetProperties,employees,clock);
        CyclicBarrier barrier=new CyclicBarrier(2);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {if(calls.incrementAndGet()<=2) barrier.await(5,TimeUnit.SECONDS);return call.callRealMethod();})
            .when(concurrent).compareAndSet(any(),any());
        try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
            var commit=pool.submit(() -> service.commit("race","PR-race","Engineering",new BigDecimal("1000"),"test"));
            var release=pool.submit(() -> service.release("race","PR-race","test","cancelled"));
            commit.get(10,TimeUnit.SECONDS); release.get(10,TimeUnit.SECONDS);
        }
        assertEquals(0,budgets.account("Engineering").reserved(),"Reserved balance must never be negative");
        assertEquals(1,store.entriesForRequest("race").stream().filter(e -> e.type()!=BudgetEntry.Type.RESERVE).count());
    }

    @Test void reservationLostAcknowledgementIsIdempotent() {
        MongoBudgetStore flaky=spy(store);
        doAnswer(call -> {call.callRealMethod();throw new IllegalStateException("lost acknowledgement");})
            .doCallRealMethod().when(flaky).compareAndSet(any(),any());
        BudgetService service=new BudgetService(flaky,budgetProperties,employees,clock);
        assertThrows(IllegalStateException.class, () ->
            service.reserve("ack","PR-ack","Engineering",new BigDecimal("1000"),false,"test"));
        assertEquals(BudgetService.Outcome.ALREADY_RESERVED,
            service.reserve("ack","PR-ack","Engineering",new BigDecimal("1000"),false,"test"));
        assertEquals(2900000,budgets.account("Engineering").available());
        assertEquals(1,store.entriesForRequest("ack").size());
    }

    @Test void legacyAccountAndLedgerUpgradeWithoutDoubleCharging() {
        var account=budgets.account("Engineering");
        // Reproduce the pre-upgrade schema, with an existing 1000 EUR reservation.
        mongo.getCollection("budget_accounts").updateOne(new org.bson.Document("_id",account.id()),
            new org.bson.Document("$unset",new org.bson.Document("revision","").append("journal",""))
                .append("$set",new org.bson.Document("available",2900000L).append("reserved",100000L)));
        mongo.insert(new BudgetEntry("legacy:RESERVE",account.id(),"legacy","PR-legacy",BudgetEntry.Type.RESERVE,
            100000,0,false,"test","legacy",Instant.now(clock)));
        assertEquals(BudgetService.Outcome.ALREADY_RESERVED,
            budgets.reserve("legacy","PR-legacy","Engineering",new BigDecimal("1000"),false,"test"));
        budgets.commit("legacy","PR-legacy","Engineering",new BigDecimal("900"),"test");
        budgets.commit("legacy","PR-legacy","Engineering",new BigDecimal("900"),"test");
        assertEquals(2910000,budgets.account("Engineering").available());
        assertEquals(0,budgets.account("Engineering").reserved());
        assertEquals(90000,budgets.account("Engineering").committed());
        assertEquals(2,store.entriesForRequest("legacy").size());
    }

    @Test void duplicateSupplierCasingStillRequiresException() {
        var r=sourcing();
        for(String name:List.of("Same Vendor"," SAME VENDOR ","Same   Vendor")) quote(r,name,"6000");
        assertThrows(IllegalStateException.class, () -> award(r));
        requests.award(r.getId(),0,"Custom device","custom requirement",List.of(),
            "Other vendors declined this RFQ","test",router::quotesRequired);
        assertTrue(requests.get(r.getId()).getSourced().buyerNote().contains("Other vendors declined"));
    }

    @Test void commitFailureBeforeAtomicWriteLeavesReservationRetryable() {
        budgets.reserve("before","PR-before","Engineering",new BigDecimal("1000"),false,"test");
        MongoBudgetStore flaky=spy(store);
        doThrow(new IllegalStateException("write unavailable")).doCallRealMethod()
            .when(flaky).compareAndSet(any(),any());
        BudgetService service=new BudgetService(flaky,budgetProperties,employees,clock);
        assertThrows(IllegalStateException.class, () ->
            service.commit("before","PR-before","Engineering",new BigDecimal("900"),"test"));
        assertEquals(100000,budgets.position("before").reserved());
        assertEquals(1,store.entriesForRequest("before").size());
        service.commit("before","PR-before","Engineering",new BigDecimal("900"),"test");
        assertEquals(90000,budgets.account("Engineering").committed());
        assertEquals(2910000,budgets.account("Engineering").available());
    }

    @Test void releaseLostAcknowledgementCannotRefundTwice() {
        budgets.reserve("refund","PR-refund","Engineering",new BigDecimal("1000"),false,"test");
        MongoBudgetStore flaky=spy(store);
        doAnswer(call -> {call.callRealMethod();throw new IllegalStateException("lost acknowledgement");})
            .doCallRealMethod().when(flaky).compareAndSet(any(),any());
        BudgetService service=new BudgetService(flaky,budgetProperties,employees,clock);
        assertThrows(IllegalStateException.class, () -> service.release("refund","PR-refund","test","cancel"));
        service.release("refund","PR-refund","test","cancel");
        assertEquals(3000000,budgets.account("Engineering").available());
        assertEquals(0,budgets.account("Engineering").reserved());
        assertEquals(2,store.entriesForRequest("refund").size());
    }

    @Test void realMongoConcurrentReservationsCannotOverspend() throws Exception {
        BudgetService service=new BudgetService(store,
            new BudgetProperties(Map.of("Engineering",new BigDecimal("5000")),3),employees,clock);
        service.account("Engineering");
        CountDownLatch start=new CountDownLatch(1);
        List<Future<BudgetService.Outcome>> outcomes=new ArrayList<>();
        try(ExecutorService pool=Executors.newFixedThreadPool(8)) {
            for(int i=0;i<20;i++) {
                String id="parallel-"+i;
                outcomes.add(pool.submit(() -> {start.await();return service.reserve(id,id,"Engineering",
                    new BigDecimal("1000"),false,"test");}));
            }
            start.countDown();
            int accepted=0;
            for(var outcome:outcomes) if(outcome.get(15,TimeUnit.SECONDS)==BudgetService.Outcome.RESERVED) accepted++;
            assertEquals(5,accepted);
        }
        assertEquals(0,service.account("Engineering").available());
        assertEquals(500000,service.account("Engineering").reserved());
        assertEquals(5,service.ledger("Engineering").size());
    }
}
