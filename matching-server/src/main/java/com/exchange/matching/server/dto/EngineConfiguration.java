package com.exchange.matching.server.dto;

import com.exchange.matching.core.orderbook.EngineParameters;

/** Configuration takes effect after this many durable commands; replay preserves historical decisions. */
public record EngineConfiguration(long effectiveAfterCommands, EngineParameters parameters) {}
