package com.compras.procurement.intake;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.FieldSpec;
import com.compras.procurement.catalog.RoleKit;
import com.compras.procurement.context.Asset;
import com.compras.procurement.context.Department;
import com.compras.procurement.context.Employee;
import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.llm.ChatMessage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds the prompts. The model is never asked "what should I ask?" in the abstract: it always
 * receives the category template, the allowed values and the requester's context, so its
 * answers are bounded by company data.
 */
public final class IntakePrompts {

    private IntakePrompts() {
    }

    // ---------------------------------------------------------------- step 1: classification

    public static String classificationSystem(List<CategoryDefinition> categories) {
        String list = categories.stream()
                .map(c -> "- " + c.code() + ": " + c.name() + ". " + c.description()
                        + " Examples: " + String.join(" | ", c.examples()))
                .collect(Collectors.joining("\n"));
        return """
                You are the triage step of a company's procurement assistant.
                Decide what the employee's latest message is about.

                Standard categories the assistant can handle end to end:
                %s

                Return only a JSON object:
                {"intent": "PURCHASE_REQUEST" | "STATUS_QUERY" | "POLICY_QUESTION" | "OTHER",
                 "categoryCode": "<one code from the list, or null>",
                 "confidence": <number 0..1>,
                 "reply": "<short reply to the employee, or null>"}

                Rules:
                - Use only the category codes listed above.
                - If the employee wants to buy something that is not in these categories (e.g. consulting,
                  outsourcing, custom services, travel), set intent PURCHASE_REQUEST, categoryCode null, and in
                  "reply" say that a buyer from the procurement team will handle it personally.
                - If the message is not a purchase request, set "reply" to a short helpful answer that says what
                  the assistant can do.
                - When the category is clear, "reply" can be null.
                - Write "reply" in the same language as the employee.
                """.formatted(list);
    }

    // ---------------------------------------------------------------- step 2: extraction

