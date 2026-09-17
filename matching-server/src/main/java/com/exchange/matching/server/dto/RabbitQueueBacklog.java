package com.exchange.matching.server.dto;

/** Null counts mean unavailable, never an inferred empty queue. */
public record RabbitQueueBacklog(String queueName, boolean available, Long ready, Long unacked,
                                Long total, Long updatedAt, String message) {}
