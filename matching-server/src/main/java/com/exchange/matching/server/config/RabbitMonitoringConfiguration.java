package com.exchange.matching.server.config;
import com.exchange.matching.server.service.RabbitQueueMonitor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
@Profile({"messaging","configuration"})
public class RabbitMonitoringConfiguration {
    @Bean(initMethod="start",destroyMethod="close")
    RabbitQueueMonitor rabbitQueueMonitor(Environment env) {
        String destination=env.getProperty("spring.cloud.stream.bindings.commands-in-0.destination","matching.commands");
        String group=env.getProperty("spring.cloud.stream.bindings.commands-in-0.group","engine");
        String prefix=env.getProperty("spring.cloud.stream.rabbit.bindings.commands-in-0.consumer.prefix",
                env.getProperty("spring.cloud.stream.rabbit.default.consumer.prefix",""));
        boolean groupOnly=env.getProperty("spring.cloud.stream.rabbit.bindings.commands-in-0.consumer.queue-name-group-only",Boolean.class,
                env.getProperty("spring.cloud.stream.rabbit.default.consumer.queue-name-group-only",Boolean.class,false));
        String queue=env.getProperty("matching.rabbitmq.command-queue",prefix+(groupOnly?group:destination+"."+group));
        String host=env.getProperty("spring.rabbitmq.host","localhost");
        String url=env.getProperty("matching.rabbitmq.management-url","http://"+host+":15672");
        return new RabbitQueueMonitor(url,env.getProperty("spring.rabbitmq.virtual-host","/"),queue,
                env.getProperty("matching.rabbitmq.management-username",env.getProperty("spring.rabbitmq.username","guest")),
                env.getProperty("matching.rabbitmq.management-password",env.getProperty("spring.rabbitmq.password","guest")));
    }
}
