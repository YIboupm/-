package com.compras.procurement.context;

import com.compras.procurement.shared.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the requester's context from the (mock) HR directory and asset register.
 * Swapping the mocks for real systems only touches this class.
 */
@Service
public class EmployeeContextService {

    private final EmployeeRepository employees;
    private final AssetRepository assets;
    private final DepartmentRepository departments;

    public EmployeeContextService(EmployeeRepository employees, AssetRepository assets,
                                  DepartmentRepository departments) {
        this.employees = employees;
        this.assets = assets;
        this.departments = departments;
    }

    public List<Department> departments() {
        return departments.findAll();
    }

    /** Case-insensitive lookup, so "it" or "Marketing" from the model resolve to the master record. */
    public Optional<Department> department(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim();
        return departments.findAll().stream()
                .filter(d -> d.name().equalsIgnoreCase(key) || d.costCenter().equalsIgnoreCase(key)).findFirst();
    }

    public List<Employee> allEmployees() {
        return employees.findAll();
    }

    public Employee employee(String id) {
        return employees.findById(id).orElseThrow(() -> new NotFoundException("Unknown employee " + id));
    }

    public EmployeeContext contextFor(String employeeId) {
        Employee employee = employee(employeeId);
        List<Asset> visible = new ArrayList<>(assets.findByAssignedTo(employeeId));
        // received-but-not-handed-out assets are earmarked for their request: never offered for reuse
        assets.findBySiteAndAssignedToIsNull(employee.site()).stream()
                .filter(a -> !Asset.RESERVED.equals(a.status())).forEach(visible::add);
        return new EmployeeContext(employee, visible);
    }
}
