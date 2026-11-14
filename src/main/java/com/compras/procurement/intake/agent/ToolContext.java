package com.compras.procurement.intake.agent;

import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.intake.Conversation;

import java.time.LocalDate;

/** What a tool may look at while it runs. */
public record ToolContext(Conversation conversation, EmployeeContext requester, LocalDate today) {
}
