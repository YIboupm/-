package com.compras.procurement.web;

import com.compras.procurement.intake.AgentIntakeService;
import com.compras.procurement.intake.ConversationView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Web-chat channel adapter for the intake agent. An IM bot would be another adapter calling the same
 * {@link AgentIntakeService} methods.
 */
@RestController
@RequestMapping("/api/intake/conversations")
public class IntakeController {

    public record StartRequest(String employeeId) {
    }

    public record MessageRequest(String text) {
    }

    public record AnswersRequest(List<AgentIntakeService.Answer> answers) {
    }

    public record RejectRequest(String feedback) {
    }

    private final AgentIntakeService intake;

    public IntakeController(AgentIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping
    public ConversationView start(@RequestBody StartRequest body) {
        return intake.start(body.employeeId());
    }

    @GetMapping("/{id}")
    public ConversationView get(@PathVariable String id) {
        return intake.get(id);
    }

    @PostMapping("/{id}/messages")
    public ConversationView message(@PathVariable String id, @RequestBody MessageRequest body) {
        if (body.text() == null || body.text().isBlank()) {
            throw new IllegalStateException("Empty message.");
        }
        return intake.message(id, body.text().trim());
    }

    @PostMapping("/{id}/answers")
    public ConversationView answers(@PathVariable String id, @RequestBody AnswersRequest body) {
        return intake.answer(id, body.answers());
    }

    /** The employee approves the agent's proposal: the only way anything gets submitted. */
    @PostMapping("/{id}/proposal/confirm")
    public ConversationView confirm(@PathVariable String id) {
        return intake.confirmProposal(id);
    }

    @PostMapping("/{id}/proposal/reject")
    public ConversationView reject(@PathVariable String id, @RequestBody RejectRequest body) {
        return intake.rejectProposal(id, body.feedback());
    }

    @PostMapping("/{id}/cancel")
    public ConversationView cancel(@PathVariable String id) {
        return intake.cancel(id);
    }
}
