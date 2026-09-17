package com.exchange.matching.server.service;

import com.exchange.matching.server.dto.RabbitQueueBacklog;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.*;

/** Poll broker state off the request/matching threads. Cache only in memory. */
public final class RabbitQueueMonitor implements AutoCloseable {
    private final HttpClient client;
    private final HttpRequest request;
    private final String queue;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "rabbit-queue-monitor"); thread.setDaemon(true); return thread;
    });
    private volatile RabbitQueueBacklog snapshot;

    public RabbitQueueMonitor(String baseUrl, String virtualHost, String queue, String username, String password) {
        this(baseUrl,virtualHost,queue,username,password,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }
    RabbitQueueMonitor(String baseUrl, String virtualHost, String queue, String username, String password, HttpClient client) {
        this.client=client;this.queue=queue;
        var uri=URI.create(baseUrl.replaceAll("/+$", "")+"/api/queues/"+encode(virtualHost)+"/"+encode(queue));
        if (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null)
            throw new IllegalArgumentException("RabbitMQ management URL must use http/https without embedded credentials");
        request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).header("Accept","application/json")
                .header("Authorization","Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8))).GET().build();
        unavailable("正在读取 RabbitMQ 队列");
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    public void start() { executor.scheduleWithFixedDelay(this::refresh,0,5,TimeUnit.SECONDS); }
    public RabbitQueueBacklog snapshot() { return snapshot; }
    void refresh() {
        try {
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200){
                unavailable(switch(response.statusCode()){
                    case 401,403 -> "RabbitMQ 管理接口认证失败或无权查看队列";
                    case 404 -> "未找到命令队列，请检查队列名称和虚拟主机";
                    default -> "RabbitMQ 管理接口暂不可用";
                });return;
            }
            var data=mapper.readTree(response.body());
            var ready=data.get("messages_ready");var unacked=data.get("messages_unacknowledged");
            if(ready==null||unacked==null||!ready.isIntegralNumber()||!unacked.isIntegralNumber()
                    ||!ready.canConvertToLong()||!unacked.canConvertToLong()||ready.longValue()<0||unacked.longValue()<0){
                unavailable("RabbitMQ 暂未提供队列计数，请检查管理统计设置");return;
            }
            snapshot=new RabbitQueueBacklog(queue,true,ready.longValue(),unacked.longValue(),
                    Math.addExact(ready.longValue(),unacked.longValue()),System.currentTimeMillis(),"每 5 秒采样，包含下单和撤单；数值受 RabbitMQ 统计刷新周期影响");
        } catch(InterruptedException error){Thread.currentThread().interrupt();unavailable("队列采样已中断");}
        catch(Exception error){unavailable("无法读取 RabbitMQ 队列，请检查管理接口地址和连接");}
    }
    private void unavailable(String message){snapshot=new RabbitQueueBacklog(queue,false,null,null,null,null,message);}
    @Override public void close(){executor.shutdownNow();client.close();}
}
