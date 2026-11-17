package com.compras.procurement.policy;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The policy versions: one ACTIVE (judges new requests), earlier ones RETIRED (requests in flight finish under the
 * version they started with), and DRAFTs being prepared. A new installation starts from the version shipped in
 * {@code policy/policy-rules.json}.
 */
@Service
public class PolicyRegistry implements PolicySource {

    private final PolicyVersionRepository versions;
    private final Clock clock;
    private volatile PolicySet active;

    public PolicyRegistry(PolicyVersionRepository versions, Clock clock) {
        this.versions = versions;
        this.clock = clock;
    }

    @Override
    public PolicySet active() {
        PolicySet a = active;
        if (a == null) {
            synchronized (this) {
                a = versions.findAll().stream().filter(v -> PolicySet.ACTIVE.equals(v.status())).findFirst()
                        .orElseGet(() -> versions.save(PolicyFiles.builtin()
                                .withStatus(PolicySet.ACTIVE, "Shipped with the application", Instant.now(clock).toString())));
                a = upgrade(a);
                active = a;
            }
        }
        return a;
    }

    @Override
    public PolicySet version(String version) {
        if (version == null || version.equals(active().version())) {
            return active();
        }
        return versions.findById(version).map(this::upgrade).orElseGet(this::active);
    }

    /**
     * Section 13 (receiving and asset registration) came after the first versions were published. A version
     * without asset rules gets the shipped ones, once, recorded in the version itself; nothing else changes.
     */
    private PolicySet upgrade(PolicySet v) {
        if (v.assets() != null && !v.assets().isEmpty()) {
            return v;
        }
        return versions.save(v.withAssets(PolicyFiles.builtin().assets()));
    }

    public PolicySet get(String version) {
        active();
        return versions.findById(version).map(v -> PolicySet.DRAFT.equals(v.status()) ? v : upgrade(v)).orElseThrow(() ->
                new com.compras.procurement.shared.NotFoundException("Unknown policy version " + version));
    }

    public List<PolicySet> all() {
        active();
        return versions.findAll().stream()
                .sorted(Comparator.comparing((PolicySet v) -> v.publishedAt() == null ? "9" : v.publishedAt()).reversed())
                .toList();
    }

    /** Creates or replaces a draft. Published versions are never edited: a change is a new version. */
    public PolicySet saveDraft(PolicySet draft) {
        active();
        List<String> structure = PolicyValidator.problems(draft).stream()
                .filter(p -> p.startsWith("'version'")).toList();
        if (!structure.isEmpty()) {
            throw new IllegalStateException(structure.get(0));
        }
        versions.findById(draft.version()).ifPresent(existing -> {
            if (!PolicySet.DRAFT.equals(existing.status())) {
                throw new IllegalStateException("Version " + draft.version() + " is already published. Published "
                        + "versions are not edited; choose a new version name.");
            }
        });
        return versions.save(new PolicySet(draft.version(), PolicySet.DRAFT, draft.effectiveFrom(), draft.note(),
                draft.basedOn() == null ? active().version() : draft.basedOn(), null, null, draft.parameters(),
                draft.routing(), draft.approvals(), draft.autoApprovals(), draft.quotes(), draft.routes(),
                draft.cases(), draft.assets()));
    }

    public void deleteDraft(String version) {
        PolicySet v = get(version);
        if (!PolicySet.DRAFT.equals(v.status())) {
            throw new IllegalStateException("Only drafts can be deleted; published versions are kept for audit.");
        }
        versions.deleteById(version);
    }

    /** The draft becomes the version in force; the previous one is retired but kept for requests in flight. */
    public synchronized PolicySet publish(String version, String by) {
        PolicySet draft = get(version);
        if (!PolicySet.DRAFT.equals(draft.status())) {
            throw new IllegalStateException("Version " + version + " is not a draft.");
        }
        String now = Instant.now(clock).toString();
        PolicySet previous = active();
        versions.save(previous.withStatus(PolicySet.RETIRED, null, null));
        PolicySet published = versions.save(new PolicySet(draft.version(), PolicySet.ACTIVE,
                java.time.LocalDate.now(clock).toString(), draft.note(), draft.basedOn(), by, now, draft.parameters(),
                draft.routing(), draft.approvals(), draft.autoApprovals(), draft.quotes(), draft.routes(),
                draft.cases(), draft.assets()));
        active = published;
        return published;
    }
}
