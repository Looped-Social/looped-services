package com.looped.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class MessageMediaS3Config {
    @Bean(name = "messageMediaS3Presigner")
    public S3Presigner messageMediaS3Presigner(@Value("${s3.messaging.region:${s3.region}}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder().build())
                .build();
    }

    @Bean(name = "messageMediaS3Client")
    public S3Client messageMediaS3Client(@Value("${s3.messaging.region:${s3.region}}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder().build())
                .build();
    }
}

