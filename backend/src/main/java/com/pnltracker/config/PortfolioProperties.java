package com.pnltracker.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "portfolio")
public class PortfolioProperties {

    private String provider = "alchemy";
    private long cacheTtlSeconds = 180L;
    private final AlchemyProperties alchemy = new AlchemyProperties();
    private final GoldRushProperties goldrush = new GoldRushProperties();
    private final AsyncProperties async = new AsyncProperties();
    private final DefiProperties defi = new DefiProperties();
    private final LendingProperties lending = new LendingProperties();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public AlchemyProperties getAlchemy() {
        return alchemy;
    }

    public GoldRushProperties getGoldrush() {
        return goldrush;
    }

    public AsyncProperties getAsync() {
        return async;
    }

    public DefiProperties getDefi() {
        return defi;
    }

    public LendingProperties getLending() {
        return lending;
    }

    public static class AlchemyProperties {
        private String baseUrl = "https://api.g.alchemy.com";
        private String apiKey = "docs-demo";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public static class AsyncProperties {
        private int alchemyConcurrency = 4;
        private int alchemyQueueCapacity = 32;

        public int getAlchemyConcurrency() {
            return alchemyConcurrency;
        }

        public void setAlchemyConcurrency(int alchemyConcurrency) {
            this.alchemyConcurrency = alchemyConcurrency;
        }

        public int getAlchemyQueueCapacity() {
            return alchemyQueueCapacity;
        }

        public void setAlchemyQueueCapacity(int alchemyQueueCapacity) {
            this.alchemyQueueCapacity = alchemyQueueCapacity;
        }
    }

    public static class GoldRushProperties {
        private boolean enabled = true;
        private String baseUrl = "https://api.covalenthq.com";
        private String apiKey = "";
        private int timeoutSeconds = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class LendingProperties {
        private List<WrapperTokenProperties> wrapperTokens = new ArrayList<>();

        public List<WrapperTokenProperties> getWrapperTokens() {
            return wrapperTokens;
        }

        public void setWrapperTokens(List<WrapperTokenProperties> wrapperTokens) {
            this.wrapperTokens = wrapperTokens == null ? new ArrayList<>() : new ArrayList<>(wrapperTokens);
        }
    }

    public static class DefiProperties {
        private boolean enabled = true;
        private List<ProtocolProperties> protocols = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<ProtocolProperties> getProtocols() {
            return protocols;
        }

        public void setProtocols(List<ProtocolProperties> protocols) {
            this.protocols = protocols == null ? new ArrayList<>() : new ArrayList<>(protocols);
        }
    }

    public static class ProtocolProperties {
        private String protocolKey;
        private String protocolName;
        private boolean enabled = true;
        private List<ProtocolNetworkProperties> networks = new ArrayList<>();

        public String getProtocolKey() {
            return protocolKey;
        }

        public void setProtocolKey(String protocolKey) {
            this.protocolKey = protocolKey;
        }

        public String getProtocolName() {
            return protocolName;
        }

        public void setProtocolName(String protocolName) {
            this.protocolName = protocolName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<ProtocolNetworkProperties> getNetworks() {
            return networks;
        }

        public void setNetworks(List<ProtocolNetworkProperties> networks) {
            this.networks = networks == null ? new ArrayList<>() : new ArrayList<>(networks);
        }
    }

    public static class ProtocolNetworkProperties {
        private String network;
        private boolean enabled = true;
        private List<WrapperTokenProperties> wrapperTokens = new ArrayList<>();
        private List<DebtTokenProperties> debtTokens = new ArrayList<>();

        public String getNetwork() {
            return network;
        }

        public void setNetwork(String network) {
            this.network = network;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<WrapperTokenProperties> getWrapperTokens() {
            return wrapperTokens;
        }

        public void setWrapperTokens(List<WrapperTokenProperties> wrapperTokens) {
            this.wrapperTokens = wrapperTokens == null ? new ArrayList<>() : new ArrayList<>(wrapperTokens);
        }

        public List<DebtTokenProperties> getDebtTokens() {
            return debtTokens;
        }

        public void setDebtTokens(List<DebtTokenProperties> debtTokens) {
            this.debtTokens = debtTokens == null ? new ArrayList<>() : new ArrayList<>(debtTokens);
        }
    }

    public static class WrapperTokenProperties {
        private String network;
        private String tokenAddress;
        private String protocolKey;
        private String protocolName;
        private String positionType = "lending";
        private String underlyingTokenAddress;
        private String underlyingSymbol;
        private String underlyingName;
        private Integer underlyingDecimals;
        private boolean alreadyCountedInSpotTotals = true;

        public String getNetwork() {
            return network;
        }

        public void setNetwork(String network) {
            this.network = network;
        }

        public String getTokenAddress() {
            return tokenAddress;
        }

        public void setTokenAddress(String tokenAddress) {
            this.tokenAddress = tokenAddress;
        }

        public String getProtocolKey() {
            return protocolKey;
        }

        public void setProtocolKey(String protocolKey) {
            this.protocolKey = protocolKey;
        }

        public String getProtocolName() {
            return protocolName;
        }

        public void setProtocolName(String protocolName) {
            this.protocolName = protocolName;
        }

        public String getPositionType() {
            return positionType;
        }

        public void setPositionType(String positionType) {
            this.positionType = positionType;
        }

        public String getUnderlyingTokenAddress() {
            return underlyingTokenAddress;
        }

        public void setUnderlyingTokenAddress(String underlyingTokenAddress) {
            this.underlyingTokenAddress = underlyingTokenAddress;
        }

        public String getUnderlyingSymbol() {
            return underlyingSymbol;
        }

        public void setUnderlyingSymbol(String underlyingSymbol) {
            this.underlyingSymbol = underlyingSymbol;
        }

        public String getUnderlyingName() {
            return underlyingName;
        }

        public void setUnderlyingName(String underlyingName) {
            this.underlyingName = underlyingName;
        }

        public Integer getUnderlyingDecimals() {
            return underlyingDecimals;
        }

        public void setUnderlyingDecimals(Integer underlyingDecimals) {
            this.underlyingDecimals = underlyingDecimals;
        }

        public boolean isAlreadyCountedInSpotTotals() {
            return alreadyCountedInSpotTotals;
        }

        public void setAlreadyCountedInSpotTotals(boolean alreadyCountedInSpotTotals) {
            this.alreadyCountedInSpotTotals = alreadyCountedInSpotTotals;
        }
    }

    public static class DebtTokenProperties {
        private String tokenAddress;
        private String underlyingTokenAddress;
        private String underlyingSymbol;
        private String underlyingName;

        public String getTokenAddress() {
            return tokenAddress;
        }

        public void setTokenAddress(String tokenAddress) {
            this.tokenAddress = tokenAddress;
        }

        public String getUnderlyingTokenAddress() {
            return underlyingTokenAddress;
        }

        public void setUnderlyingTokenAddress(String underlyingTokenAddress) {
            this.underlyingTokenAddress = underlyingTokenAddress;
        }

        public String getUnderlyingSymbol() {
            return underlyingSymbol;
        }

        public void setUnderlyingSymbol(String underlyingSymbol) {
            this.underlyingSymbol = underlyingSymbol;
        }

        public String getUnderlyingName() {
            return underlyingName;
        }

        public void setUnderlyingName(String underlyingName) {
            this.underlyingName = underlyingName;
        }
    }
}
