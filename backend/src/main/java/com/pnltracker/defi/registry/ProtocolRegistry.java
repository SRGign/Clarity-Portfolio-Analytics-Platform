package com.pnltracker.defi.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.defi.model.DefiPositionType;

@Service
public class ProtocolRegistry {

    private final List<ProtocolDefinition> protocols;
    private final Map<String, WrapperTokenDefinition> wrapperTokensByKey;
    private final Map<String, DebtTokenDefinition> debtTokensByKey;

    public ProtocolRegistry(PortfolioProperties properties) {
        this.protocols = List.copyOf(loadProtocols(properties));
        this.wrapperTokensByKey = Map.copyOf(loadWrapperTokens(properties));
        this.debtTokensByKey = Map.copyOf(loadDebtTokens(properties));
    }

    public boolean supportsProtocol(String protocolKey) {
        return protocols.stream().anyMatch(protocol -> protocol.protocolKey().equalsIgnoreCase(protocolKey));
    }

    public List<WrapperTokenDefinition> wrapperTokens() {
        return wrapperTokensByKey.values().stream().toList();
    }

    public WrapperTokenDefinition findWrapperToken(String network, String tokenAddress) {
        String key = wrapperKey(network, tokenAddress);
        if (key == null) {
            return null;
        }
        return wrapperTokensByKey.get(key);
    }

    public List<DebtTokenDefinition> debtTokens() {
        return debtTokensByKey.values().stream().toList();
    }

    public DebtTokenDefinition findDebtToken(String network, String tokenAddress) {
        String key = wrapperKey(network, tokenAddress);
        if (key == null) {
            return null;
        }
        return debtTokensByKey.get(key);
    }

    private Map<String, WrapperTokenDefinition> loadWrapperTokens(PortfolioProperties properties) {
        Map<String, WrapperTokenDefinition> wrappersByKey = new LinkedHashMap<>();

        if (properties.getDefi().isEnabled()) {
            for (PortfolioProperties.ProtocolProperties protocol : properties.getDefi().getProtocols()) {
                if (protocol == null || !protocol.isEnabled()) {
                    continue;
                }
                for (PortfolioProperties.ProtocolNetworkProperties network : protocol.getNetworks()) {
                    if (network == null || !network.isEnabled()) {
                        continue;
                    }
                    for (PortfolioProperties.WrapperTokenProperties wrapper : network.getWrapperTokens()) {
                        putWrapper(
                                wrappersByKey,
                                network.getNetwork(),
                                protocol.getProtocolKey(),
                                protocol.getProtocolName(),
                                wrapper);
                    }
                }
            }
        }

        for (PortfolioProperties.WrapperTokenProperties wrapper : properties.getLending().getWrapperTokens()) {
            putWrapper(
                    wrappersByKey,
                    wrapper.getNetwork(),
                    wrapper.getProtocolKey(),
                    wrapper.getProtocolName(),
                    wrapper);
        }

        return wrappersByKey;
    }

    private List<ProtocolDefinition> loadProtocols(PortfolioProperties properties) {
        if (!properties.getDefi().isEnabled()) {
            return List.of();
        }

        return properties.getDefi().getProtocols().stream()
                .filter(protocol -> protocol != null && protocol.isEnabled())
                .peek(protocol -> validateRequired(protocol.getProtocolKey(), "protocolKey"))
                .peek(protocol -> validateRequired(protocol.getProtocolName(), "protocolName"))
                .map(protocol -> new ProtocolDefinition(protocol.getProtocolKey().trim(), protocol.getProtocolName().trim()))
                .toList();
    }

