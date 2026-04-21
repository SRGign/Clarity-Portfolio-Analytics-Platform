package com.pnltracker.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.pnltracker.domain.ChainDefinition;

@Service
public class ChainCatalogService {

    private final Map<String, ChainDefinition> chainsById;

    public ChainCatalogService() {
        Map<String, ChainDefinition> chains = new LinkedHashMap<>();
        register(chains, "ethereum", "eth-mainnet", "Ethereum");
        register(chains, "polygon", "polygon-mainnet", "Polygon");
        register(chains, "arbitrum", "arb-mainnet", "Arbitrum");
        register(chains, "optimism", "opt-mainnet", "Optimism");
        register(chains, "base", "base-mainnet", "Base");
        register(chains, "zksync", "zksync-mainnet", "zkSync Era");
        register(chains, "linea", "linea-mainnet", "Linea");
        register(chains, "scroll", "scroll-mainnet", "Scroll");
        register(chains, "mantle", "mantle-mainnet", "Mantle");
        register(chains, "zora", "zora-mainnet", "Zora");
        register(chains, "blast", "blast-mainnet", "Blast");
        register(chains, "arbitrum-nova", "arbnova-mainnet", "Arbitrum Nova");
        register(chains, "polygon-zkevm", "polygonzkevm-mainnet", "Polygon zkEVM");
        register(chains, "zetachain", "zetachain-mainnet", "ZetaChain");
        register(chains, "berachain", "berachain-mainnet", "Berachain");
        register(chains, "avalanche", "avax-mainnet", "Avalanche");
        register(chains, "bnb", "bnb-mainnet", "BNB Chain");
        register(chains, "celo", "celo-mainnet", "Celo");
        register(chains, "sei", "sei-mainnet", "Sei");
        register(chains, "apechain", "apechain-mainnet", "ApeChain");
        register(chains, "abstract", "abstract-mainnet", "Abstract");
        register(chains, "moonbeam", "moonbeam-mainnet", "Moonbeam");
        this.chainsById = Map.copyOf(chains);
    }

    private void register(Map<String, ChainDefinition> chains, String id, String providerNetwork, String displayName) {
        chains.put(id, new ChainDefinition(id, providerNetwork, displayName, "EVM", true));
    }

    public List<ChainDefinition> all() {
        return chainsById.values().stream().toList();
    }

    public List<ChainDefinition> resolve(List<String> requestedChains) {
        if (requestedChains == null || requestedChains.isEmpty()) {
            return all();
        }
        return requestedChains.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(String::toLowerCase)
                .distinct()
                .map(this::resolveSingle)
                .toList();
    }

    private ChainDefinition resolveSingle(String value) {
        ChainDefinition byId = chainsById.get(value);
        if (byId != null) {
            return byId;
        }
        return chainsById.values().stream()
                .filter(chain -> chain.providerNetwork().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported chain: " + value));
    }

    public String displayNameForNetwork(String network) {
        if ("hyperliquid".equalsIgnoreCase(network)) {
            return "Hyperliquid";
        }
        if ("matic-mainnet".equalsIgnoreCase(network)) {
            return "Polygon";
        }
        return chainsById.values().stream()
                .filter(chain -> chain.providerNetwork().equalsIgnoreCase(network))
                .map(ChainDefinition::displayName)
                .findFirst()
                .orElse(network);
    }

    public Set<String> supportedNetworks() {
        return chainsById.values().stream().map(ChainDefinition::providerNetwork).collect(Collectors.toSet());
    }
}
