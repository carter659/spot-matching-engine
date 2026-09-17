package com.exchange.matching.protocol.event;

public record MatchingOutcome(OrderResult result, MarketUpdate market) {}
