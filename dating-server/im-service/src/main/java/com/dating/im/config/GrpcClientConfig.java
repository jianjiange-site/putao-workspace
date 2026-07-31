package com.dating.im.config;

import org.springframework.context.annotation.Configuration;

import com.dating.user.proto.UserServiceGrpc;
import com.dating.payment.proto.PaymentServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * gRPC Client 配置.
 *
 * <p>配置 user-service 和 payment-service 的 stub 注入.
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
    @Value("${payment.security.internal-token:}")
    private String paymentInternalToken;


    @Value("${ai-chat.service.grpc.host:localhost}")
    private String aiChatServiceHost;

    @Value("${ai-chat.service.grpc.port:19095}")
    private int aiChatServicePort;

    /**
     * user-service gRPC blocking stub.
     */
    @Bean
    public UserServiceGrpc.UserServiceBlockingStub userServiceBlockingStub() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(userServiceHost, userServicePort)
                .usePlaintext()
                .build();
        return UserServiceGrpc.newBlockingStub(channel);
    }

    /**
     * payment-service gRPC blocking stub.
     */
    @Bean
    public PaymentServiceGrpc.PaymentServiceBlockingStub paymentServiceBlockingStub() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(
                        paymentServiceHost, paymentServicePort)
                .usePlaintext()
                .build();
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-service-name", Metadata.ASCII_STRING_MARSHALLER),
                "im-service");
        metadata.put(Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER),
                paymentInternalToken);
        return PaymentServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
}
