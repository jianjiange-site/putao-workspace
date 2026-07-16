package com.dating.gateway.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** gRPC Client Configuration. */
@Configuration
public class GrpcClientConfig {

    @Value("${user.service.grpc.host:localhost}")
    private String userServiceHost;

    @Value("${user.service.grpc.port:19090}")
    private int userServicePort;

    @Value("${im.service.grpc.host:localhost}")
    private String imServiceHost;

    @Value("${im.service.grpc.port:19091}")
    private int imServicePort;

    @Value("${post.service.grpc.host:localhost}")
    private String postServiceHost;

    @Value("${post.service.grpc.port:19084}")
    private int postServicePort;

    @Value("${match.service.grpc.host:localhost}")
    private String matchServiceHost;

    @Value("${match.service.grpc.port:19092}")
    private int matchServicePort;

    @Value("${payment.service.grpc.host:localhost}")
    private String paymentServiceHost;

    @Value("${payment.service.grpc.port:19093}")
    private int paymentServicePort;
}
