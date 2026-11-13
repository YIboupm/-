package com.compras.procurement.web;

import com.compras.procurement.intake.ConversationView;
import com.compras.procurement.intake.IntakeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Web-chat channel adapter. An IM bot (Feishu, DingTalk, Teams) would be another adapter calling
 * the same {@link IntakeService} methods.
 */
@RestController
@RequestMapping("/api/intake/conversations")
public class IntakeController {

    public record StartRequest(String employeeId) {
    }

    public record MessageRequest(String text) {
    }

    public record AnswersRequest(List<IntakeService.Answer> answers) {
    }

    private final IntakeService intake;

    public IntakeController(IntakeService intake) {
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
        return intake.handleMessage(id, body.text().trim());
    }

    /** Answers to the multiple-choice questions. Picked options are stored without calling the model. */
    @PostMapping("/{id}/answers")
    public ConversationView answers(@PathVariable String id, @RequestBody AnswersRequest body) {
        return intake.answer(id, body.answers());
    }

    /** mode=standard (default): the closest catalog option. mode=special: send to a buyer. */
    @PostMapping("/{id}/confirm")
    public ConversationView confirm(@PathVariable String id,
                                    @RequestParam(defaultValue = "standard") String mode) {
        return intake.confirm(id, "special".equalsIgnoreCase(mode));
    }

    @PostMapping("/{id}/cancel")
    public ConversationView cancel(@PathVariable String id) {
        return intake.cancel(id);
    }
}
