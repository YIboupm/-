package com.compras.procurement.receiving;

import com.compras.procurement.approval.StaffMember;
import com.compras.procurement.approval.StaffRepository;
import com.compras.procurement.approval.StaffRole;
import com.compras.procurement.context.Asset;
import com.compras.procurement.context.AssetRepository;
import com.compras.procurement.context.EmployeeContextService;
import com.compras.procurement.shared.Events;
import com.compras.procurement.shared.ForbiddenException;
import com.compras.procurement.shared.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** The asset register as people use it: look things up, hand a reserved asset to the person it is for. */
@Service
public class AssetRegisterService {

    private final AssetRepository assets;
    private final StaffRepository staff;
    private final EmployeeContextService employees;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AssetRegisterService(AssetRepository assets, StaffRepository staff, EmployeeContextService employees,
                                ApplicationEventPublisher events, Clock clock) {
        this.assets = assets;
        this.staff = staff;
        this.employees = employees;
        this.events = events;
        this.clock = clock;
    }

    /** Newest received first, then the register as it was seeded. */
    public List<Asset> all() {
        return assets.findAll().stream()
                .sorted(Comparator.comparing((Asset a) -> a.acquisition() == null ? Instant.EPOCH : a.acquisition().receivedAt(),
                        Comparator.reverseOrder()).thenComparing(Asset::assetTag))
                .toList();
    }

    /** IT equipment is handed out by the IT asset manager, everything else by facilities. */
    public Asset assign(String assetTag, String staffId, String employeeId) {
        Asset asset = assets.findById(assetTag).orElseThrow(() -> new NotFoundException("Unknown asset " + assetTag));
        StaffMember who = staff.findById(staffId == null ? "" : staffId)
                .orElseThrow(() -> new IllegalStateException("Unknown staff member"));
        boolean it = AssetClass.parse(asset.type()).map(AssetClass::itDevice).orElse(false);
        StaffRole keeper = it ? StaffRole.IT_ASSET_MANAGER : StaffRole.FACILITIES;
        if (who.role() != keeper) {
            throw new ForbiddenException(assetTag + " is handed out by the " + keeper.label() + ".");
        }
        if (!Asset.RESERVED.equals(asset.status()) && !"IDLE".equals(asset.status())) {
            throw new IllegalStateException(assetTag + " is " + asset.status() + "; only reserved or idle assets "
                    + "can be handed out.");
        }
        var employee = employees.employee(employeeId);
        Asset handed = asset.assignTo(employee.id(), asset.custodian());
        handed = new Asset(handed.assetTag(), handed.type(), handed.model(), handed.site(),
                handed.site() + ", with " + employee.name(), handed.assignedTo(), handed.status(), handed.custodian(),
                handed.attributes(), handed.serialNumber(), handed.acquisition());
        if (assets.assignAvailable(assetTag, employee.id(), handed.location(), handed.custodian()) != 1) {
            throw new IllegalStateException("This asset is no longer available for assignment.");
        }
        events.publishEvent(new Events.AssetRegistered(assetTag, handed.model(), handed.serialNumber(), employee.id(),
                handed.status(), handed.acquisition() == null ? null : handed.acquisition().requestNumber(),
                Instant.now(clock)));
        return handed;
    }
}
