package com.looped.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;

@Configuration
public class RedisConfig {
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(@Value("${spring.data.redis.url:}") String redisUrl) {
        if (redisUrl == null || redisUrl.isBlank()) {
            return new LettuceConnectionFactory();
        }
        URI uri = URI.create(redisUrl);
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration();
        conf.setHostName(uri.getHost());
        conf.setPort(uri.getPort() == -1 ? 6379 : uri.getPort());
        if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
            String password = uri.getUserInfo().substring(uri.getUserInfo().indexOf(':') + 1);
            conf.setPassword(password);
        }
        return new LettuceConnectionFactory(conf);
    }
}

