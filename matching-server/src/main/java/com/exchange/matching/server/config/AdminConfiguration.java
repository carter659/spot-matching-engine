package com.exchange.matching.server.config;
import com.exchange.matching.server.repository.AdminStore;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class AdminConfiguration {
    @Bean(destroyMethod="close") AdminStore adminStore(@Value("${matching.admin-data-directory:${matching.home:.}/data}") String directory) throws IOException {
        return new AdminStore(Path.of(directory));
    }
}
