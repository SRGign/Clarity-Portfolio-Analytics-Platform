package com.pnltracker.zerion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets")
public class ZerionDeFiController {

    private final ZerionDeFiService service;

    public ZerionDeFiController(ZerionDeFiService service) {
        this.service = service;
    }

    @GetMapping("/{address}/defi-positions")
    public ZerionDeFiResponse getDeFiPositions(@PathVariable String address) {
        ZerionApiClient.ZerionFetchResult result = service.getPositions(address);

        double totalUsd = result.positions().stream()
                .filter(p -> p.value() != null)
                .mapToDouble(ZerionPosition::value)
                .sum();

        return new ZerionDeFiResponse(result.positions(), totalUsd, result.stale());
    }
}
