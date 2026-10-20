package com.voltbridge.infra.rpc.grpc.server;

import com.voltbridge.contract.mapper.DeviceProtoMapper;
import com.voltbridge.contract.v1.DispatchCommandReply;
import com.voltbridge.contract.v1.DispatchCommandRequest;
import com.voltbridge.contract.v1.DispatchStatus;
import com.voltbridge.contract.v1.GatewayRpcServiceGrpc;
import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.device.model.DeviceId;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Business 到 Gateway 的真实 gRPC 编码、调用和本地命令接收边界。 */
class GatewayCommandGrpcServiceTest {

    @Test
    void dispatchesCanonicalCommandOverGrpc() throws Exception {
        AtomicReference<DeviceCommand> received = new AtomicReference<>();
        Server server = NettyServerBuilder.forPort(0).addService(new GatewayCommandGrpcService(command -> {
            received.set(command);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        })).build().start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try {
            StartChargingCommand command = new StartChargingCommand(UUID.randomUUID(), new DeviceId("YKC-TEST-01"), "1",
                    new BigDecimal("20.50"), "logic", "physical", "business", Instant.ofEpochMilli(System.currentTimeMillis()));
            DispatchCommandReply reply = GatewayRpcServiceGrpc.newBlockingStub(channel)
                    .dispatchCommand(DispatchCommandRequest.newBuilder().setCommand(DeviceProtoMapper.toProto(command)).build());

            assertThat(reply.getStatus()).isEqualTo(DispatchStatus.DISPATCH_ACCEPTED);
            assertThat(received.get()).isEqualTo(command);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void deduplicatesRepeatedCanonicalCommandIdOnSameGateway() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        Server server = NettyServerBuilder.forPort(0).addService(new GatewayCommandGrpcService(command -> {
            dispatches.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        })).build().start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try {
            StartChargingCommand command = new StartChargingCommand(UUID.randomUUID(), new DeviceId("YKC-TEST-02"), "1",
                    new BigDecimal("20.50"), "logic", "physical", "business", Instant.now());
            DispatchCommandRequest request = DispatchCommandRequest.newBuilder().setCommand(DeviceProtoMapper.toProto(command)).build();
            GatewayRpcServiceGrpc.GatewayRpcServiceBlockingStub stub = GatewayRpcServiceGrpc.newBlockingStub(channel);

            assertThat(stub.dispatchCommand(request).getStatus()).isEqualTo(DispatchStatus.DISPATCH_ACCEPTED);
            assertThat(stub.dispatchCommand(request).getStatus()).isEqualTo(DispatchStatus.DISPATCH_ACCEPTED);
            assertThat(dispatches).hasValue(1);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }
}
