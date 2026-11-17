package com.compras.procurement.intake;

import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.ResolutionStrategy;
import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.context.EmployeeContextService;
import com.compras.procurement.fulfillment.CompanyResource;
import com.compras.procurement.fulfillment.CompanyResourceService;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolRegistry;
import com.compras.procurement.llm.AgentMessage;
import com.compras.procurement.llm.ChatResult;
import com.compras.procurement.llm.ModelGateway;
import com.compras.procurement.llm.ToolCall;
import com.compras.procurement.request.NewRequest;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.shared.Events;
import com.compras.procurement.shared.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The intake agent loop, modelled on Codex CLI's turn loop: send the history and the tools to the model;
 * execute the tool calls it makes; append the results (including errors) to the history; repeat until the
 * model answers in text or a tool needs the employee (questions or a proposal to confirm).
 *
 * <p>The model plans the conversation. Code keeps four guarantees: facts come from tools, fit is judged by
 * code, every proposal is validated, and nothing is submitted without the employee's confirmation.
 */
@Service
public class AgentIntakeService {

    private static final String CHANNEL = "web-agent";

    /** One answer from the question cards. {@code value}/{@code label}: a picked option; {@code text}: "Other". */
    public record Answer(String questionId, String label, String value, String text) {
    }

    private final ConversationRepository conversations;
    private final EmployeeContextService employees;
    private final CatalogService catalog;
    private final CompanyResourceService resources;
    private final ToolRegistry tools;
    private final ModelGateway model;
    private final DraftEvaluator evaluator;
    private final RequestService requests;
    private final IntakeProperties properties;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentIntakeService(ConversationRepository conversations, EmployeeContextService employees,
                              CatalogService catalog, CompanyResourceService resources, ToolRegistry tools,
                              ModelGateway model, DraftEvaluator evaluator, RequestService requests,
                              IntakeProperties properties, ApplicationEventPublisher events, ObjectMapper objectMapper,
                              Clock clock) {
        this.conversations = conversations;
        this.employees = employees;
        this.catalog = catalog;
        this.resources = resources;
        this.tools = tools;
        this.model = model;
        this.evaluator = evaluator;
        this.requests = requests;
        this.properties = properties;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ commands from the browser

    public ConversationView start(String employeeId) {
        EmployeeContext context = employees.contextFor(employeeId);
        Conversation c = new Conversation(employeeId, now());
        c.say("assistant", "Hi " + context.employee().name().split(" ")[0] + ". Tell me what you need for your "
                + "work, in your own words. I'll check what the company already has and what we can buy.", now());
        return view(conversations.save(c));
    }

    /** Free text from the employee. If the agent was waiting on a question or a proposal, it answers that call. */
    public ConversationView message(String conversationId, String text) {
        Conversation c = loadOpen(conversationId);
        c.say("user", text, now());
        if (c.getPendingToolCallId() != null) {
            boolean wasProposal = c.proposalCallPending();
            String callId = c.resolvePending();
            c.addAgentMessage(AgentMessage.tool(callId, json(wasProposal
                    ? Map.of("confirmed", false, "submitted", false, "card_still_open", true,
                            "employee_wrote_instead", text,
                            "note", "The proposal is NOT submitted. Its card stays on screen and the employee can "
                                    + "still confirm it. Answer what they wrote. If the proposal must change, call the "
                                    + "propose tool again (the new card replaces it); otherwise do not propose again.")
                    : Map.of("answered_with_options", false, "employee_wrote_instead", text))));
        } else {
            c.addAgentMessage(AgentMessage.user(text));
        }
        return runLoop(c);
    }

    /** Answers to an ask_user call. */
    public ConversationView answer(String conversationId, List<Answer> answers) {
        Conversation c = loadOpen(conversationId);
        if (c.getStatus() != Conversation.Status.AWAITING_ANSWER) {
            throw new IllegalStateException("There is no open question.");
        }
        Map<String, AgentQuestion> asked = new LinkedHashMap<>();
        c.getPendingQuestions().forEach(q -> asked.put(q.id(), q));
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        for (Answer a : answers == null ? List.<Answer>of() : answers) {
            AgentQuestion q = asked.get(a.questionId());
            if (q == null) {
                continue;
            }
            boolean other = a.text() != null && !a.text().isBlank();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question_id", q.id());
            m.put("question", q.question());
            if (other) {
                m.put("other_text", a.text().trim());
                shown.add(q.question() + " — " + a.text().trim());
            } else {
                m.put("selected_label", a.label());
                if (a.value() != null) {
                    m.put("selected_value", a.value());
                }
                shown.add(q.question() + " — " + a.label());
            }
            result.add(m);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Choose an option or type an answer first.");
        }
        asked.keySet().stream().filter(id -> result.stream().noneMatch(r -> id.equals(r.get("question_id"))))
                .forEach(id -> result.add(Map.of("question_id", id, "skipped", true)));
        c.say("user", String.join("\n", shown), now());
        String callId = c.resolvePending();
        c.addAgentMessage(AgentMessage.tool(callId, json(Map.of("answers", result))));
        return runLoop(c);
    }

    /** The employee confirmed the proposal card: this is the only path that creates anything. */
    public ConversationView confirmProposal(String conversationId) {
        Conversation c = loadOpen(conversationId);
        Proposal p = c.getProposal();
        if (p == null) {
            throw new IllegalStateException("There is no proposal to confirm.");
        }
        EmployeeContext context = employees.contextFor(c.getEmployeeId());
        boolean callIsProposal = c.proposalCallPending();
        String message;
        Conversation.Status status;
        String requestId = null;
        switch (p.type()) {
            case INTERNAL_USE -> {
                CompanyResource now = resources.find(p.resource().id()).orElseThrow(() ->
                        new IllegalStateException("The resource is no longer available."));
                if (now.busy()) {
                    throw new IllegalStateException(now.name() + " is busy right now. Try again later or ask the "
                            + "assistant for alternatives.");
                }
                events.publishEvent(new Events.InternalUseConfirmed(c.getId(), now.id(), now.name(),
                        p.avoidedSpend(), now()));
                message = "Confirmed: you will use " + now.name() + ". No purchase was made"
                        + (p.avoidedSpend() == null ? "" : " (avoided spend " + p.avoidedSpend() + " " + p.currency() + ")")
                        + ". " + now.custodian() + " will grant access.";
                status = Conversation.Status.FULFILLED_INTERNAL;
            }
            case STANDARD -> {
                CategoryDefinition category = catalog.category(p.categoryCode());
                DraftAssessment a = evaluator.assess(category, p.fields(), context, p.requirements());
                PurchaseRequest r = requests.submit(new NewRequest(CHANNEL, c.getId(), context, category, p.fields(),
                        confirmedSources(p.fields()), a.lines(), a.estimatedTotal(), properties.currency(),
                        a.warnings(), List.of(), a.checks(), c.originalWords(), p.rationale(), p.title(),
                        p.requirements(), p.considered(), List.of()));
                requestId = r.getId();
                PurchaseRequest current = requests.get(r.getId());
                message = "Submitted as " + current.getNumber() + ". " + nextStep(current);
                status = Conversation.Status.SUBMITTED;
            }
            case SPECIAL -> {
                CategoryDefinition category = p.categoryCode() == null ? nonCatalogCategory()
                        : catalog.category(p.categoryCode());
                List<String> reasons = new ArrayList<>(p.specialReasons());
                p.checks().stream().filter(k -> k.blocksStandard()).forEach(k -> reasons.add(k.explain()));
                PurchaseRequest r = requests.submit(new NewRequest(CHANNEL, c.getId(), context, category, p.fields(),
                        confirmedSources(p.fields()), p.lines(),
                        p.estimatedTotal() == null ? java.math.BigDecimal.ZERO : p.estimatedTotal(),
                        properties.currency(), List.of(), reasons, p.checks(), c.originalWords(), p.summary(),
                        p.title(), p.requirements(), p.considered(), p.candidates(),
                        p.items() == null ? List.of() : p.items()));
                requestId = r.getId();
                PurchaseRequest current = requests.get(r.getId());
                message = "Submitted as special request " + current.getNumber() + ". " + nextStep(current)
                        + " Then a buyer sources and prices it (procurement's service level: a reply within "
                        + properties.buyerResponseDays() + " working days), and the price goes through approval "
                        + "and a budget check before anything is ordered.";
                status = Conversation.Status.SUBMITTED;
            }
            default -> throw new IllegalStateException("Unknown proposal type");
        }
        String number = requestId == null ? null : requests.get(requestId).getNumber();
        c.addSubmitted(new Conversation.Submitted(requestId, number, p.title(), p.type().name(), now()));
        c.clearProposal();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmed", true);
        result.put("result", message);
        result.put("submitted_in_this_conversation", c.getSubmitted().stream()
                .map(s -> (s.number() == null ? s.kind() : s.number()) + ": " + s.title()).toList());
        result.put("next", "Continue with any part of the employee's need that is not submitted yet (check your plan); "
                + "do not propose again what is listed above. If everything is covered, close with one short "
                + "sentence.");
        String callId = c.resolvePending();
        if (callIsProposal) {
            c.addAgentMessage(AgentMessage.tool(callId, json(result)));
        } else {
            if (callId != null) { // a question asked after the card: it is left unanswered
                c.addAgentMessage(AgentMessage.tool(callId, json(Map.of("answered", false,
                        "note", "The employee confirmed the open proposal card instead."))));
            }
            c.addAgentMessage(AgentMessage.user("[System] The employee pressed Confirm on the proposal card. "
                    + json(result)));
        }
        c.say("assistant", message, now());
        return runLoop(c);
    }

    /** The employee did not accept the proposal; their feedback goes back to the agent. */
    public ConversationView rejectProposal(String conversationId, String feedback) {
        Conversation c = loadOpen(conversationId);
        if (c.getProposal() == null) {
            throw new IllegalStateException("There is no proposal to reject.");
        }
        String text = feedback == null || feedback.isBlank() ? "Not like this." : feedback.trim();
        c.say("user", "Not confirmed: " + text, now());
        boolean callIsProposal = c.proposalCallPending();
        String callId = c.resolvePending();
        c.clearProposal();
        Map<String, Object> result = Map.of("confirmed", false, "submitted", false, "card_removed", true,
                "employee_feedback", text);
        if (callIsProposal) {
            c.addAgentMessage(AgentMessage.tool(callId, json(result)));
        } else {
            if (callId != null) {
                c.addAgentMessage(AgentMessage.tool(callId, json(Map.of("answered", false,
                        "note", "The employee rejected the open proposal card instead."))));
            }
            c.addAgentMessage(AgentMessage.user("[System] The employee rejected the proposal card. " + json(result)));
        }
        return runLoop(c);
    }

    public ConversationView cancel(String conversationId) {
        Conversation c = load(conversationId);
        if (c.isOpen()) {
            c.finish(Conversation.Status.CANCELLED, null);
            c.say("assistant", "Cancelled. Nothing was submitted.", now());
            conversations.save(c);
        }
        return view(c);
    }

    public ConversationView get(String conversationId) {
        return view(load(conversationId));
    }

    // ------------------------------------------------------------------ the loop

    private ConversationView runLoop(Conversation c) {
        EmployeeContext requester = employees.contextFor(c.getEmployeeId());
        String costCenter = employees.department(requester.employee().department())
                .map(d -> d.costCenter()).orElse(requester.employee().costCenter());
        ToolContext context = new ToolContext(c, requester, LocalDate.now(clock));
        int maxSteps = properties.agentSteps();
        Map<String, Integer> failures = new HashMap<>(); // identical failing calls in this turn
        for (int step = 1; step <= maxSteps; step++) {
            List<AgentMessage> messages = new ArrayList<>();
            messages.add(AgentMessage.system(AgentPrompt.system(requester, costCenter, LocalDate.now(clock),
                    properties.buyerResponseDays()) + state(c)));
            messages.addAll(c.getAgentMessages());

            ChatResult r = model.chat("agent step " + step, c.getId(), messages, tools.specs());
            c.addAgentMessage(new AgentMessage("assistant", r.content(), r.hasToolCalls() ? r.toolCalls() : null,
                    null, r.reasoningContent()));
            if (r.content() != null && !r.content().isBlank()) {
                c.say("assistant", r.content().trim(), now());
            }
            if (!r.hasToolCalls()) {
                // the model answered in text: the turn is over (an unconfirmed card stays confirmable)
                c.setStatus(c.getProposal() != null ? Conversation.Status.AWAITING_CONFIRMATION
                        : Conversation.Status.ACTIVE);
                return view(conversations.save(c));
            }

            boolean paused = false;
            for (ToolCall call : r.toolCalls()) {
                if (paused) {
                    c.addAgentMessage(AgentMessage.tool(call.id(), json(Map.of("error",
                            "Not executed: the employee must respond to the previous question or proposal first."))));
                    continue;
                }
                ToolRegistry.Executed ex = tools.execute(call, context);
                c.say("tool", ex.trace(), now());
                if (ex.ok() && ex.result().pauses()) {
                    paused = true;
                    if (ex.result().questions() != null) {
                        c.waitForAnswers(call.id(), ex.result().questions());
                    } else {
                        c.waitForConfirmation(call.id(), ex.result().proposal()); // replaces an older card
                    }
                } else if (!ex.ok() && failures.merge(call.name() + call.arguments(), 1, Integer::sum) >= 3) {
                    // The model keeps sending the same rejected call: stop instead of burning steps.
                    c.addAgentMessage(AgentMessage.tool(call.id(), ex.output()));
                    c.say("assistant", "I'm stuck preparing the next step. Please answer in your own words (for "
                            + "example how many, by when, for whom), and I'll continue from there.", now());
                    c.setStatus(c.getProposal() != null ? Conversation.Status.AWAITING_CONFIRMATION
                            : Conversation.Status.ACTIVE);
                    return view(conversations.save(c));
                } else if (!ex.ok() && failures.get(call.name() + call.arguments()) == 2) {
                    c.addAgentMessage(AgentMessage.tool(call.id(), json(Map.of(
                            "error", "You sent exactly the same call again and it failed the same way. Read the "
                                    + "problems and change the arguments; do not repeat the call unchanged.",
                            "previous_result", ex.output()))));
                } else {
                    c.addAgentMessage(AgentMessage.tool(call.id(), ex.output()));
                }
            }
            conversations.save(c);
            if (paused) {
                return view(c); // waiting for the employee; the paused call is answered by their response
            }
        }
        c.say("assistant", "I could not finish this in " + maxSteps + " steps. Please rephrase or add detail, "
                + "or I can prepare a request for a buyer.", now());
        c.setStatus(c.getProposal() != null ? Conversation.Status.AWAITING_CONFIRMATION : Conversation.Status.ACTIVE);
        return view(conversations.save(c));
    }

    /** What happens next, for the employee: who approves, and the budget verdict (never an amount). */
    private static String nextStep(PurchaseRequest r) {
        StringBuilder sb = new StringBuilder();
        switch (r.getStatus()) {
            case PENDING_APPROVAL -> {
                List<String> who = r.getApprovals().stream().filter(s -> s.isPending())
                        .map(s -> s.approverName() + " (" + s.reason() + ")").toList();
                sb.append("Waiting for approval by ").append(String.join(", then ", who)).append(".");
            }
            case POOLED -> sb.append("Approved and queued for the next consolidated order.");
            case ORDERED -> sb.append("Approved and ordered.");
            case APPROVED -> sb.append("Approved.");
            case BUYER_REVIEW -> sb.append("With a buyer.");
            case STRATEGIC_REVIEW -> sb.append("This is a strategic purchase (")
                    .append(String.join("; ", r.getStrategic() == null ? r.getRouteReasons() : r.getStrategic().reasons()))
                    .append("). Per policy 7 the category manager decides how it is sourced; the assistant does not "
                            + "recommend suppliers for it.");
            default -> sb.append("Status: ").append(r.getStatus()).append(".");
        }
        if (r.getSplitNote() != null) {
            sb.append(" ").append(r.getSplitNote()).append(".");
        }
        if ("OVER_BUDGET".equals(r.getBudgetCheck())) {
            sb.append(" It is over your department's budget for this quarter, so the Finance Controller decides.");
        } else if ("WITHIN_BUDGET".equals(r.getBudgetCheck())) {
            sb.append(" Within your department's budget.");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    /** What the system knows about this conversation, recomputed every step: the model's only source for it. */
    static String state(Conversation c) {
        StringBuilder sb = new StringBuilder("\n\nCONVERSATION STATE (from the system; authoritative)\n");
        if (c.getSubmitted().isEmpty()) {
            sb.append("- Submitted in this conversation: nothing yet.\n");
        } else {
            sb.append("- Submitted in this conversation (confirmed by the employee):\n");
            c.getSubmitted().forEach(s -> sb.append("  - ").append(s.number() == null ? s.kind() : s.number())
                    .append(": ").append(s.title()).append("\n"));
        }
        if (c.getProposal() != null) {
            sb.append("- Open proposal card, NOT submitted (waiting for Confirm): ").append(c.getProposal().title())
                    .append("\n");
        }
        sb.append("- Only what is listed as submitted exists. Never tell the employee something was submitted, "
                + "approved or ordered unless it is listed here. One proposal card is on screen at a time: when "
                + "the need has several parts, propose them one after another; after each confirmation, continue "
                + "with the next part.");
        return sb.toString();
    }

    private ConversationView view(Conversation c) {
        PurchaseRequest request = c.getRequestId() == null ? null : requests.get(c.getRequestId());
        List<ConversationView.SubmittedView> submitted = c.getSubmitted().stream().map(s -> {
            PurchaseRequest pr = s.requestId() == null ? null : requests.get(s.requestId());
            return new ConversationView.SubmittedView(s.requestId(), s.number(), s.title(), s.kind(),
                    pr == null ? null : pr.getStatus().name(), pr == null ? null : nextStep(pr));
        }).toList();
        return new ConversationView(c.getId(), c.getEmployeeId(), c.getStatus().name(), c.getTurns(), c.getPlan(),
                c.getPlanExplanation(), c.getPendingQuestions(), c.getProposal(), c.getRequestId(),
                request == null ? null : request.getNumber(), request == null ? null : request.getStatus().name(),
                submitted);
    }

    private static Map<String, String> confirmedSources(Map<String, Object> fields) {
        Map<String, String> sources = new LinkedHashMap<>();
        fields.keySet().forEach(k -> sources.put(k, "CONFIRMED"));
        return sources;
    }

    private static CategoryDefinition nonCatalogCategory() {
        return new CategoryDefinition("OTHER", "Non-catalog request", "Sourced individually by a buyer.",
                List.of(), ResolutionStrategy.DIRECT_SKU, null, false, List.of());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Conversation loadOpen(String id) {
        Conversation c = load(id);
        if (!c.isOpen()) {
            throw new IllegalStateException("This conversation is closed (" + c.getStatus() + "). Start a new one.");
        }
        return c;
    }

    private Conversation load(String id) {
        return conversations.findById(id).orElseThrow(() -> new NotFoundException("Unknown conversation " + id));
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
