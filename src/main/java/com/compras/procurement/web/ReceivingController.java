package com.compras.procurement.web;

import com.compras.procurement.context.Asset;
import com.compras.procurement.receiving.AssetClass;
import com.compras.procurement.receiving.AssetRegisterService;
import com.compras.procurement.receiving.Receipt;
import com.compras.procurement.receiving.ReceivingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Goods-in and the asset register. Demo identity: X-Staff-Id (production: single sign-on). */
@RestController
public class ReceivingController {

    public record ClassInput(String assetClass) {
    }

    public record NoteInput(String image, String text) {
    }

    public record AssignInput(String employeeId) {
    }

    private final ReceivingService receiving;
    private final AssetRegisterService register;

    public ReceivingController(ReceivingService receiving, AssetRegisterService register) {
        this.receiving = receiving;
        this.register = register;
    }

    @GetMapping("/api/receiving")
    public List<Receipt> mine(@RequestHeader("X-Staff-Id") String staffId) {
        return receiving.forReceiver(staffId);
    }

    @GetMapping("/api/receiving/classes")
    public List<Map<String, Object>> classes() {
        return Arrays.stream(AssetClass.values()).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("description", c.description());
            m.put("itDevice", c.itDevice());
            return m;
        }).toList();
    }

    @PostMapping("/api/receiving/{id}/class")
    public Receipt reclassify(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId,
                              @RequestBody ClassInput body) {
        return receiving.reclassify(id, staffId, body.assetClass());
    }

    /** The model reads the delivery note; nothing is checked in. */
    @PostMapping("/api/receiving/{id}/read")
    public ReceivingService.Reading read(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId,
                                         @RequestBody NoteInput body) {
        return receiving.read(id, staffId, body.image(), body.text());
    }

    @PostMapping("/api/receiving/{id}/check-in")
    public Receipt checkIn(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId,
                           @RequestBody ReceivingService.CheckIn body) {
        return receiving.checkIn(id, staffId, body);
    }

    @GetMapping("/api/assets")
    public List<Asset> assets() {
        return register.all();
    }

    @PostMapping("/api/receiving/{id}/returns")
    public Receipt returnGoods(@PathVariable String id, @RequestHeader("X-Staff-Id") String staffId,
                               @RequestBody ReceivingService.ReturnInput input) {
        return receiving.returnGoods(id, staffId, input);
    }

    @PostMapping("/api/assets/{tag}/assign")
    public Asset assign(@PathVariable String tag, @RequestHeader("X-Staff-Id") String staffId,
                        @RequestBody AssignInput body) {
        return register.assign(tag, staffId, body.employeeId());
    }
}
