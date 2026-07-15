package com.dating.post.config;

import com.dating.user.proto.UserServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC Client 配置.
 *
 * <p>配置 user-service 的 stub 注入.
 */
@Configuration
public class GrpcClientConfig {

    @Value("${user.service.grpc.host:localhost}")
    private String userServiceHost;

    @Value("${user.service.grpc.port:19090}")
    private int userServicePort;

    /**
     * user-service gRPC blocking stub.
     *
     * <p>连接到 user-service 获取用户信息.
     */
    @Bean
    public UserServiceGrpc.UserServiceBlockingStub userServiceBlockingStub() {
        return UserServiceGrpc.newBlockingStub(
                ManagedChannelBuilder.forAddress(userServiceHost, userServicePort)
                        .usePlaintext()
                        .build()
        );
    }
}
