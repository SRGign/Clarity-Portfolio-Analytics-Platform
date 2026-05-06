package com.pnltracker.analytics;

import com.pnltracker.api.PortfolioRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/portfolio")
public class AnalyticsController {

    private final PortfolioMetricsCalculator metricsCalculator;
    private final BenchmarkPriceService benchmarkPriceService;

    public AnalyticsController(
            PortfolioMetricsCalculator metricsCalculator,
            BenchmarkPriceService benchmarkPriceService) {
        this.metricsCalculator = metricsCalculator;
        this.benchmarkPriceService = benchmarkPriceService;
    }

    @PostMapping("/metrics")
    public PortfolioMetrics metrics(@Valid @RequestBody PortfolioRequest request) {
        return metricsCalculator.calculate(request.addresses(), request.chains());
    }

    @GetMapping("/benchmarks")
    public BenchmarkData benchmarks(@RequestParam("start") long startTimestamp) {
        try {
            return benchmarkPriceService.getBenchmarks(startTimestamp);
        } catch (Exception exception) {
            return new BenchmarkData(List.of(), List.of());
        }
    }
}
