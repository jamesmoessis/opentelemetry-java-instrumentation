/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.apachedubbo.v2_7;

import static io.opentelemetry.instrumentation.testing.GlobalTraceUtil.runWithSpan;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.satisfies;
import static io.opentelemetry.semconv.NetworkAttributes.NETWORK_PEER_ADDRESS;
import static io.opentelemetry.semconv.NetworkAttributes.NETWORK_PEER_PORT;
import static io.opentelemetry.semconv.NetworkAttributes.NETWORK_TYPE;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_ADDRESS;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_PORT;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.apachedubbo.v2_7.api.HelloService;
import io.opentelemetry.instrumentation.apachedubbo.v2_7.api.MiddleService;
import io.opentelemetry.instrumentation.apachedubbo.v2_7.impl.HelloServiceImpl;
import io.opentelemetry.instrumentation.apachedubbo.v2_7.impl.MiddleServiceImpl;
import io.opentelemetry.instrumentation.test.utils.PortUtils;
import io.opentelemetry.instrumentation.testing.internal.AutoCleanupExtension;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.semconv.incubating.RpcIncubatingAttributes;
import java.lang.reflect.Field;
import java.net.InetAddress;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.service.GenericService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public abstract class AbstractDubboTraceChainTest {

  @RegisterExtension static final AutoCleanupExtension cleanup = AutoCleanupExtension.create();

  @BeforeAll
  static void setUp() throws Exception {
    System.setProperty("dubbo.application.qos-enable", "false");
    Field field = NetUtils.class.getDeclaredField("LOCAL_ADDRESS");
    field.setAccessible(true);
    field.set(null, InetAddress.getLoopbackAddress());
  }

  @AfterAll
  static void tearDown() {
    System.clearProperty("dubbo.application.qos-enable");
  }

  protected abstract InstrumentationExtension testing();

  ReferenceConfig<HelloService> configureClient(int port) {
    ReferenceConfig<HelloService> reference = new ReferenceConfig<>();
    reference.setInterface(HelloService.class);
    reference.setGeneric("true");
    reference.setUrl("dubbo://localhost:" + port + "/?timeout=30000");
    return reference;
  }

  ReferenceConfig<HelloService> configureLocalClient(int port) {
    ReferenceConfig<HelloService> reference = new ReferenceConfig<>();
    reference.setInterface(HelloService.class);
    reference.setGeneric("true");
    reference.setUrl("injvm://localhost:" + port + "/?timeout=30000");
    return reference;
  }

  ReferenceConfig<MiddleService> configureMiddleClient(int port) {
    ReferenceConfig<MiddleService> reference = new ReferenceConfig<>();
    reference.setInterface(MiddleService.class);
    reference.setGeneric("true");
    reference.setUrl("dubbo://localhost:" + port + "/?timeout=30000");
    return reference;
  }

  ServiceConfig<HelloService> configureServer() {
    RegistryConfig registerConfig = new RegistryConfig();
    registerConfig.setAddress("N/A");
    ServiceConfig<HelloService> service = new ServiceConfig<>();
    service.setInterface(HelloService.class);
    service.setRef(new HelloServiceImpl());
    service.setRegistry(registerConfig);
    return service;
  }

  ServiceConfig<MiddleService> configureMiddleServer(
      ReferenceConfig<HelloService> referenceConfig) {
    RegistryConfig registerConfig = new RegistryConfig();
    registerConfig.setAddress("N/A");
    ServiceConfig<MiddleService> service = new ServiceConfig<>();
    service.setInterface(MiddleService.class);
    service.setRef(new MiddleServiceImpl(referenceConfig));
    service.setRegistry(registerConfig);
    return service;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  ReferenceConfig<GenericService> convertReference(ReferenceConfig<MiddleService> config) {
    return (ReferenceConfig) config;
  }

  @Test
  @DisplayName("test that context is propagated correctly in chained dubbo calls")
  void testDubboChain() throws ReflectiveOperationException {
    int port = PortUtils.findOpenPorts(2);
    int middlePort = port + 1;

    // setup hello service provider
    ProtocolConfig protocolConfig = new ProtocolConfig();
    protocolConfig.setPort(port);

    DubboBootstrap bootstrap = DubboTestUtil.newDubboBootstrap();
    cleanup.deferCleanup(bootstrap::destroy);
    bootstrap
        .application(new ApplicationConfig("dubbo-test-provider"))
        .service(configureServer())
        .protocol(protocolConfig)
        .start();

    // setup middle service provider, hello service consumer
    ProtocolConfig middleProtocolConfig = new ProtocolConfig();
    middleProtocolConfig.setPort(middlePort);

    ReferenceConfig<HelloService> clientReference = configureClient(port);
    DubboBootstrap middleBootstrap = DubboTestUtil.newDubboBootstrap();
    cleanup.deferCleanup(middleBootstrap::destroy);
    middleBootstrap
        .application(new ApplicationConfig("dubbo-demo-middle"))
        .reference(clientReference)
        .service(configureMiddleServer(clientReference))
        .protocol(middleProtocolConfig)
        .start();

    // setup middle service consumer
    ProtocolConfig consumerProtocolConfig = new ProtocolConfig();
    consumerProtocolConfig.setRegister(false);

    ReferenceConfig<MiddleService> middleReference = configureMiddleClient(middlePort);
    DubboBootstrap consumerBootstrap = DubboTestUtil.newDubboBootstrap();
    cleanup.deferCleanup(consumerBootstrap::destroy);
    consumerBootstrap
        .application(new ApplicationConfig("dubbo-demo-api-consumer"))
        .reference(middleReference)
        .protocol(consumerProtocolConfig)
        .start();

    GenericService genericService = convertReference(middleReference).get();

    Object response =
        runWithSpan(
            "parent",
            () ->
                genericService.$invoke(
                    "hello", new String[] {String.class.getName()}, new Object[] {"hello"}));

    assertThat(response).isEqualTo("hello");
    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                    span ->
                        span.hasName("org.apache.dubbo.rpc.service.GenericService/$invoke")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SYSTEM,
                                    RpcIncubatingAttributes.RpcSystemIncubatingValues.APACHE_DUBBO),
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SERVICE,
                                    "org.apache.dubbo.rpc.service.GenericService"),
                                equalTo(RpcIncubatingAttributes.RPC_METHOD, "$invoke"),
                                equalTo(SERVER_ADDRESS, "localhost"),
                                satisfies(SERVER_PORT, k -> k.isInstanceOf(Long.class)),
                                satisfies(
                                    NETWORK_PEER_ADDRESS,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isInstanceOf(String.class))),
                                satisfies(
                                    NETWORK_PEER_PORT,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isInstanceOf(Long.class))),
                                satisfies(
                                    NETWORK_TYPE,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isEqualTo("ipv4"),
                                            val -> assertThat(val).isEqualTo("ipv6")))),
                    span ->
                        span.hasName(
                                "io.opentelemetry.instrumentation.apachedubbo.v2_7.api.MiddleService/hello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(1))
                            .hasAttributesSatisfying(
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SYSTEM,
                                    RpcIncubatingAttributes.RpcSystemIncubatingValues.APACHE_DUBBO),
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SERVICE,
                                    "io.opentelemetry.instrumentation.apachedubbo.v2_7.api.MiddleService"),
                                equalTo(RpcIncubatingAttributes.RPC_METHOD, "hello"),
                                satisfies(NETWORK_PEER_ADDRESS, k -> k.isInstanceOf(String.class)),
                                satisfies(NETWORK_PEER_PORT, k -> k.isInstanceOf(Long.class)),
                                satisfies(
                                    NETWORK_TYPE,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isEqualTo("ipv4"),
                                            val -> assertThat(val).isEqualTo("ipv6")))),
                    span ->
                        span.hasName("org.apache.dubbo.rpc.service.GenericService/$invoke")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(2))
                            .hasAttributesSatisfyingExactly(
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SYSTEM,
                                    RpcIncubatingAttributes.RpcSystemIncubatingValues.APACHE_DUBBO),
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SERVICE,
                                    "org.apache.dubbo.rpc.service.GenericService"),
                                equalTo(RpcIncubatingAttributes.RPC_METHOD, "$invoke"),
                                equalTo(SERVER_ADDRESS, "localhost"),
                                satisfies(SERVER_PORT, k -> k.isInstanceOf(Long.class)),
                                satisfies(
                                    NETWORK_PEER_ADDRESS,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isInstanceOf(String.class))),
                                satisfies(
                                    NETWORK_PEER_PORT,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isInstanceOf(Long.class))),
                                satisfies(
                                    NETWORK_TYPE,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isEqualTo("ipv4"),
                                            val -> assertThat(val).isEqualTo("ipv6")))),
                    span ->
                        span.hasName(
                                "io.opentelemetry.instrumentation.apachedubbo.v2_7.api.HelloService/hello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(3))
                            .hasAttributesSatisfying(
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SYSTEM,
                                    RpcIncubatingAttributes.RpcSystemIncubatingValues.APACHE_DUBBO),
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SERVICE,
                                    "io.opentelemetry.instrumentation.apachedubbo.v2_7.api.HelloService"),
                                equalTo(RpcIncubatingAttributes.RPC_METHOD, "hello"),
                                satisfies(NETWORK_PEER_ADDRESS, k -> k.isInstanceOf(String.class)),
                                satisfies(NETWORK_PEER_PORT, k -> k.isInstanceOf(Long.class)),
                                satisfies(
                                    NETWORK_TYPE,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isEqualTo("ipv4"),
                                            val -> assertThat(val).isEqualTo("ipv6"))))));
  }

  @Test
  @DisplayName("test ignore injvm calls")
  void testDubboChainInJvm() throws ReflectiveOperationException {
    int port = PortUtils.findOpenPorts(2);
    int middlePort = port + 1;

    // setup hello service provider
    ProtocolConfig protocolConfig = new ProtocolConfig();
    protocolConfig.setPort(port);

    DubboBootstrap bootstrap = DubboTestUtil.newDubboBootstrap();
    cleanup.deferCleanup(bootstrap::destroy);
    bootstrap
        .application(new ApplicationConfig("dubbo-test-provider"))
        .service(configureServer())
        .protocol(protocolConfig)
        .start();

    // setup middle service provider, hello service consumer
    ProtocolConfig middleProtocolConfig = new ProtocolConfig();
    middleProtocolConfig.setPort(middlePort);

    ReferenceConfig<HelloService> clientReference = configureLocalClient(port);
    DubboBootstrap middleBootstrap = DubboTestUtil.newDubboBootstrap();
    cleanup.deferCleanup(middleBootstrap::destroy);
    middleBootstrap
        .application(new ApplicationConfig("dubbo-demo-middle"))
        .service(configureMiddleServer(clientReference))
        .protocol(middleProtocolConfig)
        .start();

    // setup middle service consumer
    ProtocolConfig consumerProtocolConfig = new ProtocolConfig();
    consumerProtocolConfig.setRegister(false);

    ReferenceConfig<MiddleService> middleReference = configureMiddleClient(middlePort);
    DubboBootstrap consumerBootstrap = DubboTestUtil.newDubboBootstrap();
    cleanup.deferCleanup(consumerBootstrap::destroy);
    consumerBootstrap
        .application(new ApplicationConfig("dubbo-demo-api-consumer"))
        .reference(middleReference)
        .protocol(consumerProtocolConfig)
        .start();

    GenericService genericService = convertReference(middleReference).get();

    Object response =
        runWithSpan(
            "parent",
            () ->
                genericService.$invoke(
                    "hello", new String[] {String.class.getName()}, new Object[] {"hello"}));

    assertThat(response).isEqualTo("hello");
    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                    span ->
                        span.hasName("org.apache.dubbo.rpc.service.GenericService/$invoke")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SYSTEM,
                                    RpcIncubatingAttributes.RpcSystemIncubatingValues.APACHE_DUBBO),
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SERVICE,
                                    "org.apache.dubbo.rpc.service.GenericService"),
                                equalTo(RpcIncubatingAttributes.RPC_METHOD, "$invoke"),
                                equalTo(SERVER_ADDRESS, "localhost"),
                                satisfies(SERVER_PORT, k -> k.isInstanceOf(Long.class)),
                                satisfies(
                                    NETWORK_PEER_ADDRESS,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isInstanceOf(String.class))),
                                satisfies(
                                    NETWORK_PEER_PORT,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isInstanceOf(Long.class))),
                                satisfies(
                                    NETWORK_TYPE,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isEqualTo("ipv4"),
                                            val -> assertThat(val).isEqualTo("ipv6")))),
                    span ->
                        span.hasName(
                                "io.opentelemetry.instrumentation.apachedubbo.v2_7.api.MiddleService/hello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(1))
                            .hasAttributesSatisfying(
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SYSTEM,
                                    RpcIncubatingAttributes.RpcSystemIncubatingValues.APACHE_DUBBO),
                                equalTo(
                                    RpcIncubatingAttributes.RPC_SERVICE,
                                    "io.opentelemetry.instrumentation.apachedubbo.v2_7.api.MiddleService"),
                                equalTo(RpcIncubatingAttributes.RPC_METHOD, "hello"),
                                satisfies(NETWORK_PEER_ADDRESS, k -> k.isInstanceOf(String.class)),
                                satisfies(NETWORK_PEER_PORT, k -> k.isInstanceOf(Long.class)),
                                satisfies(
                                    NETWORK_TYPE,
                                    k ->
                                        k.satisfiesAnyOf(
                                            val -> assertThat(val).isNull(),
                                            val -> assertThat(val).isEqualTo("ipv4"),
                                            val -> assertThat(val).isEqualTo("ipv6"))))));
  }
}
