package com.voltbridge.infra.rpc.grpc.server;

import com.voltbridge.contract.mapper.DeviceProtoMapper;
import com.voltbridge.contract.v1.DeviceAdmissionReply;
import com.voltbridge.contract.v1.DeviceAdmissionRequest;
import com.voltbridge.contract.v1.DeviceAdmissionServiceGrpc;
import com.voltbridge.device.model.DeviceConnectionDecision;
import com.voltbridge.device.port.DeviceConnectionAuthorizer;
import com.voltbridge.device.port.DeviceTransactionStartService;
import io.grpc.stub.StreamObserver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Business Service 对设备登录与 BootNotification 提供的同步准入服务。 */
@Component
@ConditionalOnProperty(name = "voltbridge.deployment.mode", havingValue = "business")
public class BusinessAdmissionGrpcService extends DeviceAdmissionServiceGrpc.DeviceAdmissionServiceImplBase {

    private final DeviceConnectionAuthorizer authorizer;
    private final DeviceTransactionStartService transactionStartService;

    public BusinessAdmissionGrpcService(DeviceConnectionAuthorizer authorizer, DeviceTransactionStartService transactionStartService) {
        this.authorizer = authorizer;
        this.transactionStartService = transactionStartService;
    }

    @Override
    public void authorize(DeviceAdmissionRequest request, StreamObserver<DeviceAdmissionReply> observer) {
        DeviceConnectionDecision decision = authorizer.authorize(DeviceProtoMapper.fromAdmissionRequest(request));
        observer.onNext(DeviceAdmissionReply.newBuilder().setAccepted(decision.accepted()).setReason(decision.reason()).build());
        observer.onCompleted();
    }

    @Override
    public void startDeviceTransaction(com.voltbridge.contract.v1.DeviceTransactionStartRequest request,
                                       StreamObserver<com.voltbridge.contract.v1.DeviceTransactionStartReply> observer) {
        var result = transactionStartService.start(new com.voltbridge.device.model.DeviceTransactionStartRequest(
                new com.voltbridge.device.model.DeviceId(request.getDeviceId()),
                com.voltbridge.device.model.DeviceProtocol.valueOf(request.getProtocol()), request.getConnectorNo(),
                java.time.Instant.ofEpochMilli(request.getStartedAtEpochMillis()), new java.math.BigDecimal(request.getStartMeterKwh()),
                com.voltbridge.device.model.ChargingStartMethod.valueOf(request.getStartMethod()),
                java.time.Instant.ofEpochMilli(request.getOccurredAtEpochMillis())));
        observer.onNext(com.voltbridge.contract.v1.DeviceTransactionStartReply.newBuilder().setAccepted(result.accepted())
                .setProtocolTransactionId(result.protocolTransactionId()).setTransactionId(result.transactionId() == null ? "" : result.transactionId().toString())
                .setReason(result.reason()).build());
        observer.onCompleted();
    }
}
