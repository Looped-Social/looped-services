package com.looped.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
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
            String userInfo = uri.getUserInfo();
            // Support optional "username:password" or just ":password"
            int idx = userInfo.indexOf(':');
            String password = idx >= 0 ? userInfo.substring(idx + 1) : userInfo;
            if (!password.isBlank()) {
                conf.setPassword(password);
            }
        }

        boolean useSsl = uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("rediss");
        if (useSsl) {
            LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                    .useSsl()
                    .build();
            return new LettuceConnectionFactory(conf, clientConfig);
        }
        return new LettuceConnectionFactory(conf);
    }
}
