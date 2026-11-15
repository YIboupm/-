package com.compras.procurement.approval;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * Someone with a role in the process. {@code departments} lists the departments they act for ("*" = all).
 */
@Document("staff")
public record StaffMember(@Id String id, String name, String title, StaffRole role, List<String> departments) {

    public boolean actsFor(String department) {
        return departments != null && (departments.contains("*")
                || departments.stream().anyMatch(d -> d.equalsIgnoreCase(department)));
    }
}
