package com.voltbridge.infra.rpc.grpc.client;

import com.voltbridge.contract.mapper.DeviceProtoMapper;
import com.voltbridge.contract.v1.DeviceAdmissionReply;
import com.voltbridge.contract.v1.DeviceAdmissionServiceGrpc;
import com.voltbridge.device.event.DeviceConnectedEvent;
import com.voltbridge.device.model.DeviceConnectionDecision;
import com.voltbridge.device.port.DeviceConnectionAuthorizer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Gateway 的同步入网适配器；业务服务不可达时按保守策略拒绝设备登录。 */
@Component
@ConditionalOnProperty(name = "voltbridge.deployment.mode", havingValue = "gateway")
public class GrpcDeviceConnectionAuthorizer implements DeviceConnectionAuthorizer {

    private final ManagedChannel channel;
    private final DeviceAdmissionServiceGrpc.DeviceAdmissionServiceBlockingStub stub;
    private final Duration timeout;

    public GrpcDeviceConnectionAuthorizer(@Value("${voltbridge.grpc.business-target:localhost:9091}") String target,
                                          @Value("${voltbridge.grpc.admission-timeout:2s}") Duration timeout) {
        this.channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        this.stub = DeviceAdmissionServiceGrpc.newBlockingStub(channel);
        this.timeout = timeout;
    }

    @Override
    public DeviceConnectionDecision authorize(DeviceConnectedEvent event) {
        try {
            DeviceAdmissionReply reply = stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .authorize(DeviceProtoMapper.toAdmissionRequest(event));
            return new DeviceConnectionDecision(reply.getAccepted(), reply.getReason());
        } catch (RuntimeException exception) {
            return DeviceConnectionDecision.reject("business_admission_unavailable");
        }
    }

    @PreDestroy
    void close() {
        channel.shutdownNow();
    }
}
