package com.dating.gateway.interceptor;

import com.dating.gateway.security.RequestContext;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC Client Interceptor for injecting request metadata.
 */
@Component
public class GrpcClientMetadataInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcClientMetadataInterceptor.class);
    private static final String USER_ID_KEY = "user_id";
    private static final String DEVICE_ID_KEY = "device_id";
    private static final String TRACE_ID_KEY = "trace_id";

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        
        Metadata metadata = new Metadata();
        RequestContext ctx = RequestContext.current();
        
        if (ctx.getUserId() != null) {
            metadata.put(Metadata.Key.of(USER_ID_KEY, Metadata.ASCII_STRING_MARSHALLER), 
                    ctx.getUserId().toString());
        }
        if (ctx.getDeviceId() != null) {
            metadata.put(Metadata.Key.of(DEVICE_ID_KEY, Metadata.ASCII_STRING_MARSHALLER), 
                    ctx.getDeviceId());
        }
        if (ctx.getTraceId() != null) {
            metadata.put(Metadata.Key.of(TRACE_ID_KEY, Metadata.ASCII_STRING_MARSHALLER), 
                    ctx.getTraceId());
        }

        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.merge(metadata);
                super.start(responseListener, headers);
            }
        };
    }
}
