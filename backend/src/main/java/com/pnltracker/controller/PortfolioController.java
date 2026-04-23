package com.pnltracker.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pnltracker.api.AssetRowResponse;
import com.pnltracker.api.ChainAllocationResponse;
import com.pnltracker.api.ChainResponse;
import com.pnltracker.api.DefiPositionResponse;
import com.pnltracker.api.DefiPositionSummaryResponse;
import com.pnltracker.api.LendingPositionResponse;
import com.pnltracker.api.LendingPositionSummaryResponse;
import com.pnltracker.api.PortfolioHistoryPointResponse;
import com.pnltracker.api.PortfolioHistoryRequest;
import com.pnltracker.api.PortfolioHistoryResponse;
import com.pnltracker.api.PortfolioAssetsResponse;
import com.pnltracker.api.PortfolioOverviewResponse;
import com.pnltracker.api.PortfolioPositionsResponse;
import com.pnltracker.api.PortfolioRequest;
import com.pnltracker.api.PortfolioSummaryResponse;
import com.pnltracker.api.WalletAllocationResponse;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.LendingPosition;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.service.ChainCatalogService;
import com.pnltracker.service.PortfolioHistoryPeriod;
import com.pnltracker.service.PortfolioHistoryResult;
import com.pnltracker.service.PortfolioOverviewService;
import com.pnltracker.service.PortfolioHistoryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
@Validated
public class PortfolioController {

    private final PortfolioOverviewService portfolioOverviewService;
    private final ChainCatalogService chainCatalogService;
    private final PortfolioHistoryService portfolioHistoryService;

    public PortfolioController(
            PortfolioOverviewService portfolioOverviewService,
            ChainCatalogService chainCatalogService,
            PortfolioHistoryService portfolioHistoryService) {
        this.portfolioOverviewService = portfolioOverviewService;
        this.chainCatalogService = chainCatalogService;
        this.portfolioHistoryService = portfolioHistoryService;
    }

    @GetMapping("/chains")
    public List<ChainResponse> chains() {
        return chainCatalogService.all().stream()
                .map(chain -> new ChainResponse(
                        chain.id(),
                        chain.providerNetwork(),
                        chain.displayName(),
                        chain.family(),
                        chain.enabled()))
                .toList();
    }

    @PostMapping("/portfolio/overview")
    public PortfolioOverviewResponse overview(@Valid @RequestBody PortfolioRequest request) {
        PortfolioAnalysis overview = portfolioOverviewService.getOverview(request.addresses(), request.chains());
        portfolioHistoryService.recordLiveSnapshot(
                request.addresses(),
                request.chains(),
                overview.summary().totalUsd());
        return new PortfolioOverviewResponse(
                toSummaryResponse(overview),
                toAssetRows(overview.assets()),
                toLendingPositions(overview.lendingPositions()),
                toLendingSummaryResponse(overview),
                toDefiPositions(overview),
                toDefiSummaryResponse(overview));
    }

    @PostMapping("/portfolio/summary")
    public PortfolioSummaryResponse summary(@Valid @RequestBody PortfolioRequest request) {
        return toSummaryResponse(portfolioOverviewService.getOverview(request.addresses(), request.chains()));
    }

    @PostMapping("/portfolio/assets")
    public PortfolioAssetsResponse assets(@Valid @RequestBody PortfolioRequest request) {
        return new PortfolioAssetsResponse(
                toAssetRows(portfolioOverviewService.getOverview(request.addresses(), request.chains()).assets()));
    }

    @PostMapping("/portfolio/positions")
    public PortfolioPositionsResponse positions(@Valid @RequestBody PortfolioRequest request) {
        PortfolioAnalysis overview = portfolioOverviewService.getOverview(request.addresses(), request.chains());
        return new PortfolioPositionsResponse(
                toLendingPositions(overview.lendingPositions()),
                toLendingSummaryResponse(overview),
                toDefiPositions(overview),
                toDefiSummaryResponse(overview));
    }

    @PostMapping("/portfolio/history")
    public PortfolioHistoryResponse history(@Valid @RequestBody PortfolioHistoryRequest request) {
        PortfolioHistoryResult history = portfolioHistoryService.getHistory(
                request.addresses(),
                request.chains(),
                PortfolioHistoryPeriod.fromApiValue(request.period()));
        return new PortfolioHistoryResponse(
                history.period().apiValue(),
                history.scopeHash(),
                history.points().stream()
                        .map(point -> new PortfolioHistoryPointResponse(
                                point.localDate().toString(),
                                point.totalUsd(),
                                point.source(),
                                point.persisted()))
                        .toList(),
                history.partial(),
                history.missingChains(),
                history.asOf().toString());
    }

