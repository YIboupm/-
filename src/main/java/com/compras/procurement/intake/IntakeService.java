package com.compras.procurement.intake;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.FieldSpec;
import com.compras.procurement.catalog.ResolutionStrategy;
import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.context.EmployeeContextService;
import com.compras.procurement.llm.ModelGateway;
import com.compras.procurement.matching.Requirement;
import com.compras.procurement.matching.RequirementMatcher;
import com.compras.procurement.request.NewRequest;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.resolution.FieldValues;
import com.compras.procurement.rules.RuleViolation;
import com.compras.procurement.shared.Events;
import com.compras.procurement.shared.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrates the intake dialogue:
 * <ol>
 *   <li>classify the message and pick the category template (model)</li>
 *   <li>prefill what the system already knows (context, no model)</li>
 *   <li>extract field values and the employee's conditions from the dialogue (model, bounded by the template)</li>
 *   <li>normalise and validate every value (rules, no model)</li>
 *   <li>check the conditions against catalog attributes, switching to a better tier if one fits (code, no model)</li>
 *   <li>ask for what is missing, or show the structured request for confirmation</li>
 *   <li>on confirmation, store the standardised request: standard, or special for a buyer</li>
 * </ol>
 */
@Service
public class IntakeService {

    private static final String CHANNEL = "web-chat";
    private static final Pattern URGENT_WORDS = Pattern.compile(
            "urgent|asap|as soon as possible|immediately|right away|emergency|blocking|blocked|urgente|urgencia|急|马上|立刻",
            Pattern.CASE_INSENSITIVE);

