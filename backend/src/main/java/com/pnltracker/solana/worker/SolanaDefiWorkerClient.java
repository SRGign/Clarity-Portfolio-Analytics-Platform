package com.pnltracker.solana.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    private final RestClient restClient;

    public SolanaDefiWorkerClient(
            @Value("${solana.defi.worker.base-url:http://localhost:8787}") String baseUrl,
            @Value("${solana.defi.worker.timeout-seconds:30}") int timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .build();
    }

    public WorkerPositionsResponse fetchPositions(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return emptyResponse(walletAddress, "Wallet address is blank");
        }

        try {
            WorkerPositionsResponse response = restClient.post()
                    .uri("/defi/positions")
                    .body(new WorkerPositionsRequest(walletAddress.trim()))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, httpResponse) -> {
                        throw new WorkerRequestException(httpResponse.getStatusCode().value());
                    })
                    .body(WorkerPositionsResponse.class);

            if (response == null) {
                log.warn("Solana DeFi worker returned an empty response for wallet={}", walletAddress);
                return emptyResponse(walletAddress, "Worker returned an empty response");
            }
            return response;
        } catch (WorkerRequestException exception) {
            log.warn("Solana DeFi worker returned HTTP {} for wallet={}", exception.status, walletAddress);
            return emptyResponse(walletAddress, "Worker returned HTTP " + exception.status);
        } catch (RuntimeException exception) {
            log.warn("Solana DeFi worker request failed for wallet={}: {}", walletAddress, exception.getMessage());
            return emptyResponse(walletAddress, "Worker request failed");
        }
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
}
