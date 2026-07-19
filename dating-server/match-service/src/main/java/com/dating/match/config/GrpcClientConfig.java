package com.dating.match.config;

import com.dating.im.proto.ImServiceGrpc;
import com.dating.payment.proto.PaymentServiceGrpc;
import com.dating.user.proto.UserServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC Client 配置 — 调用 user / payment / im 服务.
 */
@Configuration
public class GrpcClientConfig {

    @Value("${user.service.grpc.host:localhost}")
    private String userServiceHost;

    @Value("${user.service.grpc.port:19090}")
    private int userServicePort;

    @Value("${payment.service.grpc.host:localhost}")
    private String paymentServiceHost;

    @Value("${payment.service.grpc.port:19093}")
    private int paymentServicePort;

    @Value("${im.service.grpc.host:localhost}")
    private String imServiceHost;

    @Value("${im.service.grpc.port:19092}")
    private int imServicePort;

    @Bean
    public UserServiceGrpc.UserServiceBlockingStub userServiceBlockingStub() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(userServiceHost, userServicePort)
                .usePlaintext()
                .build();
        return UserServiceGrpc.newBlockingStub(channel);
    }

    @Bean
    public PaymentServiceGrpc.PaymentServiceBlockingStub paymentServiceBlockingStub() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(paymentServiceHost, paymentServicePort)
                .usePlaintext()
                .build();
        return PaymentServiceGrpc.newBlockingStub(channel);
    }

    @Bean
    public ImServiceGrpc.ImServiceBlockingStub imServiceBlockingStub() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(imServiceHost, imServicePort)
                .usePlaintext()
                .build();
        return ImServiceGrpc.newBlockingStub(channel);
    }
}
