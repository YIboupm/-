package com.compras.procurement.receiving;

import com.compras.procurement.approval.StaffMember;
import com.compras.procurement.approval.StaffRepository;
import com.compras.procurement.approval.StaffRole;
import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.consolidation.ConsolidatedOrder;
import com.compras.procurement.consolidation.ConsolidatedOrderRepository;
import com.compras.procurement.consolidation.ReleaseReason;
import com.compras.procurement.context.Asset;
import com.compras.procurement.context.AssetRepository;
import com.compras.procurement.policy.AssetPolicy;
import com.compras.procurement.policy.PolicySet;
import com.compras.procurement.policy.PolicySource;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.RequestStatus;
import com.compras.procurement.shared.Events;
import com.compras.procurement.shared.ForbiddenException;
import com.compras.procurement.shared.NotFoundException;
import com.compras.procurement.shared.SequenceService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Goods-in (policy 13). When a purchase order is issued, a receipt is pre-registered from what the order already
 * knows. When goods arrive, the receiver (IT asset manager for IT equipment, facilities for the rest, per the asset
 * rules) checks them in: quantities, serial numbers, the model actually delivered. Registered items become assets,
 * handed to the requester or reserved for the request. A request whose goods have all arrived is RECEIVED and its
 * commitment becomes spend.
 *
 * <p>The model helps at two points and decides neither: it says what a non-catalog item is (the rules then decide
 * whether it is registered), and it reads a delivery note against the order (the code checks what it read, the
 * receiver confirms).
 */