    private PortfolioSummaryResponse toSummaryResponse(PortfolioAnalysis analysis) {
        return new PortfolioSummaryResponse(
                analysis.summary().totalUsd(),
                analysis.summary().grossAssetUsd(),
                analysis.summary().grossLiabilityUsd(),
                analysis.summary().netUsd(),
                analysis.summary().trackedAssets(),
                analysis.summary().hiddenAssets(),
                analysis.summary().allocations().stream()
                        .map(allocation -> new ChainAllocationResponse(
                                allocation.network(),
                                allocation.displayName(),
                                allocation.valueUsd()))
                        .toList(),
                analysis.summary().walletAllocations().stream()
                        .map(allocation -> new WalletAllocationResponse(
                                allocation.walletAddress(),
                                allocation.valueUsd()))
                        .toList());
    }

    private List<AssetRowResponse> toAssetRows(List<AssetBalance> assets) {
        return assets.stream()
                .map(asset -> new AssetRowResponse(
                        asset.assetId(),
                        asset.network(),
                        asset.tokenAddress(),
                        asset.symbol(),
                        asset.name(),
                        asset.decimals(),
                        asset.quantity(),
                        asset.priceUsd(),
                        asset.valueUsd(),
                        asset.nativeToken(),
                        asset.logoUrl()))
                .toList();
    }

    private List<LendingPositionResponse> toLendingPositions(List<LendingPosition> positions) {
        return positions.stream()
                .map(position -> new LendingPositionResponse(
                        position.positionId(),
                        position.walletAddress(),
                        position.network(),
                        position.protocolKey(),
                        position.protocolName(),
                        position.positionSide().apiValue(),
                        position.detectionMode().apiValue(),
                        position.coverage().apiValue(),
                        position.sourceAssetId(),
                        position.underlyingTokenAddress(),
                        position.underlyingSymbol(),
                        position.underlyingName(),
                        position.quantity(),
                        position.priceUsd(),
                        position.supplyUsd(),
                        position.debtUsd(),
                        position.netUsd(),
                        position.alreadyCountedInPortfolio()))
                .toList();
    }

    private LendingPositionSummaryResponse toLendingSummaryResponse(PortfolioAnalysis analysis) {
        return new LendingPositionSummaryResponse(
                analysis.lendingSummary().trackedPositions(),
                analysis.lendingSummary().visibleSupplyUsd(),
                analysis.lendingSummary().visibleDebtUsd(),
                analysis.lendingSummary().visibleNetUsd(),
                analysis.lendingSummary().partialCoverage(),
                analysis.lendingSummary().detectionModes().stream()
                        .map(mode -> mode.apiValue())
                        .toList());
    }

    private List<DefiPositionResponse> toDefiPositions(PortfolioAnalysis analysis) {
        return analysis.defiPositions().stream()
                .map(position -> new DefiPositionResponse(
                        position.positionId(),
                        position.walletAddress(),
                        position.network(),
                        position.protocolKey(),
                        position.protocolName(),
                        position.positionType().apiValue(),
                        position.positionSide().apiValue(),
                        position.detectionMode().apiValue(),
                        position.coverage().apiValue(),
                        position.sourceAssetId(),
                        position.underlyingTokenAddress(),
                        position.underlyingSymbol(),
                        position.underlyingName(),
                        position.quantity(),
                        position.priceUsd(),
                        position.grossSupplyUsd(),
                        position.grossDebtUsd(),
                        position.netUsd(),
                        position.alreadyCountedInPortfolio()))
                .toList();
    }

    private DefiPositionSummaryResponse toDefiSummaryResponse(PortfolioAnalysis analysis) {
        return new DefiPositionSummaryResponse(
                analysis.defiSummary().trackedPositions(),
                analysis.defiSummary().visibleSupplyUsd(),
                analysis.defiSummary().visibleDebtUsd(),
                analysis.defiSummary().visibleNetUsd(),
                analysis.defiSummary().partialCoverage(),
                analysis.defiSummary().detectionModes().stream()
                        .map(mode -> mode.apiValue())
                        .toList());
    }
}
