package com.exchange.matching.server.service;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitQueueMonitorTests {
    @Test void readsActualReadyAndUnackedAndEncodesQueuePath() throws Exception {
        var client=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"messages_ready\":7,\"messages_unacknowledged\":3}");
        try(var monitor=new RabbitQueueMonitor("http://localhost:15672/","/tenant","orders test/engine","guest","guest",client)){
            assertFalse(monitor.snapshot().available());monitor.refresh();
            var snapshot=monitor.snapshot();assertTrue(snapshot.available());
            assertEquals(7L,snapshot.ready());assertEquals(3L,snapshot.unacked());assertEquals(10L,snapshot.total());
            var request=ArgumentCaptor.forClass(HttpRequest.class);
            verify(client).send(request.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
            assertEquals("/api/queues/%2Ftenant/orders%20test%2Fengine",request.getValue().uri().getRawPath());
            assertEquals("GET",request.getValue().method());
        }
    }
    @Test void failedOrIncompleteSamplesDoNotLookLikeEmptyQueue() throws Exception {
        var client=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"messages_ready\":5,\"messages_unacknowledged\":1}");
        try(var monitor=new RabbitQueueMonitor("http://localhost:15672","/","commands.engine","guest","guest",client)){
            monitor.refresh();assertEquals(6L,monitor.snapshot().total());
            when(response.statusCode()).thenReturn(403);monitor.refresh();
            assertFalse(monitor.snapshot().available());assertNull(monitor.snapshot().total());assertNull(monitor.snapshot().updatedAt());
            when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{}");monitor.refresh();
            assertFalse(monitor.snapshot().available());assertNull(monitor.snapshot().ready());
            when(response.body()).thenReturn("{\"messages_ready\":0,\"messages_unacknowledged\":0}");monitor.refresh();
            assertTrue(monitor.snapshot().available());assertEquals(0L,monitor.snapshot().total());
        }
    }
}