@Service
public class ReceivingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReceivingService.class);

    /** What the receiver confirms at the dock. */
    public record CheckIn(int quantity, List<String> serialNumbers, String modelAsDelivered, int rejected,
                          String rejectionReason, String note, boolean aiAssisted, List<String> aiWarnings) {
    }

    /** A delivery note as read by the model, with the system's own checks of it, to prefill the check-in form. */
    public record Reading(DeliveryNoteReader.Extract extract, Integer quantity, List<String> serialNumbers,
                          String modelAsDelivered, List<String> warnings) {
    }

    public static final String IT_CUSTODIAN = "IT asset management";
    public static final String FACILITIES_CUSTODIAN = "Office & facilities";

    private final ReceiptRepository receipts;
    private final ConsolidatedOrderRepository orders;
    private final RequestService requests;
    private final CatalogService catalog;
    private final PolicySource policies;
    private final AssetRepository assets;
    private final StaffRepository staff;
    private final SequenceService sequences;
    private final AssetClassifier classifier;
    private final DeliveryNoteReader reader;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ReceivingService(ReceiptRepository receipts, ConsolidatedOrderRepository orders, RequestService requests,
                            CatalogService catalog, PolicySource policies, AssetRepository assets,
                            StaffRepository staff, SequenceService sequences, AssetClassifier classifier,
                            DeliveryNoteReader reader, ApplicationEventPublisher events, Clock clock) {
        this.receipts = receipts;
        this.orders = orders;
        this.requests = requests;
        this.catalog = catalog;
        this.policies = policies;
        this.assets = assets;
        this.staff = staff;
        this.sequences = sequences;
        this.classifier = classifier;
        this.reader = reader;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ pre-registration

    /**
     * Never lets goods-in break ordering: the order is already issued when this runs. If pre-registration fails
     * (e.g. the model times out), the receipt is created on the next visit to the goods-in page.
     */
    @EventListener
    public void on(Events.OrderReleased event) {
        try {
            orders.findById(event.orderId()).ifPresent(this::expect);
        } catch (RuntimeException e) {
            log.warn("Receipt for order {} not pre-registered yet: {}", event.orderNumber(), e.getMessage());
        }
    }

    /**
     * The receipt an order should produce. Idempotent. Framework call-offs (cloud) deliver a service, not goods:
     * there is nothing to check in.
     */
    public Receipt expect(ConsolidatedOrder order) {
        var existing = receipts.findByOrderId(order.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (order.cancelled()
                || (order.reasons() != null && order.reasons().contains(ReleaseReason.FRAMEWORK_CALL_OFF))) {
            return null;
        }
        CatalogItem item = "SPECIAL".equals(order.sku()) ? null : catalog.findItem(order.sku()).orElse(null);
        List<Receipt.Line> lines = new ArrayList<>();
        PurchaseRequest first = null;
        for (ConsolidatedOrder.Allocation a : order.allocations()) {
            PurchaseRequest r = requests.get(a.requestId());
            first = first == null ? r : first;
            boolean forRequester = a.quantity() == 1 && !hasHeadcount(r);
            lines.add(new Receipt.Line(r.getId(), r.getNumber(), r.getRequester().employeeId(), r.getRequester().name(),
                    a.department(), a.quantity(), forRequester, a.neededBy()));
        }
        Receipt receipt = new Receipt(order.id(), order.number(), order.sku(), order.itemName(), order.supplierName(),
                order.deliverySite(), item != null ? item.categoryCode() : first == null ? null : first.getCategoryCode(),
                order.unitPrice(), order.currency(), item == null ? Map.of() : item.attributes(), lines,
                Instant.now(clock));
        if (item == null && first != null) {
            // a sourced item: the receiver checks the goods against what was awarded and the employee's must-haves
            PurchaseRequest r = first;
            var award = r.getAwards().stream()
                    .filter(a -> order.sourceItemId() == null || order.sourceItemId().equals(a.itemId())).findFirst();
            var line = r.itemsOrDefault().stream()
                    .filter(i -> award.map(a -> a.itemId().equals(i.id())).orElse(true)).findFirst();
            receipt.award(award.map(com.compras.procurement.request.Award::description)
                            .orElse(r.getSourced() == null ? null : r.getSourced().description()),
                    award.map(com.compras.procurement.request.Award::reference).orElse(null),
                    line.map(i -> i.requirements().stream().map(q -> q.description() + (q.isMust() ? "" : " (preference)"))
                            .toList()).orElse(List.of()));
        }
        AssetClass cls = item == null ? null : AssetClass.parse(item.assetClass()).orElse(null);
        if (cls != null) {
            receipt.classify(cls, "CATALOG", "Catalog item " + item.sku());
        } else {
            AssetClassifier.Result r = classifier.classify(order.number(), order.itemName(), context(first));
            // (the order's item name is the awarded line, e.g. "Chair · Ergonomic chair Pro")
            receipt.classify(r.assetClass(), r.by(), r.note());
        }
        decide(receipt);
        return receipts.save(receipt);
    }

    /** The order was cancelled before delivery: nothing is expected any more. */
    @EventListener
    public void on(Events.OrderCancelled event) {
        receipts.findByOrderId(event.orderId()).ifPresent(r -> {
            if (!r.getDeliveries().isEmpty()) {
                throw new IllegalStateException(r.getOrderNumber() + " already has goods checked in: return them first.");
            }
            r.cancel(event.reason());
            receipts.save(r);
        });
    }

    /** Whether any goods of this request have been checked in (then its order can no longer be cancelled). */
    public boolean anythingReceived(String requestId) {
        return receipts.findByLinesRequestId(requestId).stream().anyMatch(r -> !r.getDeliveries().isEmpty());
    }

    /** Orders issued before receiving existed get their receipts too. */
    public void backfill() {
        Set<String> known = receipts.findAll().stream().map(Receipt::getOrderId).collect(Collectors.toSet());
        for (ConsolidatedOrder o : orders.findAll()) {
            if (!known.contains(o.id())) {
                try {
                    expect(o);
                } catch (RuntimeException e) {
                    log.warn("Receipt for order {} could not be created: {}", o.number(), e.getMessage());
                }
            }
        }
    }

    /** The receiver corrects what the item is (e.g. the model said OTHER for a monitor): the rules decide again. */
    public Receipt reclassify(String receiptId, String staffId, String assetClass) {
        Receipt receipt = get(receiptId);
        receiverOf(receipt, staffId);
        if (!receipt.getDeliveries().isEmpty()) {
            throw new IllegalStateException("Goods were already checked in on " + receipt.getOrderNumber()
                    + "; the class can no longer change.");
        }
        AssetClass cls = AssetClass.parse(assetClass).orElseThrow(() ->
                new IllegalArgumentException("Unknown class '" + assetClass + "'."));
        StaffMember who = staff.findById(staffId).orElseThrow();
        receipt.classify(cls, "RECEIVER", "Set by " + who.name());
        decide(receipt);
        return receipts.save(receipt);
    }

    private void decide(Receipt receipt) {
        PolicySet policy = policies.active();
        AssetClass cls = receipt.assetClassOrOther();
        AssetPolicy.Decision d = AssetPolicy.decide(policy, AssetPolicy.facts(cls.name(), cls.itDevice(),
                receipt.getUnitPrice(), receipt.getCategoryCode()));
        receipt.decide(d.track(), d.receiver(), d.ruleId(), d.reason(), policy.version());
    }

    // ------------------------------------------------------------------ at the dock

    /** Receipts for this receiver's role, open ones first. */
    public List<Receipt> forReceiver(String staffId) {
        StaffMember who = member(staffId);
        backfill();
        return receipts.findAll().stream()
                .filter(r -> who.role().name().equals(r.getReceiverRole()))
                .filter(r -> r.getStatus() != Receipt.Status.CANCELLED)
                .sorted(Comparator.comparing((Receipt r) -> r.getStatus() == Receipt.Status.RECEIVED)
                        .thenComparing(Receipt::getCreatedAt, Comparator.reverseOrder()))
                .toList();
    }

    public Receipt get(String id) {
        return receipts.findById(id).orElseThrow(() -> new NotFoundException("Unknown receipt " + id));
    }

    public Reading read(String receiptId, String staffId, String imageDataUrl, String text) {
        Receipt receipt = get(receiptId);
        receiverOf(receipt, staffId);
        open(receipt);
        DeliveryNoteReader.Extract e = reader.read(receipt, imageDataUrl, text);
        List<String> warnings = new ArrayList<>();
        if (e.orderNumberOnNote() != null && !normal(e.orderNumberOnNote()).contains(normal(receipt.getOrderNumber()))) {
            warnings.add("The note refers to order " + e.orderNumberOnNote() + ", not " + receipt.getOrderNumber()
                    + ": check that it is the right delivery.");
        }
        if (e.supplierOnNote() != null && receipt.getSupplierName() != null
                && !similar(e.supplierOnNote(), receipt.getSupplierName())) {
            warnings.add("Supplier on the note: " + e.supplierOnNote() + "; ordered from " + receipt.getSupplierName() + ".");
        }
        Integer quantity = e.quantity();
        if (quantity != null && quantity > receipt.outstanding()) {
            warnings.add("The note shows " + quantity + " unit(s) but only " + receipt.outstanding()
                    + " are still expected on this order.");
            quantity = receipt.outstanding();
        }
        List<String> serials = clean(e.serialNumbers());
        List<String> problems = serialProblems(serials);
        warnings.addAll(problems);
        if (receipt.isTracked() && receipt.isItDevice() && quantity != null && serials.size() != quantity) {
            warnings.add("Serial numbers read: " + serials.size() + " for " + quantity
                    + " unit(s). Scan the missing ones from the labels.");
        }
        String model = receipt.getItemName();
        if (Boolean.FALSE.equals(e.sameItemAsOrdered())) {
            warnings.add("Possibly a different item from the one ordered"
                    + (e.differences() == null ? "" : ": " + e.differences())
                    + ". Accept it only with a note (it is recorded on the asset), or send it back.");
            model = e.itemOnNote() == null ? model : e.itemOnNote();
        }
        if (e.damage() != null && !e.damage().isBlank()) {
            warnings.add("The note mentions damage or a shortage: " + e.damage());
        }
        if (e.otherItems() != null && !e.otherItems().isEmpty()) {
            warnings.add("The note also lists " + String.join("; ", e.otherItems())
                    + ": those belong to other orders and are checked in there.");
        }
        if ("low".equalsIgnoreCase(e.confidence())) {
            warnings.add("The note was hard to read: check every value against the goods.");
        }
        return new Reading(e, quantity, serials, model, warnings);
    }

    /** Checks goods in. Registered items become assets; requests whose goods have all arrived become RECEIVED. */
    public Receipt checkIn(String receiptId, String staffId, CheckIn in) {
        Receipt receipt = get(receiptId);
        StaffMember who = receiverOf(receipt, staffId);
        open(receipt);
        int outstanding = receipt.outstanding();
        if (in.quantity() < 0 || in.rejected() < 0 || in.quantity() + in.rejected() == 0) {
            throw new IllegalArgumentException("Enter how many units you accept (and how many you send back, if any).");
        }
        if (in.quantity() > outstanding) {
            throw new IllegalArgumentException("Only " + outstanding + " unit(s) are still expected on "
                    + receipt.getOrderNumber() + ".");
        }
        if (in.rejected() > outstanding - in.quantity()) {
            throw new IllegalArgumentException("Accepted plus sent back is more than the " + outstanding
                    + " unit(s) expected.");
        }
        if (in.rejected() > 0 && blank(in.rejectionReason())) {
            throw new IllegalArgumentException("Say why units are sent back (damaged, wrong item...).");
        }
        String model = blank(in.modelAsDelivered()) ? receipt.getItemName() : in.modelAsDelivered().trim();
        boolean substituted = !model.equalsIgnoreCase(receipt.getItemName());
        if (substituted && in.quantity() > 0 && blank(in.note())) {
            throw new IllegalArgumentException("The delivered item (" + model + ") is not the one ordered ("
                    + receipt.getItemName() + "). Add a note saying why it is accepted, or send it back.");
        }
        List<String> serials = clean(in.serialNumbers());
        if (receipt.isTracked() && in.quantity() > 0) {
            if (receipt.isItDevice() && serials.size() != in.quantity()) {
                throw new IllegalArgumentException("IT equipment needs one serial number per unit: " + serials.size()
                        + " given for " + in.quantity() + ".");
            }
            if (!receipt.isItDevice() && !serials.isEmpty() && serials.size() != in.quantity()) {
                throw new IllegalArgumentException("Give a serial number for every unit, or none: " + serials.size()
                        + " given for " + in.quantity() + ".");
            }
            List<String> problems = serialProblems(serials);
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join(" ", problems));
            }
        } else {
            serials = List.of(); // not registered: nothing to record per unit
        }

        // hand the units out to the requests on the order, earliest need first
        Instant now = Instant.now(clock);
        List<String> tags = new ArrayList<>();
        int left = in.quantity();
        int unit = 0;
        List<Receipt.Line> queue = receipt.getLines().stream().filter(l -> l.outstanding() > 0)
                .sorted(Comparator.comparing(Receipt.Line::getNeededBy, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Receipt.Line::getRequestNumber))
                .toList();
        for (Receipt.Line line : queue) {
            int units = Math.min(left, line.outstanding());
            for (int i = 0; i < units && receipt.isTracked(); i++) {
                Asset asset = register(receipt, line, model, substituted,
                        serials.isEmpty() ? null : serials.get(unit), who, now);
                tags.add(asset.assetTag());
                unit++;
            }
            line.take(units);
            left -= units;
        }
        int sendBack = in.rejected();
        for (Receipt.Line line : queue) {
            int units = Math.min(sendBack, line.outstanding());
            line.sentBack(units);
            sendBack -= units;
        }
        receipt.record(new Receipt.Delivery(now, who.id(), who.name(), in.quantity(), in.rejected(),
                blank(in.rejectionReason()) ? null : in.rejectionReason().trim(), model, serials, tags,
                blank(in.note()) ? null : in.note().trim(), in.aiAssisted(),
                in.aiWarnings() == null ? List.of() : in.aiWarnings()));
        Receipt saved = receipts.save(receipt);
        events.publishEvent(new Events.GoodsReceived(saved.getId(), saved.getOrderNumber(), in.quantity(),
                in.rejected(), tags, who.name(), now));
        saved.getLines().stream().map(Receipt.Line::getRequestId).distinct().forEach(this::updateRequest);
        return saved;
    }

    private Asset register(Receipt receipt, Receipt.Line line, String model, boolean substituted, String serial,
                           StaffMember who, Instant now) {
        AssetClass cls = receipt.assetClassOrOther();
        String site = receipt.getDeliverySite() == null ? "" : receipt.getDeliverySite();
        String tag = sequences.nextNumber(cls.prefix() + "-" + siteCode(site));
        String custodian = cls.itDevice() ? IT_CUSTODIAN : FACILITIES_CUSTODIAN;
        boolean hand = line.isForRequester() && line.getRequesterId() != null;
        Map<String, Object> attributes = new LinkedHashMap<>(substituted ? Map.of() : receipt.getAttributes());
        Asset asset = new Asset(tag, cls.name(), model, site,
                hand ? site + ", with " + line.getRequesterName()
                        : site + ", store room (reserved for " + line.getRequestNumber() + ")",
                hand ? line.getRequesterId() : null, hand ? Asset.IN_USE : Asset.RESERVED, custodian, attributes,
                serial, new Asset.Acquisition(line.getRequestId(), line.getRequestNumber(), receipt.getOrderNumber(),
                receipt.getSupplierName(), receipt.getUnitPrice(), receipt.getCurrency(), now, who.name(),
                receipt.getId()));
        assets.save(asset);
        events.publishEvent(new Events.AssetRegistered(tag, model, serial, asset.assignedTo(), asset.status(),
                line.getRequestNumber(), now));
        return asset;
    }

    /** Delivery progress for the employee, and RECEIVED once every unit ordered for the request has arrived. */
    private void updateRequest(String requestId) {
        List<Receipt> all = receipts.findByLinesRequestId(requestId).stream()
                .filter(r -> r.getStatus() != Receipt.Status.CANCELLED).toList();
        int ordered = 0, received = 0, rejected = 0;
        boolean complete = true;
        for (Receipt r : all) {
            for (Receipt.Line l : r.getLines()) {
                if (requestId.equals(l.getRequestId())) {
                    ordered += l.getOrdered();
                    received += l.getReceived();
                    rejected += l.getRejected();
                    complete &= l.outstanding() == 0;
                }
            }
        }
        List<String> tags = assets.findAll().stream()
                .filter(a -> a.acquisition() != null && requestId.equals(a.acquisition().requestId()))
                .map(Asset::assetTag).toList();
        requests.recordDelivery(requestId, new PurchaseRequest.Delivery(ordered, received, rejected, tags));
        PurchaseRequest r = requests.get(requestId);
        // ORDERED means every line is on an order, so every receipt for it exists
        if (complete && r.getStatus() == RequestStatus.ORDERED) {
            requests.markReceived(requestId, tags, "All " + received + " unit(s) delivered"
                    + (tags.isEmpty() ? "" : "; assets " + String.join(", ", tags)));
        }
    }

    // ------------------------------------------------------------------ helpers

    private StaffMember member(String staffId) {
        return staff.findById(staffId == null ? "" : staffId)
                .orElseThrow(() -> new IllegalStateException("Unknown staff member"));
    }

    private StaffMember receiverOf(Receipt receipt, String staffId) {
        StaffMember who = member(staffId);
        if (!who.role().name().equals(receipt.getReceiverRole())) {
            throw new ForbiddenException(receipt.getOrderNumber() + " is received by the "
                    + StaffRole.valueOf(receipt.getReceiverRole()).label() + ".");
        }
        return who;
    }

    private static void open(Receipt receipt) {
        if (receipt.getStatus() == Receipt.Status.CANCELLED) {
            throw new IllegalStateException(receipt.getOrderNumber() + " was cancelled: " + receipt.getCancelReason());
        }
        if (receipt.getStatus() == Receipt.Status.RECEIVED) {
            throw new IllegalStateException(receipt.getOrderNumber() + " has been fully received.");
        }
    }

    private List<String> serialProblems(List<String> serials) {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> twice = new LinkedHashSet<>();
        for (String s : serials) {
            if (!seen.add(s.toUpperCase(Locale.ROOT))) {
                twice.add(s);
            }
        }
        if (!twice.isEmpty()) {
            problems.add("Serial number(s) entered twice: " + String.join(", ", twice) + ".");
        }
        List<String> known = serials.stream().filter(assets::existsBySerialNumber).toList();
        if (!known.isEmpty()) {
            problems.add("Already in the asset register: " + String.join(", ", known) + ".");
        }
        return problems;
    }

    static List<String> clean(List<String> serials) {
        return serials == null ? List.of() : serials.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    static String siteCode(String site) {
        String letters = site.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        return letters.isEmpty() ? "GEN" : letters.substring(0, Math.min(3, letters.length()));
    }

    private static boolean hasHeadcount(PurchaseRequest r) {
        Object h = r.getAttributes() == null ? null : r.getAttributes().get("headcount");
        return h != null && !String.valueOf(h).isBlank();
    }

    private static String context(PurchaseRequest r) {
        if (r == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (r.getTitle() != null) {
            sb.append("Title: ").append(r.getTitle()).append(". ");
        }
        if (r.getSourced() != null) {
            sb.append("Awarded: ").append(r.getSourced().description()).append(". ");
        }
        r.getRequirements().forEach(q -> sb.append(q.description()).append(". "));
        return sb.toString();
    }

    private static String normal(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static boolean similar(String a, String b) {
        String x = normal(a);
        String y = normal(b);
        String head = y.length() > 6 ? y.substring(0, 6) : y;
        return x.contains(head) || y.contains(x.length() > 6 ? x.substring(0, 6) : x);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
