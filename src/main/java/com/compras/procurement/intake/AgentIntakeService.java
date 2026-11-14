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
            boolean wasProposal = c.getProposal() != null;
            String callId = c.resolvePending();
            c.addAgentMessage(AgentMessage.tool(callId, json(wasProposal
                    ? Map.of("confirmed", false, "employee_wrote_instead", text)
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
        String callId = c.getPendingToolCallId();
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
                message = "Submitted as " + current.getNumber() + " (status " + current.getStatus() + ").";
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
                        p.title(), p.requirements(), p.considered(), p.candidates()));
                requestId = r.getId();
                PurchaseRequest current = requests.get(r.getId());
                message = "Sent to procurement as special request " + current.getNumber() + ". A buyer will source "
                        + "and price it; procurement's service level is a reply within "
                        + properties.buyerResponseDays() + " working days.";
                status = Conversation.Status.SUBMITTED;
            }
            default -> throw new IllegalStateException("Unknown proposal type");
        }
        c.resolvePending();
        c.addAgentMessage(AgentMessage.tool(callId, json(Map.of("confirmed", true, "result", message))));
        c.say("assistant", message, now());
        c.finish(status, requestId);
        return view(conversations.save(c));
    }

    /** The employee did not accept the proposal; their feedback goes back to the agent. */
    public ConversationView rejectProposal(String conversationId, String feedback) {
        Conversation c = loadOpen(conversationId);
        if (c.getProposal() == null) {
            throw new IllegalStateException("There is no proposal to reject.");
        }
        String text = feedback == null || feedback.isBlank() ? "Not like this." : feedback.trim();
        c.say("user", "Not confirmed: " + text, now());
        String callId = c.resolvePending();
        c.addAgentMessage(AgentMessage.tool(callId, json(Map.of("confirmed", false, "employee_feedback", text))));
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
                    properties.buyerResponseDays())));
            messages.addAll(c.getAgentMessages());

            ChatResult r = model.chat("agent step " + step, c.getId(), messages, tools.specs());
            c.addAgentMessage(new AgentMessage("assistant", r.content(), r.hasToolCalls() ? r.toolCalls() : null,
                    null, r.reasoningContent()));
            if (r.content() != null && !r.content().isBlank()) {
                c.say("assistant", r.content().trim(), now());
            }
            if (!r.hasToolCalls()) {
                c.setStatus(Conversation.Status.ACTIVE);
                return view(conversations.save(c)); // the model answered in text: the turn is over
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
                        c.waitForConfirmation(call.id(), ex.result().proposal());
                    }
                } else if (!ex.ok() && failures.merge(call.name() + call.arguments(), 1, Integer::sum) >= 3) {
                    // The model keeps sending the same rejected call: stop instead of burning steps.
                    c.addAgentMessage(AgentMessage.tool(call.id(), ex.output()));
                    c.say("assistant", "I'm stuck preparing the next step. Please answer in your own words (for "
                            + "example how many, by when, for whom), and I'll continue from there.", now());
                    c.setStatus(Conversation.Status.ACTIVE);
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
        c.setStatus(Conversation.Status.ACTIVE);
        return view(conversations.save(c));
    }

    // ------------------------------------------------------------------ helpers

    private ConversationView view(Conversation c) {
        PurchaseRequest request = c.getRequestId() == null ? null : requests.get(c.getRequestId());
        return new ConversationView(c.getId(), c.getEmployeeId(), c.getStatus().name(), c.getTurns(), c.getPlan(),
                c.getPlanExplanation(), c.getPendingQuestions(), c.getProposal(), c.getRequestId(),
                request == null ? null : request.getNumber(), request == null ? null : request.getStatus().name());
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
