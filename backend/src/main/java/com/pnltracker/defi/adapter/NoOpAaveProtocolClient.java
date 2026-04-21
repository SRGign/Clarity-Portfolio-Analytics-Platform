package com.pnltracker.defi.adapter;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class NoOpAaveProtocolClient implements AaveProtocolClient {

    @Override
    public List<AaveUserReservePosition> fetchPositions(List<String> addresses, List<String> networks) {
        return List.of();
    }
}
