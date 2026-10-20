package com.voltbridge.bootstrap;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 保护业务、传输、协议和持久化之间最关键的编译期边界。 */
class ArchitectureDependencyTest {

    private final JavaClasses classes = new ClassFileImporter().importPackages("com.voltbridge");

    @Test
    void businessDomainDoesNotDependOnProtocolTransportOrInfrastructureImplementations() {
        noClasses().that().resideInAPackage("com.voltbridge.business.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.voltbridge.protocol..",
                        "com.voltbridge.transport..",
                        "com.voltbridge.infra..")
                .check(classes);
    }

    @Test
    void businessApplicationDoesNotDependOnConcreteProtocolsOrNetworkRuntimes() {
        noClasses().that().resideInAPackage("com.voltbridge.business.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.voltbridge.protocol.yunkuaichong..",
                        "com.voltbridge.protocol.ocpp16..",
                        "io.netty..",
                        "org.apache.kafka..",
                        "io.grpc..")
                .check(classes);
    }

    @Test
    void transportsDoNotDependOnBusinessApplication() {
        noClasses().that().resideInAPackage("com.voltbridge.transport..")
                .should().dependOnClassesThat().resideInAPackage("com.voltbridge.business.application..")
                .check(classes);
    }

    @Test
    void protocolsDoNotDependOnPersistenceImplementation() {
        noClasses().that().resideInAPackage("com.voltbridge.protocol..")
                .should().dependOnClassesThat().resideInAPackage("com.voltbridge.infra.persistence..")
                .check(classes);
    }
}