    private Map<String, DebtTokenDefinition> loadDebtTokens(PortfolioProperties properties) {
        Map<String, DebtTokenDefinition> debtTokensByKey = new LinkedHashMap<>();

        if (properties.getDefi().isEnabled()) {
            for (PortfolioProperties.ProtocolProperties protocol : properties.getDefi().getProtocols()) {
                if (protocol == null || !protocol.isEnabled()) {
                    continue;
                }
                for (PortfolioProperties.ProtocolNetworkProperties network : protocol.getNetworks()) {
                    if (network == null || !network.isEnabled()) {
                        continue;
                    }
                    for (PortfolioProperties.DebtTokenProperties debtToken : network.getDebtTokens()) {
                        putDebtToken(
                                debtTokensByKey,
                                network.getNetwork(),
                                protocol.getProtocolKey(),
                                protocol.getProtocolName(),
                                debtToken);
                    }
                }
            }
        }

        return debtTokensByKey;
    }

    private void putWrapper(
            Map<String, WrapperTokenDefinition> wrappersByKey,
            String network,
            String protocolKey,
            String protocolName,
            PortfolioProperties.WrapperTokenProperties wrapper) {
        validateRequired(network, "network");
        validateRequired(wrapper.getTokenAddress(), "tokenAddress");
        validateRequired(protocolKey, "protocolKey");
        validateRequired(protocolName, "protocolName");

        String key = wrapperKey(network, wrapper.getTokenAddress());
        WrapperTokenDefinition next = new WrapperTokenDefinition(
                normalize(network),
                normalize(wrapper.getTokenAddress()),
                protocolKey.trim(),
                protocolName.trim(),
                parsePositionType(wrapper.getPositionType()),
                normalizeNullable(wrapper.getUnderlyingTokenAddress()),
                normalizeNullable(wrapper.getUnderlyingSymbol()),
                normalizeNullable(wrapper.getUnderlyingName()),
                wrapper.getUnderlyingDecimals(),
                wrapper.isAlreadyCountedInSpotTotals());

        WrapperTokenDefinition existing = wrappersByKey.putIfAbsent(key, next);
        if (existing != null && !existing.equals(next)) {
            throw new IllegalStateException("Duplicate wrapper token registry entry for key: " + key);
        }
    }

    private void putDebtToken(
            Map<String, DebtTokenDefinition> debtTokensByKey,
            String network,
            String protocolKey,
            String protocolName,
            PortfolioProperties.DebtTokenProperties debtToken) {
        validateRequired(network, "network");
        validateRequired(debtToken.getTokenAddress(), "tokenAddress");
        validateRequired(protocolKey, "protocolKey");
        validateRequired(protocolName, "protocolName");

        String key = wrapperKey(network, debtToken.getTokenAddress());
        DebtTokenDefinition next = new DebtTokenDefinition(
                normalize(network),
                normalize(debtToken.getTokenAddress()),
                protocolKey.trim(),
                protocolName.trim(),
                normalizeNullable(debtToken.getUnderlyingTokenAddress()),
                normalizeNullable(debtToken.getUnderlyingSymbol()),
                normalizeNullable(debtToken.getUnderlyingName()));

        DebtTokenDefinition existing = debtTokensByKey.putIfAbsent(key, next);
        if (existing != null && !existing.equals(next)) {
            throw new IllegalStateException("Duplicate debt token registry entry for key: " + key);
        }
    }

    private void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required registry field: " + fieldName);
        }
    }

    private String wrapperKey(String network, String tokenAddress) {
        if (network == null || tokenAddress == null) {
            return null;
        }
        return normalize(network) + ":" + normalize(tokenAddress);
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private DefiPositionType parsePositionType(String value) {
        if (value == null || value.isBlank()) {
            return DefiPositionType.LENDING;
        }
        return DefiPositionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public record WrapperTokenDefinition(
            String network,
            String tokenAddress,
            String protocolKey,
            String protocolName,
            DefiPositionType positionType,
            String underlyingTokenAddress,
            String underlyingSymbol,
            String underlyingName,
            Integer underlyingDecimals,
            boolean alreadyCountedInSpotTotals) {
    }

    public record ProtocolDefinition(
            String protocolKey,
            String protocolName) {
    }

    public record DebtTokenDefinition(
            String network,
            String tokenAddress,
            String protocolKey,
            String protocolName,
            String underlyingTokenAddress,
            String underlyingSymbol,
            String underlyingName) {
    }
}
