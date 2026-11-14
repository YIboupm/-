package com.compras.procurement.web;

import com.compras.procurement.fulfillment.CompanyResourceSnapshot;
import com.compras.procurement.fulfillment.MacResourceMonitor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only endpoint used by the demo to inspect the live company Mac resource. */
@RestController
@RequestMapping("/api/internal-resources")
public class InternalResourceController {

    private final MacResourceMonitor monitor;

    public InternalResourceController(MacResourceMonitor monitor) {
        this.monitor = monitor;
    }

    @GetMapping("/mac-server")
    public CompanyResourceSnapshot macServer() {
        return monitor.snapshot();
    }
}