    public static String extractionSystem(CategoryDefinition category, List<CatalogItem> items, List<RoleKit> kits,
                                          List<Department> departments, EmployeeContext context,
                                          Map<String, Object> currentDraft, List<String> missingFields,
                                          LocalDate today) {
        Employee e = context.employee();
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are the intake assistant of a company's procurement system. Turn the employee's request into
                the structured fields of the category template below, and ask for whatever required field is
                still missing. The employee is not a procurement expert.

                """);
        sb.append("Today: ").append(today).append(" (").append(today.getDayOfWeek()).append(").\n");
        sb.append("Employee: ").append(e.name()).append(", ").append(e.jobRole()).append(", ")
                .append(e.department()).append(" department, site: ").append(e.site()).append(".\n\n");

        sb.append("CATEGORY: ").append(category.code()).append(" - ").append(category.name()).append("\n");
        sb.append("FIELDS:\n");
        for (FieldSpec f : category.fields()) {
            sb.append("- ").append(f.name()).append(" (").append(f.type())
                    .append(f.required() ? ", required" : ", optional");
            if (f.min() != null || f.max() != null) {
                sb.append(", range ").append(f.min()).append("..").append(f.max());
            }
            if (f.allowedValues() != null && !f.allowedValues().isEmpty()) {
                sb.append(", one of ").append(f.allowedValues());
            }
            sb.append("): ").append(f.description()).append("\n");
        }

        if (hasType(category, "SKU")) {
            sb.append("\nCATALOG OPTIONS (valid values for SKU fields):\n");
            for (CatalogItem i : items) {
                sb.append("- ").append(i.sku()).append(" \"").append(i.name()).append("\": ")
                        .append(i.specs()).append(". Suitable for: ").append(String.join(", ", i.suitableFor()))
                        .append(". Price ").append(i.listPrice()).append(" ").append(i.currency()).append("\n");
            }
        }
        if (hasType(category, "ASSET")) {
            sb.append("\nASSETS THE EMPLOYEE CAN SEE (valid values for ASSET fields):\n");
            for (Asset a : context.assetsOfType(category.assetType())) {
                sb.append("- ").append(a.assetTag()).append(": ").append(a.model())
                        .append(", location: ").append(a.location()).append("\n");
            }
        }
        if (hasType(category, "DEPARTMENT")) {
            sb.append("\nDEPARTMENTS (valid values for DEPARTMENT fields): ")
                    .append(departments.stream().map(Department::name).toList()).append("\n");
        }
        Map<String, TreeSet<String>> attributeValues = new TreeMap<>();
        for (CatalogItem i : items) {
            if (i.attributes() != null) {
                i.attributes().forEach((k, v) -> attributeValues.computeIfAbsent(k, x -> new TreeSet<>())
                        .add(Objects.toString(v)));
            }
        }
        if (!attributeValues.isEmpty()) {
            sb.append("\nCOMPARABLE ATTRIBUTES (keys for \"requirements\", with the values that exist in the catalog):\n");
            attributeValues.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        }
        if (hasType(category, "KIT")) {
            sb.append("\nSTANDARD ROLE KITS (valid values for KIT fields):\n");
            for (RoleKit k : kits) {
                sb.append("- ").append(k.code()).append(" \"").append(k.name()).append("\": ")
                        .append(k.description()).append(" Matches roles: ").append(String.join(", ", k.matchesRoles()))
                        .append("\n");
            }
        }

        sb.append("\nCURRENT DRAFT (already captured; keep unless the employee changes it): ")
                .append(currentDraft).append("\n");
        sb.append("REQUIRED FIELDS STILL MISSING BEFORE THIS MESSAGE: ").append(missingFields).append("\n\n");

        sb.append("""
                RULES
                1. Fill a field only from what the employee said or from a clear inference. Map vague needs onto
                   the catalog: a job, workload or use case selects the matching option. Use the field "source":
                   "stated" when the employee said it, "inferred" when you derived it.
                2. Use only the SKU, asset and kit values listed above. Never invent one.
                3. Convert relative dates ("next Monday", "in two weeks", "early December") to yyyy-MM-dd using today.
                4. If the employee corrects a value, return the new value.
                5. Return only fields you are adding or changing; omit the others.
                6. For each required field still missing after this message (at most 3, most important first), add
                   one entry to "questions": {"field": "<fieldName>", "question": "<short question>",
                   "options": [{"label": "<plain words>", "value": <canonical value>}]} with 2 to 4 likely answers.
                   "value" must be valid for the field: a listed SKU / asset tag / kit code / department, a date as
                   yyyy-MM-dd, a number. The interface adds an "Other" choice itself. Do not ask for technical specs:
                   offer catalog options in plain words. Do not ask for anything the draft already has.
                7. NEVER fill a field you are asking about, and NEVER fill a date the employee did not mention.
                   If you have a likely guess, make it the first option of the question instead.
                   If nothing required is missing, "questions" is [].
                8. If the employee asks something (specs, differences between options, price, lead time, how the
                   process works), answer it in "reply" in 1-4 short sentences, using ONLY the catalog data above.
                   If the data does not contain the answer, say so. Never invent specs. Otherwise "reply" is null.
                   Do not tell the employee whether their requirements are met; the system shows that.
                9. "rationale": one short sentence addressed to the employee explaining why the selected option
                   fits their need (e.g. "Design roles need colour-accurate screens and a dedicated GPU").
                10. "requirements": list EVERY condition the employee put on the item (operating system, GPU,
                   memory, screen size, software it must run, brand ...). For each one:
                   - "description": the condition in a few plain words, e.g. "Must run macOS".
                   - "attribute": the matching key from COMPARABLE ATTRIBUTES, or null if none covers it.
                   - "operator": "eq" for a required value, "min" for at least, "max" for at most.
                   - "value": the value asked for, using the catalog's vocabulary where possible
                     (e.g. "dedicated" for a strong / dedicated graphics card, 32 for "32 GB or more").
                   - "strength": "must" if the employee says it is required, "preference" for "preferably",
                     "ideally", "would be nice".
                   Do NOT judge whether the catalog meets them: the system compares them with the catalog.
                   Return the full current list every time; [] if there are none.
                11. "escalateToBuyer": true if the employee insists on something the catalog cannot provide or asks
                   for a human buyer; otherwise false. This does not submit anything: the employee is asked to choose.
                12. A field's "source" is "stated" only when the employee said that value. "urgent" is "stated"
                   only if the employee used a word like urgent / ASAP / blocking; otherwise "inferred".
                13. Never promise or estimate delivery or processing times; the system adds those from its data.
                14. Write questions, option labels, "reply", "rationale" and requirement descriptions in the same
                   language as the employee.

                Return only a JSON object:
                {"fields": {"<fieldName>": {"value": <string | number | boolean>, "source": "stated" | "inferred"}},
                 "questions": [{"field": "<fieldName>", "question": "...", "options": [{"label": "...", "value": "..."}]}],
                 "reply": "<answer to the employee's question, or null>",
                 "rationale": "<why the selected option fits, or null>",
                 "requirements": [{"description": "...", "attribute": "<key or null>", "operator": "eq" | "min" | "max",
                                   "value": <string | number>, "strength": "must" | "preference"}],
                 "escalateToBuyer": false}
                """);
        return sb.toString();
    }

    /** The dialogue so far in chat format, after the given system prompt. */
    public static List<ChatMessage> withHistory(String system, List<Conversation.Turn> turns) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));
        for (Conversation.Turn t : turns) {
            messages.add("user".equals(t.role()) ? ChatMessage.user(t.text()) : ChatMessage.assistant(t.text()));
        }
        return messages;
    }

    private static boolean hasType(CategoryDefinition category, String type) {
        return category.fields().stream().anyMatch(f -> f.type().name().equals(type));
    }
}
