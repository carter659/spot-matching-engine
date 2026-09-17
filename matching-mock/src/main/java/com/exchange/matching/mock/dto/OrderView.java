package com.exchange.matching.mock.dto;



public record OrderView(String orderId, String commandId, String symbol, String side, String orderType,
                            String timeInForce, String price, String quantity, String filledQuantity,
                            String remainingQuantity, String cancelledQuantity, String status, String reason,
                            boolean canCancel, boolean cancelPending) {}