    private final ConversationRepository conversations;
    private final CatalogService catalog;
    private final EmployeeContextService employees;
    private final ModelGateway model;
    private final FieldNormalizer normalizer;
    private final DraftEvaluator evaluator;
    private final RequirementMatcher matcher;
    private final RequestService requests;
    private final IntakeProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public IntakeService(ConversationRepository conversations, CatalogService catalog,
                         EmployeeContextService employees, ModelGateway model, FieldNormalizer normalizer,
                         DraftEvaluator evaluator, RequirementMatcher matcher, RequestService requests,
                         IntakeProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.conversations = conversations;
        this.catalog = catalog;
        this.employees = employees;
        this.model = model;
        this.normalizer = normalizer;
        this.evaluator = evaluator;
        this.matcher = matcher;
        this.requests = requests;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ commands

    public ConversationView start(String employeeId) {
        EmployeeContext context = employees.contextFor(employeeId);
        Conversation c = new Conversation(employeeId, now());
        String names = catalog.intakeCategories().stream().map(CategoryDefinition::name)
                .map(String::toLowerCase).collect(Collectors.joining(", "));
        c.say("assistant", "Hi " + firstName(context) + ". Tell me what you need in your own words. "
                + "I can handle " + names + ". Anything else goes to a buyer.", now());
        c.setQuickReplies(List.of(
                "I need a laptop for a new designer starting next month",
                "The printer on the 3rd floor is out of toner",
                "Two developers join on December 1st"));
        return view(conversations.save(c));
    }

    public ConversationView handleMessage(String conversationId, String text) {
        Conversation c = loadOpen(conversationId);
        c.say("user", text, now());
        return process(c);
    }

    /**
     * The employee answered the multiple-choice questions. Picked options are canonical values and are
     * stored directly as the employee's own answer, without calling the model. Only free-text "Other"
     * answers that cannot be parsed go to the model.
     */
    public ConversationView answer(String conversationId, List<Answer> answers) {
        Conversation c = loadOpen(conversationId);
        if (c.getCategoryCode() == null) {
            throw new IllegalStateException("There is no open question to answer.");
        }
        EmployeeContext context = employees.contextFor(c.getEmployeeId());
        CategoryDefinition category = catalog.category(c.getCategoryCode());
        List<String> summary = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        boolean needsModel = false;
        String decision = null;
        for (Answer a : answers == null ? List.<Answer>of() : answers) {
            if (Question.DECISION.equals(a.field())) {
                decision = a.value() == null ? null : a.value().toString();
                summary.add(orDefault(a.label(), decision));
                continue;
            }
            Optional<FieldSpec> spec = category.field(a.field());
            if (spec.isEmpty()) {
                continue;
            }
            boolean other = a.value() == null;
            Object raw = other ? a.text() : a.value();
            if (raw == null || raw.toString().isBlank()) {
                continue;
            }
            summary.add(spec.get().label() + ": " + (other ? a.text().trim() : orDefault(a.label(), raw.toString())));
            FieldNormalizer.Result r = normalizer.normalize(spec.get(), raw, category, context, today());
            if (r.isOk()) {
                c.put(spec.get().name(), r.value(), Conversation.Source.STATED);
            } else if (other) {
                needsModel = true; // free text the rules cannot parse: let the model read it in context
            } else if (r.problem() != null) {
                problems.add(r.problem());
            }
        }
        if (summary.isEmpty()) {
            throw new IllegalStateException("Choose an option or type an answer first.");
        }
        c.say("user", String.join("\n", summary), now());
        c.setStatus(Conversation.Status.COLLECTING);

        if ("special".equals(decision)) {
            return submit(c, category, context, true, null);
        }
        if ("standard".equals(decision)) {
            c.setRequirements(c.getRequirements().stream()
                    .map(r -> new Requirement(r.description(), r.attribute(), r.operator(), r.value(), "preference"))
                    .toList()); // the employee accepted the standard item: their conditions become preferences
            if (assess(c, category, context).readyToSubmit()) {
                return submit(c, category, context, false, null);
            }
        }
        if (needsModel) {
            return process(c);
        }
        c.setFieldProblems(problems);
        return decide(c, category, context, null, null, null, problems);
    }

    /** Model turn: classify if needed, extract, normalise, check requirements, then decide. */
    private ConversationView process(Conversation c) {
        EmployeeContext context = employees.contextFor(c.getEmployeeId());
        c.setStatus(Conversation.Status.COLLECTING);
        c.setQuickReplies(List.of());

        // 1. Classification: only until a category is chosen.
        if (c.getCategoryCode() == null) {
            ModelOutputs.Classification cls = model.call("classify", c.getId(),
                    IntakePrompts.withHistory(IntakePrompts.classificationSystem(catalog.intakeCategories()), c.getTurns()),
                    ModelOutputs.Classification.class);
            Optional<CategoryDefinition> category = catalog.findCategory(cls.categoryCode())
                    .filter(CategoryDefinition::intakeEnabled);
            if (cls.intent() == ModelOutputs.Intent.PURCHASE_REQUEST && category.isEmpty()) {
                return handOff(c, "Non-catalog purchase: needs a buyer", orDefault(cls.reply(),
                        "This is not one of our standard items, so a buyer from procurement will contact you."));
            }
            if (category.isEmpty()) {
                c.say("assistant", orDefault(cls.reply(),
                        "I can help you buy standard equipment. What do you need?"), now());
                return view(conversations.save(c));
            }
            c.startCategory(category.get().code());
            prefillFromContext(c, category.get(), context);
        }

        CategoryDefinition category = catalog.category(c.getCategoryCode());

        // 2. Extraction, bounded by the template, the catalog and the requester's context.
        DraftAssessment before = evaluator.assess(category, c.values(), context);
        String system = IntakePrompts.extractionSystem(category, catalog.itemsOf(category.code()), catalog.kits(),
                employees.departments(), context, c.values(), before.missingFields(), today());
        ModelOutputs.Extraction extraction = model.call("extract", c.getId(),
                IntakePrompts.withHistory(system, c.getTurns()), ModelOutputs.Extraction.class);

        // 3. Normalise and keep only valid values the employee actually gave.
        List<String> problems = applyExtraction(c, category, context, extraction);
        c.setFieldProblems(problems);
        if (extraction.requirements() != null) {
            c.setRequirements(extraction.requirements().stream()
                    .filter(r -> r != null && notBlank(r.description())).toList());
        }
        if (notBlank(extraction.rationale())) {
            c.setRationale(extraction.rationale().trim());
        }
        String reply = notBlank(extraction.reply()) ? extraction.reply().trim() : null;

        // 4. Code, not the model, checks the conditions against the catalog and picks a better tier if one fits.
        String switchNote = switchToBestTier(c, category, context);

        return decide(c, category, context, extraction, reply, switchNote, problems);
    }

    /**
     * The deterministic next step: ask multiple-choice questions for what is missing, or show the request
     * for confirmation. Nothing is ever submitted here; submission always follows an explicit choice.
     */
    private ConversationView decide(Conversation c, CategoryDefinition category, EmployeeContext context,
                                    ModelOutputs.Extraction extraction, String reply, String switchNote,
                                    List<String> problems) {
        DraftAssessment after = assess(c, category, context);
        boolean escalate = extraction != null && Boolean.TRUE.equals(extraction.escalateToBuyer());
        List<Question> questions = new ArrayList<>();
        String message;
        if (after.readyToSubmit()) {
            c.setStatus(Conversation.Status.AWAITING_CONFIRMATION);
            message = join(reply, join(switchNote, confirmationText(after, c)));
            if (escalate || !after.unmetMustHaves().isEmpty()) {
                questions.add(decisionQuestion(after, escalate, true));
            }
        } else if (reply == null && c.nextClarificationRound() > properties.maxClarificationRounds()) {
            // Turns where the employee asked us something do not count as unanswered questions.
            return handOff(c, "Still missing after " + properties.maxClarificationRounds() + " rounds: "
                    + labels(category, after.missingFields()),
                    "I'll pass this to a buyer with what we have so far; they will contact you for the rest.");
        } else {
            questions.addAll(buildQuestions(c, category, context, after, extraction));
            if (escalate) {
                questions.add(decisionQuestion(after, true, false));
            }
            message = join(reply, join(switchNote, clarificationText(after, questions, problems)));
        }
        c.setPendingQuestions(questions);
        c.say("assistant", message, now());
        return view(conversations.save(c));
    }

    /** One question per missing required field, up to 3: the model's wording if valid, else built from the template. */
    private List<Question> buildQuestions(Conversation c, CategoryDefinition category, EmployeeContext context,
                                          DraftAssessment assessment, ModelOutputs.Extraction extraction) {
        Map<String, Question> proposed = new java.util.HashMap<>();
        if (extraction != null && extraction.questions() != null) {
            for (ModelOutputs.ModelQuestion q : extraction.questions()) {
                if (q != null && q.field() != null) {
                    proposed.put(q.field(), new Question(q.field(), q.question(), q.options(), true));
                }
            }
        } else {
            c.getPendingQuestions().forEach(q -> proposed.put(q.field(), q));
        }
        List<Question> result = new ArrayList<>();
        for (String field : assessment.missingFields()) {
            if (result.size() == 3) {
                break;
            }
            FieldSpec spec = category.field(field).orElseThrow();
            Question q = proposed.get(field);
            List<Question.Option> options = validOptions(spec, q == null ? null : q.options(), category, context);
            if (options.isEmpty()) {
                options = fallbackOptions(spec, category, context);
            }
            String text = q != null && notBlank(q.text()) ? q.text() : spec.label() + "?";
            result.add(new Question(field, text, options, true));
        }
        return result;
    }

    /** Keeps only options whose value passes the field's normaliser, so every choice is valid by construction. */
    private List<Question.Option> validOptions(FieldSpec spec, List<Question.Option> options,
                                               CategoryDefinition category, EmployeeContext context) {
        List<Question.Option> valid = new ArrayList<>();
        if (options == null) {
            return valid;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Question.Option o : options) {
            if (o == null || o.value() == null) {
                continue;
            }
            FieldNormalizer.Result r = normalizer.normalize(spec, o.value(), category, context, today());
            if (r.isOk() && seen.add(r.value().toString())) {
                valid.add(new Question.Option(orDefault(o.label(), r.value().toString()), r.value()));
            }
        }
        return valid.size() > 4 ? valid.subList(0, 4) : valid;
    }

    /** Options derived from company data when the model offered none. */
    private List<Question.Option> fallbackOptions(FieldSpec spec, CategoryDefinition category, EmployeeContext context) {
        List<Question.Option> o = new ArrayList<>();
        switch (spec.type()) {
            case SKU -> catalog.itemsOf(category.code()).forEach(i -> o.add(new Question.Option(i.name(), i.sku())));
            case ASSET -> context.assetsOfType(category.assetType())
                    .forEach(a -> o.add(new Question.Option(a.location(), a.assetTag())));
            case KIT -> catalog.kits().forEach(k -> o.add(new Question.Option(k.name(), k.code())));
            case DEPARTMENT -> employees.departments().forEach(d -> o.add(new Question.Option(d.name(), d.name())));
            case ENUM -> {
                if (spec.allowedValues() != null) {
                    spec.allowedValues().forEach(v -> o.add(new Question.Option(v, v)));
                }
            }
            case BOOLEAN -> {
                o.add(new Question.Option("Yes", true));
                o.add(new Question.Option("No", false));
            }
            case DATE -> {
                for (int weeks : new int[]{1, 2, 4}) {
                    LocalDate d = today().plusWeeks(weeks);
                    o.add(new Question.Option("In " + weeks + (weeks == 1 ? " week" : " weeks") + " (" + d + ")",
                            d.toString()));
                }
            }
            case INTEGER -> {
                int min = spec.min() == null ? 1 : spec.min();
                for (int n : new int[]{1, 2, 3, 5}) {
                    if (n >= min && (spec.max() == null || n <= spec.max())) {
                        o.add(new Question.Option(String.valueOf(n), n));
                    }
                }
            }
            case TEXT -> { /* free text only: the "Other" box */ }
        }
        return o;
    }

    private Question decisionQuestion(DraftAssessment a, boolean specialFirst, boolean ready) {
        String itemName = a.lines().isEmpty() ? "a standard item" : "the standard " + a.lines().get(0).name();
        Question.Option standard = new Question.Option(
                ready ? "Go ahead with " + itemName : "Continue with a standard item", "standard");
        Question.Option special = new Question.Option("Send it to a buyer as a special request", "special");
        String text = ready && !a.unmetMustHaves().isEmpty()
                ? "The standard catalog does not meet all your must-haves. How do you want to proceed?"
                : "Do you want a buyer to handle this as a special request?";
        return new Question(Question.DECISION, text,
                specialFirst ? List.of(special, standard) : List.of(standard, special), false);
    }

    /**
     * Submits the draft. {@code special = false}: the standard catalog option, fully automated.
     * {@code special = true}: a special request with the unmet conditions, routed to a buyer.
     */
    public ConversationView confirm(String conversationId, boolean special) {
        Conversation c = load(conversationId);
        if (!c.isOpen() || c.getCategoryCode() == null) {
            throw new IllegalStateException("Nothing to submit yet.");
        }
        EmployeeContext context = employees.contextFor(c.getEmployeeId());
        CategoryDefinition category = catalog.category(c.getCategoryCode());
        if (special) {
            return submit(c, category, context, true, null);
        }
        if (c.getStatus() != Conversation.Status.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("Nothing to confirm yet.");
        }
        DraftAssessment assessment = assess(c, category, context);
        if (!assessment.readyToSubmit()) {
            return decide(c, category, context, null, "Something changed and the request is no longer complete.",
                    null, List.of());
        }
        return submit(c, category, context, false, null);
    }

    public ConversationView cancel(String conversationId) {
        Conversation c = load(conversationId);
        if (c.isOpen()) {
            c.setStatus(Conversation.Status.CANCELLED);
            c.say("assistant", "Cancelled. Nothing was submitted.", now());
            conversations.save(c);
        }
        return view(c);
    }

    public ConversationView get(String conversationId) {
        return view(load(conversationId));
    }

    // ------------------------------------------------------------------ steps

    private ConversationView submit(Conversation c, CategoryDefinition category, EmployeeContext context,
                                    boolean special, String reply) {
        DraftAssessment a = assess(c, category, context);
        List<String> specialRequirements = List.of();
        if (special) {
            specialRequirements = a.unmetMustHaves().isEmpty()
                    ? List.of("Employee asked for a non-standard request") : a.unmetMustHaves();
        }
        PurchaseRequest request = requests.submit(new NewRequest(CHANNEL, c.getId(), context, category,
                c.values(), c.sources(), a.lines(), a.estimatedTotal(), properties.currency(), a.warnings(),
                specialRequirements, a.checks(), c.originalWords(), c.getRationale()));
        c.submitted(request.getId());
        PurchaseRequest current = requests.get(request.getId());
        String message = special
                ? "Sent to procurement as special request " + current.getNumber() + ". It is not in the standard "
                  + "catalog, so a buyer will source and price it individually; it does not go through automatic "
                  + "approval. Procurement's service level is a reply within " + properties.buyerResponseDays()
                  + " working days."
                : "Submitted as " + current.getNumber() + ". Status: " + current.getStatus()
                  + ". You can follow it on the Pool & Orders page.";
        c.say("assistant", join(reply, message), now());
        c.setQuickReplies(List.of());
        c.setPendingQuestions(List.of());
        return view(conversations.save(c));
    }

    /**
     * For categories where the employee picks one SKU: if the selected tier fails a must-have that
     * another tier meets, switch to that tier. Deterministic, so the same request always maps the same way.
     * Returns a sentence for the employee when a switch happened.
     */
    private String switchToBestTier(Conversation c, CategoryDefinition category, EmployeeContext context) {
        if (category.resolution() != ResolutionStrategy.DIRECT_SKU || c.getRequirements().isEmpty()) {
            return null;
        }
        Optional<CatalogItem> current = FieldValues.string(c.values(), "item").flatMap(catalog::findItem);
        if (current.isEmpty()) {
            return null;
        }
        long currentFailures = matcher.failedMusts(current.get(), c.getRequirements());
        if (currentFailures == 0) {
            return null;
        }
        Optional<CatalogItem> better = matcher.bestMatch(catalog.itemsOf(category.code()), c.getRequirements());
        if (better.isEmpty() || matcher.failedMusts(better.get(), c.getRequirements()) >= currentFailures) {
            return null; // no tier is strictly better: keep the model's choice
        }
        c.put("item", better.get().sku(), Conversation.Source.INFERRED);
        c.setRationale("Switched from " + current.get().name() + " to " + better.get().name()
                + ": by the catalog data it meets more of your must-have requirements.");
        return "I changed the option to " + better.get().name()
                + ", the standard item that meets more of your must-have requirements.";
    }

    /** Fields the system can answer itself are never asked: site and department from HR data, flags from constants. */
    private void prefillFromContext(Conversation c, CategoryDefinition category, EmployeeContext context) {
        for (FieldSpec f : category.fields()) {
            if (f.defaultFrom() == null) {
                continue;
            }
            if (f.defaultFrom().equals("employee.site")) {
                c.put(f.name(), context.employee().site(), Conversation.Source.CONTEXT);
            } else if (f.defaultFrom().equals("employee.department")) {
                c.put(f.name(), context.employee().department(), Conversation.Source.CONTEXT);
            } else if (f.defaultFrom().startsWith("const:")) {
                Object value = normalizer.normalize(f, f.defaultFrom().substring(6), category, context, today()).value();
                if (value != null) {
                    c.put(f.name(), value, Conversation.Source.CONTEXT);
                }
            }
        }
    }

    private List<String> applyExtraction(Conversation c, CategoryDefinition category, EmployeeContext context,
                                         ModelOutputs.Extraction extraction) {
        List<String> problems = new ArrayList<>();
        if (extraction.fields() == null) {
            return problems;
        }
        String employeeWords = c.originalWords();
        java.util.Set<String> asked = new java.util.HashSet<>();
        if (extraction.questions() != null) {
            extraction.questions().stream().filter(q -> q != null && q.field() != null).forEach(q -> asked.add(q.field()));
        }
        for (Map.Entry<String, ModelOutputs.ProposedValue> entry : extraction.fields().entrySet()) {
            Optional<FieldSpec> spec = category.field(entry.getKey());
            ModelOutputs.ProposedValue proposed = entry.getValue();
            if (spec.isEmpty() || proposed == null) {
                continue; // the model proposed a field the template does not have: ignore it
            }
            Conversation.Source proposedSource = proposed.toSource();
            // The model asks about this field: it may not also answer it on the employee's behalf.
            if (asked.contains(spec.get().name())) {
                continue;
            }
            // Dates are commitments: only a date the employee gave counts, never one the model inferred.
            if (spec.get().type() == com.compras.procurement.catalog.FieldType.DATE
                    && proposedSource == Conversation.Source.INFERRED) {
                continue;
            }
            FieldNormalizer.Result result = normalizer.normalize(spec.get(), proposed.value(), category, context, today());
            if (result.isOk()) {
                Conversation.Source source = proposedSource;
                // The model may not label "urgent" as said by the employee unless they actually said it.
                if (spec.get().name().equals("urgent") && Boolean.TRUE.equals(result.value())
                        && source == Conversation.Source.STATED && !URGENT_WORDS.matcher(employeeWords).find()) {
                    source = Conversation.Source.INFERRED;
                }
                c.put(spec.get().name(), result.value(), source);
            } else if (result.problem() != null) {
                problems.add(result.problem());
            }
        }
        return problems;
    }

    private ConversationView handOff(Conversation c, String reason, String message) {
        c.handOff(reason);
        c.say("assistant", message, now());
        c.setQuickReplies(List.of());
        Conversation saved = conversations.save(c);
        events.publishEvent(new Events.IntakeHandedOff(saved.getId(), reason, now()));
        return view(saved);
    }

    private String clarificationText(DraftAssessment a, List<Question> questions, List<String> problems) {
        StringBuilder sb = new StringBuilder();
        for (String p : problems) {
            sb.append(p).append(". ");
        }
        a.violations().stream().filter(RuleViolation::blocking).forEach(v -> sb.append(v.message()).append(" "));
        if (!questions.isEmpty()) {
            sb.append(questions.size() == 1 ? "One more thing:" : "A few details, please:");
            questions.forEach(q -> sb.append("\n- ").append(q.text()));
        }
        return sb.toString().trim();
    }

    /** Shows exactly what will be ordered, specs and requirement checks included, so nothing is a black box. */
    private String confirmationText(DraftAssessment a, Conversation c) {
        StringBuilder sb = new StringBuilder("Here is your request, ready to submit:");
        a.lines().forEach(l -> {
            sb.append("\n- ").append(l.quantity()).append(" x ").append(l.name());
            if (l.specs() != null && !l.specs().isEmpty()) {
                sb.append(" (").append(l.specs().entrySet().stream()
                        .map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining(", "))).append(')');
            }
        });
        sb.append("\nEstimated total: ").append(a.estimatedTotal()).append(" ").append(properties.currency()).append('.');
        if (notBlank(c.getRationale())) {
            sb.append("\nWhy this option: ").append(c.getRationale());
        }
        a.warnings().forEach(w -> sb.append("\nNote: ").append(w.message()));
        List<String> unmet = a.unmetMustHaves();
        if (!unmet.isEmpty()) {
            sb.append("\nThe standard catalog does not meet: ").append(String.join("; ", unmet))
                    .append(". You can go ahead with this standard item, or send the request to a buyer, who will "
                            + "source and price it individually (procurement replies within ")
                    .append(properties.buyerResponseDays()).append(" working days).");
        } else {
            sb.append("\nPress Confirm to submit, ask me anything about it, or tell me what to change.");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ view

    private DraftAssessment assess(Conversation c, CategoryDefinition category, EmployeeContext context) {
        return evaluator.assess(category, c.values(), context, c.getRequirements());
    }

    private ConversationView view(Conversation c) {
        CategoryDefinition category = catalog.findCategory(c.getCategoryCode()).orElse(null);
        List<ConversationView.FieldView> fields = new ArrayList<>();
        DraftAssessment assessment = null;
        if (category != null) {
            for (FieldSpec f : category.fields()) {
                Conversation.FieldValue v = c.getFields().get(f.name());
                fields.add(new ConversationView.FieldView(f.name(), f.label(), f.type().name(), f.required(),
                        v == null ? null : v.value(), v == null ? null : v.source().name()));
            }
            assessment = assess(c, category, employees.contextFor(c.getEmployeeId()));
        }
        List<RuleViolation> issues = new ArrayList<>();
        c.getFieldProblems().forEach(p -> issues.add(RuleViolation.error(null, p)));
        if (assessment != null) {
            issues.addAll(assessment.violations());
        }
        String costCenter = FieldValues.string(c.values(), "chargeTo").flatMap(employees::department)
                .map(d -> d.costCenter()).orElse(null);
        PurchaseRequest request = c.getRequestId() == null ? null : requests.get(c.getRequestId());
        return new ConversationView(c.getId(), c.getEmployeeId(), c.getStatus().name(),
                category == null ? null : category.code(), category == null ? null : category.name(),
                c.getTurns(), fields,
                assessment == null ? List.of() : assessment.lines(),
                assessment == null ? null : assessment.estimatedTotal(),
                properties.currency(), issues,
                assessment == null ? List.of() : assessment.missingFields(),
                c.getQuickReplies(), c.getPendingQuestions(), c.getRationale(),
                assessment == null ? List.of() : assessment.checks(),
                assessment == null ? List.of() : assessment.unmetMustHaves(),
                costCenter,
                c.getHandoffReason(), c.getRequestId(),
                request == null ? null : request.getNumber(),
                request == null ? null : request.getStatus().name());
    }

    // ------------------------------------------------------------------ helpers

    /** One answer from the question cards: a picked option ({@code value}) or free text ({@code text}). */
    public record Answer(String field, Object value, String label, String text) {
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

    private static String labels(CategoryDefinition category, List<String> names) {
        return names.stream().map(n -> category.field(n).map(FieldSpec::label).orElse(n))
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
    }

    private static String firstName(EmployeeContext context) {
        return context.employee().name().split(" ")[0];
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank() && !"null".equalsIgnoreCase(s.trim());
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return second == null || second.isBlank() ? first : first + "\n\n" + second;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
