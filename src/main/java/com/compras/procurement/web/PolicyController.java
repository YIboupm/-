package com.compras.procurement.web;

import com.compras.procurement.approval.ApprovalService;
import com.compras.procurement.approval.StaffMember;
import com.compras.procurement.approval.StaffRole;
import com.compras.procurement.policy.Facts;
import com.compras.procurement.policy.PolicyFiles;
import com.compras.procurement.policy.PolicyRegistry;
import com.compras.procurement.policy.PolicySet;
import com.compras.procurement.policy.PolicySimulator;
import com.compras.procurement.policy.PolicyValidator;
import com.compras.procurement.policy.RuleEngine;
import com.compras.procurement.shared.ForbiddenException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The procurement policy as data: read the version in force, prepare a draft, check it (structure, standard cases,
 * replay of past requests) and publish it. Reading is open to everyone; changing is for the policy owners.
 */
@RestController
@RequestMapping("/api/policy")
public class PolicyController {

    public record Summary(String version, String status, String effectiveFrom, String note, String basedOn,
                          String publishedBy, String publishedAt) {
        static Summary of(PolicySet p) {
            return new Summary(p.version(), p.status(), p.effectiveFrom(), p.note(), p.basedOn(), p.publishedBy(),
                    p.publishedAt());
        }
    }

    public record NewDraft(String version, String note) {
    }

    public record Publish(int acknowledgedChanges) {
    }

    public record Saved(PolicySet policy, PolicySimulator.Report report) {
    }

    private final PolicyRegistry registry;
    private final PolicySimulator simulator;
    private final ApprovalService approvals;

    public PolicyController(PolicyRegistry registry, PolicySimulator simulator, ApprovalService approvals) {
        this.registry = registry;
        this.simulator = simulator;
        this.approvals = approvals;
    }

    @GetMapping("/versions")
    public List<Summary> versions() {
        return registry.all().stream().map(Summary::of).toList();
    }

    @GetMapping("/active")
    public PolicySet active() {
        return registry.active();
    }

    @GetMapping("/versions/{version}")
    public PolicySet version(@PathVariable String version) {
        return registry.get(version);
    }

    /** What rules can be written with: the facts, operators, step building blocks and roles the code provides. */
    @GetMapping("/vocabulary")
    public Map<String, Object> vocabulary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("facts", Facts.VOCABULARY);
        out.put("operators", RuleEngine.OPS.stream().sorted().toList());
        out.put("stepTypes", PolicyValidator.STEP_TYPES.stream().sorted().toList());
        out.put("roles", Arrays.stream(StaffRole.values()).map(Enum::name).toList());
        return out;
    }

    /** A draft that starts as a copy of the version in force. */
    @PostMapping("/drafts/from-active")
    public PolicySet draftFromActive(@RequestHeader("X-Staff-Id") String staffId, @RequestBody NewDraft body) {
        owner(staffId);
        PolicySet active = registry.active();
        return registry.saveDraft(active.asDraft(body.version(), active.version(), body.note()));
    }

    /** Saves the draft (the whole version as JSON) and checks it. An invalid draft is saved too, with its problems. */
    @PostMapping("/drafts")
    public Saved saveDraft(@RequestHeader("X-Staff-Id") String staffId, @RequestBody String json) {
        owner(staffId);
        PolicySet saved = registry.saveDraft(PolicyFiles.parse(json));
        return new Saved(saved, simulator.check(saved));
    }

    @DeleteMapping("/drafts/{version}")
    public void deleteDraft(@PathVariable String version, @RequestHeader("X-Staff-Id") String staffId) {
        owner(staffId);
        registry.deleteDraft(version);
    }

    /** Structure, standard cases, and what would change for past requests. Changes nothing. */
    @PostMapping("/versions/{version}/check")
    public PolicySimulator.Report check(@PathVariable String version) {
        return simulator.check(registry.get(version));
    }

    /**
     * Publishes a draft. Refused while it has problems or failing cases, and unless the publisher states how many
     * past requests would be judged differently (so the replay was actually read).
     */
    @PostMapping("/versions/{version}/publish")
    public PolicySet publish(@PathVariable String version, @RequestHeader("X-Staff-Id") String staffId,
                             @RequestBody Publish body) {
        StaffMember who = owner(staffId);
        PolicySimulator.Report report = simulator.check(registry.get(version));
        if (!report.publishable()) {
            throw new IllegalStateException("Version " + version + " cannot be published: "
                    + (report.problems().isEmpty() ? report.failedCases() : report.problems()).get(0));
        }
        if (body == null || body.acknowledgedChanges() != report.replay().size()) {
            throw new IllegalStateException("Run the check first: " + report.replay().size()
                    + " past request(s) would be judged differently under this version.");
        }
        return registry.publish(version, who.name() + " (" + who.role().label() + ")");
    }

    private StaffMember owner(String staffId) {
        StaffMember who = approvals.member(staffId);
        if (who.role() != StaffRole.CATEGORY_MANAGER && who.role() != StaffRole.CFO) {
            throw new ForbiddenException("Only the policy owners (category manager, CFO) change the procurement policy.");
        }
        return who;
    }
}
