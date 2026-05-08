package com.pnltracker.market;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "yield.market.defillama")
public class DefiLlamaYieldProperties {

    private String baseUrl = "https://yields.llama.fi";
    private double minTvlUsd = 50_000_000.0d;
    private double maxApy = 15.0d;
    private int cacheTtlSeconds = 900;
    private int maxOptions = 8;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public double getMinTvlUsd() {
        return minTvlUsd;
    }

    public void setMinTvlUsd(double minTvlUsd) {
        this.minTvlUsd = minTvlUsd;
    }

    public double getMaxApy() {
        return maxApy;
    }

    public void setMaxApy(double maxApy) {
        this.maxApy = maxApy;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public int getMaxOptions() {
        return maxOptions;
    }

    public void setMaxOptions(int maxOptions) {
        this.maxOptions = maxOptions;
    }
}
