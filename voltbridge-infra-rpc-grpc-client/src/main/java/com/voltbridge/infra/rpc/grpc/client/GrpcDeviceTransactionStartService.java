package com.voltbridge.infra.rpc.grpc.client;

import com.voltbridge.contract.v1.DeviceAdmissionServiceGrpc;
import com.voltbridge.device.model.DeviceTransactionStartRequest;
import com.voltbridge.device.model.DeviceTransactionStartResult;
import com.voltbridge.device.port.DeviceTransactionStartService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Gateway 调用 Business 的设备主动交易同步准入端口。 */
@Component
@ConditionalOnProperty(name = "voltbridge.deployment.mode", havingValue = "gateway")
public class GrpcDeviceTransactionStartService implements DeviceTransactionStartService {
    private final ManagedChannel channel;
    private final DeviceAdmissionServiceGrpc.DeviceAdmissionServiceBlockingStub stub;

    public GrpcDeviceTransactionStartService(@org.springframework.beans.factory.annotation.Value("${voltbridge.grpc.business-target:localhost:9091}") String target) {
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        stub = DeviceAdmissionServiceGrpc.newBlockingStub(channel);
    }
    @Override public DeviceTransactionStartResult start(DeviceTransactionStartRequest request) {
        var reply = stub.withDeadlineAfter(2, TimeUnit.SECONDS).startDeviceTransaction(
                com.voltbridge.contract.v1.DeviceTransactionStartRequest.newBuilder().setDeviceId(request.deviceId().value())
                        .setProtocol(request.protocol().name()).setConnectorNo(request.connectorNo())
                        .setStartedAtEpochMillis(request.startedAt().toEpochMilli()).setStartMeterKwh(request.startMeterKwh().toPlainString())
                        .setStartMethod(request.startMethod().name()).setOccurredAtEpochMillis(request.occurredAt().toEpochMilli()).build());
        return new DeviceTransactionStartResult(reply.getAccepted(), reply.getProtocolTransactionId(),
                reply.getTransactionId().isBlank() ? null : UUID.fromString(reply.getTransactionId()), reply.getReason());
    }
    @PreDestroy void close() { channel.shutdownNow(); }
}
