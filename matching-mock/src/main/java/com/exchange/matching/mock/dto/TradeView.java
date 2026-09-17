package com.exchange.matching.mock.dto;



public record TradeView(String tradeId, String makerOrderId, String takerOrderId, String price, String quantity,
                            String receivedAt) {}
