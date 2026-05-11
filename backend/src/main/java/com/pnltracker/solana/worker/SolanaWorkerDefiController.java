package com.pnltracker.solana.worker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pnltracker.solana.worker.dto.WorkerPositionsResponse;

@RestController
@RequestMapping("/api/v1/wallets")
public class SolanaWorkerDefiController {

    private final SolanaDefiWorkerClient workerClient;

    public SolanaWorkerDefiController(SolanaDefiWorkerClient workerClient) {
        this.workerClient = workerClient;
    }

    @GetMapping("/{address}/solana/defi-positions")
    public WorkerPositionsResponse getWorkerDefiPositions(@PathVariable String address) {
        return workerClient.fetchPositions(address);
    }
}
