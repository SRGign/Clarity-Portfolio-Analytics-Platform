package com.pnltracker.solana.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.pnltracker.solana.worker.dto.WorkerPosition;
import com.pnltracker.solana.worker.dto.WorkerPositionsRequest;
import com.pnltracker.solana.worker.dto.WorkerPositionsResponse;
import com.pnltracker.solana.worker.dto.WorkerProtocolError;

@Component
public class SolanaDefiWorkerClient {

    private static final Logger log = LoggerFactory.getLogger(SolanaDefiWorkerClient.class);

    private final List<WorkerEndpoint> endpoints;

    public SolanaDefiWorkerClient(
            @Value("${solana.defi.worker.base-url:http://localhost:8787}") String baseUrl,
            @Value("${solana.defi.worker.timeout-seconds:30}") int timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.endpoints = parseBaseUrls(baseUrl).stream()
                .map(url -> new WorkerEndpoint(url, RestClient.builder()
                        .baseUrl(url)
                        .requestFactory(requestFactory)
                        .build()))
                .toList();
    }

    public WorkerPositionsResponse fetchPositions(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return emptyResponse(walletAddress, "Wallet address is blank");
        }

        String lastFailure = "Worker request failed";

        for (WorkerEndpoint endpoint : endpoints) {
            try {
                WorkerPositionsResponse response = endpoint.client().post()
                        .uri("/defi/positions")
                        .body(new WorkerPositionsRequest(walletAddress.trim()))
                        .retrieve()
                        .onStatus(status -> !status.is2xxSuccessful(), (request, httpResponse) -> {
                            throw new WorkerRequestException(httpResponse.getStatusCode().value());
                        })
                        .body(WorkerPositionsResponse.class);

                if (response == null) {
                    lastFailure = "Worker returned an empty response";
                    log.warn("Solana DeFi worker returned an empty response for wallet={} endpoint={}",
                            walletAddress, endpoint.baseUrl());
                    continue;
                }
                return response;
            } catch (WorkerRequestException exception) {
                lastFailure = "Worker returned HTTP " + exception.status;
                log.warn("Solana DeFi worker returned HTTP {} for wallet={} endpoint={}",
                        exception.status, walletAddress, endpoint.baseUrl());
            } catch (RuntimeException exception) {
                lastFailure = "Worker request failed";
                log.warn("Solana DeFi worker request failed for wallet={} endpoint={}: {}",
                        walletAddress, endpoint.baseUrl(), exception.getMessage());
            }
        }

        return emptyResponse(walletAddress, lastFailure);
    }

    private List<String> parseBaseUrls(String baseUrls) {
        List<String> urls = Arrays.stream(Objects.toString(baseUrls, "").split(","))
                .map(String::trim)
                .filter(url -> !url.isBlank())
                .map(url -> url.replaceAll("/+$", ""))
                .distinct()
                .toList();
        return urls.isEmpty() ? List.of("http://localhost:8787") : urls;
    }

    private WorkerPositionsResponse emptyResponse(String walletAddress, String message) {
        return new WorkerPositionsResponse(
                walletAddress,
                Instant.now().toString(),
                List.<WorkerPosition>of(),
                List.of(new WorkerProtocolError("worker", message)));
    }

    private static class WorkerRequestException extends RuntimeException {
        private final int status;

        private WorkerRequestException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    private record WorkerEndpoint(String baseUrl, RestClient client) {
    }
}
