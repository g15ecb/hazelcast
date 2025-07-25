/*
 * Copyright (c) 2008-2025, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.config;

import com.hazelcast.client.util.RandomLB;
import com.hazelcast.client.util.RoundRobinLB;
import com.hazelcast.config.CredentialsFactoryConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.InstanceTrackingConfig;
import com.hazelcast.config.InvalidConfigurationException;
import com.hazelcast.config.NativeMemoryConfig;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.config.PersistentMemoryConfig;
import com.hazelcast.config.PersistentMemoryDirectoryConfig;
import com.hazelcast.config.SerializationConfig;
import com.hazelcast.config.YamlConfigBuilderTest;
import com.hazelcast.config.security.KerberosIdentityConfig;
import com.hazelcast.config.security.TokenIdentityConfig;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.internal.serialization.impl.compact.CompactTestUtil;
import com.hazelcast.memory.Capacity;
import com.hazelcast.memory.MemoryUnit;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import com.hazelcast.topic.TopicOverloadPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static com.hazelcast.config.PersistentMemoryMode.MOUNTED;
import static com.hazelcast.config.PersistentMemoryMode.SYSTEM_MEMORY;
import static com.hazelcast.internal.nio.IOUtil.delete;
import static com.hazelcast.internal.util.RootCauseMatcher.rootCause;
import static com.hazelcast.memory.MemoryUnit.GIGABYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * This class tests the usage of {@link YamlClientConfigBuilder}
 */
// tests need to be executed sequentially because of system properties being set/unset
@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class YamlClientConfigBuilderTest extends AbstractClientConfigBuilderTest {

    @Before
    public void init() throws Exception {
        URL schemaResource = YamlConfigBuilderTest.class.getClassLoader().getResource("hazelcast-client-full.yaml");
        fullClientConfig = new YamlClientConfigBuilder(schemaResource).build();

        URL schemaResourceDefault = YamlConfigBuilderTest.class.getClassLoader().getResource("hazelcast-client-default.yaml");
        defaultClientConfig = new YamlClientConfigBuilder(schemaResourceDefault).build();
    }

    @After
    @Before
    public void beforeAndAfter() {
        System.clearProperty("hazelcast.client.config");
    }

    @Override
    @Test(expected = HazelcastException.class)
    public void loadingThroughSystemProperty_nonExistingFile() throws IOException {
        File file = File.createTempFile("foo", ".yaml");
        delete(file);
        System.setProperty("hazelcast.client.config", file.getAbsolutePath());

        new YamlClientConfigBuilder();
    }

    @Override
    @Test
    public void loadingThroughSystemProperty_existingFile() throws IOException {
        String yaml = "hazelcast-client:\n"
                + "  cluster-name: foobar";

        File file = File.createTempFile("foo", ".yaml");
        file.deleteOnExit();
        PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8);
        writer.println(yaml);
        writer.close();

        System.setProperty("hazelcast.client.config", file.getAbsolutePath());

        YamlClientConfigBuilder configBuilder = new YamlClientConfigBuilder();
        ClientConfig config = configBuilder.build();
        assertEquals("foobar", config.getClusterName());
    }

    @Override
    @Test(expected = HazelcastException.class)
    public void loadingThroughSystemProperty_nonExistingClasspathResource() {
        System.setProperty("hazelcast.client.config", "classpath:idontexist.yaml");
        new YamlClientConfigBuilder();
    }

    @Override
    @Test
    public void loadingThroughSystemProperty_existingClasspathResource() {
        System.setProperty("hazelcast.client.config", "classpath:test-hazelcast-client.yaml");

        YamlClientConfigBuilder configBuilder = new YamlClientConfigBuilder();
        ClientConfig config = configBuilder.build();
        assertEquals("foobar-yaml", config.getClusterName());
        assertEquals("com.hazelcast.nio.ssl.BasicSSLContextFactory",
                config.getNetworkConfig().getSSLConfig().getFactoryClassName());
        assertEquals(128, config.getNetworkConfig().getSocketOptions().getBufferSize());
        assertFalse(config.getNetworkConfig().getSocketOptions().isKeepAlive());
        assertFalse(config.getNetworkConfig().getSocketOptions().isTcpNoDelay());
        assertEquals(3, config.getNetworkConfig().getSocketOptions().getLingerSeconds());
    }

    @Override
    @Test
    public void testFlakeIdGeneratorConfig() {
        String yaml = """
                hazelcast-client:
                  flake-id-generator:
                    gen:
                      prefetch-count: 3
                      prefetch-validity-millis: 10
                """;

        ClientConfig config = buildConfig(yaml);
        ClientFlakeIdGeneratorConfig fConfig = config.findFlakeIdGeneratorConfig("gen");
        assertEquals("gen", fConfig.getName());
        assertEquals(3, fConfig.getPrefetchCount());
        assertEquals(10L, fConfig.getPrefetchValidityMillis());
    }

    @Override
    @Test
    public void testSecurityConfig_onlyFactory() {
        String yaml = """
                hazelcast-client:
                  security:
                    credentials-factory:
                      class-name: com.hazelcast.examples.MyCredentialsFactory
                      properties:
                        property: value
                """;

        ClientConfig config = buildConfig(yaml);
        ClientSecurityConfig securityConfig = config.getSecurityConfig();
        CredentialsFactoryConfig credentialsFactoryConfig = securityConfig.getCredentialsFactoryConfig();
        assertEquals("com.hazelcast.examples.MyCredentialsFactory", credentialsFactoryConfig.getClassName());
        Properties properties = credentialsFactoryConfig.getProperties();
        assertEquals("value", properties.getProperty("property"));
    }

    @Override
    @Test(expected = InvalidConfigurationException.class)
    public void testHazelcastClientTagAppearsTwice() {
        String yaml = "hazelcast-client: {}\n"
                + "hazelcast-client: {}";
        buildConfig(yaml);
    }

    @Override
    @Test
    public void testNearCacheInMemoryFormatNative_withKeysByReference() {
        String mapName = "testMapNearCacheInMemoryFormatNative";
        String yaml = "hazelcast-client:\n"
                + "  near-cache:\n"
                + "    " + mapName + ":\n"
                + "      in-memory-format: NATIVE\n"
                + "      serialize-keys: false";

        ClientConfig clientConfig = buildConfig(yaml);
        NearCacheConfig ncConfig = clientConfig.getNearCacheConfig(mapName);

        assertEquals(InMemoryFormat.NATIVE, ncConfig.getInMemoryFormat());
        assertTrue(ncConfig.isSerializeKeys());
    }

    @Override
    @Test
    public void testNearCacheEvictionPolicy() {
        String yaml = """
                hazelcast-client:
                  near-cache:
                    lfu:
                      eviction:
                        eviction-policy: LFU
                    lru:
                      eviction:
                        eviction-policy: LRU
                    none:
                      eviction:
                        eviction-policy: NONE
                    random:
                      eviction:
                        eviction-policy: RANDOM
                """;

        ClientConfig clientConfig = buildConfig(yaml);
        assertEquals(EvictionPolicy.LFU, getNearCacheEvictionPolicy("lfu", clientConfig));
        assertEquals(EvictionPolicy.LRU, getNearCacheEvictionPolicy("lru", clientConfig));
        assertEquals(EvictionPolicy.NONE, getNearCacheEvictionPolicy("none", clientConfig));
        assertEquals(EvictionPolicy.RANDOM, getNearCacheEvictionPolicy("random", clientConfig));
    }

    @Override
    @Test
    public void testClientUserCodeDeploymentConfig() {
        String yaml = """
                hazelcast-client:
                  user-code-deployment:
                    enabled: true
                    jarPaths:
                      - /User/test/test.jar
                    classNames:
                      - test.testClassName
                      - test.testClassName2
                """;

        ClientConfig clientConfig = buildConfig(yaml);
        ClientUserCodeDeploymentConfig userCodeDeploymentConfig = clientConfig.getUserCodeDeploymentConfig();
        assertTrue(userCodeDeploymentConfig.isEnabled());
        List<String> classNames = userCodeDeploymentConfig.getClassNames();
        assertEquals(2, classNames.size());
        assertTrue(classNames.contains("test.testClassName"));
        assertTrue(classNames.contains("test.testClassName2"));
        List<String> jarPaths = userCodeDeploymentConfig.getJarPaths();
        assertEquals(1, jarPaths.size());
        assertTrue(jarPaths.contains("/User/test/test.jar"));
    }

    @Override
    @Test
    public void testReliableTopic_defaults() {
        String yaml = """
                hazelcast-client:
                  reliable-topic:
                    rel-topic: {}
                """;

        ClientConfig config = buildConfig(yaml);
        ClientReliableTopicConfig reliableTopicConfig = config.getReliableTopicConfig("rel-topic");
        assertEquals("rel-topic", reliableTopicConfig.getName());
        assertEquals(10, reliableTopicConfig.getReadBatchSize());
        assertEquals(TopicOverloadPolicy.BLOCK, reliableTopicConfig.getTopicOverloadPolicy());
    }

    @Override
    @Test(expected = InvalidConfigurationException.class)
    public void testQueryCacheBothPredicateDefinedThrows() {
        String yaml = """
                hazelcast-client:
                  query-caches:
                    query-cache-name:
                      map-name: map-name
                      predicate:
                        class-name: com.hazelcast.example.Predicate
                        sql: "%age=40"
                """;
        buildConfig(yaml);
    }

    @Override
    @Test(expected = InvalidConfigurationException.class)
    public void testQueryCacheNoPredicateDefinedThrows() {
        String yaml = """
                hazelcast-client:
                  query-caches:
                    query-cache-name:
                      predicate: {}
                """;
        buildConfig(yaml);
    }

    @Override
    @Test
    public void testLoadBalancerRandom() {
        String yaml = """
                hazelcast-client:
                  load-balancer:
                    type: random
                """;

        ClientConfig config = buildConfig(yaml);

        assertInstanceOf(RandomLB.class, config.getLoadBalancer());
        assertNull(config.getLoadBalancerClassName());
    }

    @Override
    @Test
    public void testLoadBalancerRoundRobin() {
        String yaml = """
                hazelcast-client:
                  load-balancer:
                    type: round-robin
                """;

        ClientConfig config = buildConfig(yaml);

        assertInstanceOf(RoundRobinLB.class, config.getLoadBalancer());
        assertNull(config.getLoadBalancerClassName());
    }

    @Override
    @Test
    public void testLoadBalancerCustom() {
        String yaml = """
                hazelcast-client:
                  load-balancer:
                    type: custom
                    class-name: com.hazelcast.client.test.CustomLoadBalancer
                """;

        ClientConfig config = buildConfig(yaml);

        assertNull(config.getLoadBalancer());
        assertEquals("com.hazelcast.client.test.CustomLoadBalancer", config.getLoadBalancerClassName());
    }

    @Test
    public void testNullInMapThrows() {
        String yaml = """
                hazelcast-client:
                  group:
                  name: instanceName
                """;

        assertThatThrownBy(() -> buildConfig(yaml)).has(rootCause(InvalidConfigurationException.class, "hazelcast-client/group"));
    }

    @Test
    public void testNullInSequenceThrows() {
        String yaml = """
                hazelcast-client:
                  client-labels:
                    - admin
                    -
                """;

        assertThatThrownBy(() -> buildConfig(yaml)).has(rootCause(InvalidConfigurationException.class, "hazelcast-client/client-labels"));
    }

    @Test
    public void testExplicitNullScalarThrows() {
        String yaml = """
                hazelcast-client:
                  group:
                   name: !!null""";

        assertThatThrownBy(() -> buildConfig(yaml)).has(rootCause(InvalidConfigurationException.class, "hazelcast-client/group/name"));
    }

    @Override
    @Test
    public void testWhitespaceInNonSpaceStrings() {
        String yaml = """
                hazelcast-client:
                  load-balancer:
                    type:   random  \s
                """;

        buildConfig(yaml);
    }

    @Override
    @Test
    public void testTokenIdentityConfig() {
        String yaml = """
                hazelcast-client:
                  security:
                    token:
                      encoding: base64
                      value: SGF6ZWxjYXN0
                """;

        ClientConfig config = buildConfig(yaml);
        TokenIdentityConfig tokenIdentityConfig = config.getSecurityConfig().getTokenIdentityConfig();
        assertNotNull(tokenIdentityConfig);
        assertArrayEquals("Hazelcast".getBytes(StandardCharsets.US_ASCII), tokenIdentityConfig.getToken());
        assertEquals("SGF6ZWxjYXN0", tokenIdentityConfig.getTokenEncoded());
    }

    @Override
    @Test
    public void testKerberosIdentityConfig() {
        String yaml = """
                hazelcast-client:
                  security:
                    kerberos:
                      realm: HAZELCAST.COM
                      principal: jduke
                      keytab-file: /opt/jduke.keytab
                      security-realm: krb5Initiator
                      service-name-prefix: hz/
                      use-canonical-hostname: true
                      spn: hz/127.0.0.1@HAZELCAST.COM
                """;

        ClientConfig config = buildConfig(yaml);
        KerberosIdentityConfig identityConfig = config.getSecurityConfig().getKerberosIdentityConfig();
        assertNotNull(identityConfig);
        assertEquals("HAZELCAST.COM", identityConfig.getRealm());
        assertEquals("jduke", identityConfig.getPrincipal());
        assertEquals("/opt/jduke.keytab", identityConfig.getKeytabFile());
        assertEquals("krb5Initiator", identityConfig.getSecurityRealm());
        assertEquals("hz/", identityConfig.getServiceNamePrefix());
        assertTrue(identityConfig.getUseCanonicalHostname());
        assertEquals("hz/127.0.0.1@HAZELCAST.COM", identityConfig.getSpn());
    }

    @Override
    @Test
    public void testMetricsConfig() {
        String yaml = """
                hazelcast-client:
                  metrics:
                    enabled: false
                    jmx:
                      enabled: false
                    collection-frequency-seconds: 10
                """;
        ClientConfig config = buildConfig(yaml);
        ClientMetricsConfig metricsConfig = config.getMetricsConfig();
        assertFalse(metricsConfig.isEnabled());
        assertFalse(metricsConfig.getJmxConfig().isEnabled());
        assertEquals(10, metricsConfig.getCollectionFrequencySeconds());
    }

    @Override
    public void testInstanceTrackingConfig() {
        String yaml = """
                hazelcast-client:
                  instance-tracking:
                    enabled: true
                    file-name: /dummy/file
                    format-pattern: dummy-pattern with $HZ_INSTANCE_TRACKING{placeholder} and $RND{placeholder}""";
        ClientConfig config = buildConfig(yaml);
        InstanceTrackingConfig trackingConfig = config.getInstanceTrackingConfig();
        assertTrue(trackingConfig.isEnabled());
        assertEquals("/dummy/file", trackingConfig.getFileName());
        assertEquals("dummy-pattern with $HZ_INSTANCE_TRACKING{placeholder} and $RND{placeholder}",
                trackingConfig.getFormatPattern());
    }

    @Override
    @Test
    public void testMetricsConfigMasterSwitchDisabled() {
        String yaml = """
                hazelcast-client:
                  metrics:
                    enabled: false""";
        ClientConfig config = buildConfig(yaml);
        ClientMetricsConfig metricsConfig = config.getMetricsConfig();
        assertFalse(metricsConfig.isEnabled());
        assertTrue(metricsConfig.getJmxConfig().isEnabled());
    }

    @Override
    @Test
    public void testMetricsConfigJmxDisabled() {
        String yaml = """
                hazelcast-client:
                  metrics:
                    jmx:
                      enabled: false""";
        ClientConfig config = buildConfig(yaml);
        ClientMetricsConfig metricsConfig = config.getMetricsConfig();
        assertTrue(metricsConfig.isEnabled());
        assertFalse(metricsConfig.getJmxConfig().isEnabled());
    }

    @Test
    public void nativeMemory() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    enabled: true
                    allocator-type: STANDARD
                    min-block-size: 32
                    page-size: 24
                    capacity:
                      unit: BYTES
                      value: 256
                    metadata-space-percentage: 70""";

        ClientConfig config = buildConfig(yaml);

        NativeMemoryConfig nativeMemoryConfig = config.getNativeMemoryConfig();
        assertTrue(nativeMemoryConfig.isEnabled());
        assertEquals(NativeMemoryConfig.MemoryAllocatorType.STANDARD, nativeMemoryConfig.getAllocatorType());
        assertEquals(32, nativeMemoryConfig.getMinBlockSize());
        assertEquals(24, nativeMemoryConfig.getPageSize());
        assertEquals(MemoryUnit.BYTES, nativeMemoryConfig.getCapacity().getUnit());
        assertEquals(256, nativeMemoryConfig.getCapacity().getValue());
        assertEquals(70, nativeMemoryConfig.getMetadataSpacePercentage(), 10E-6);
    }

    @Override
    @Test
    public void testPersistentMemoryDirectoryConfiguration() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    persistent-memory:
                      directories:
                        - directory: /mnt/pmem0
                          numa-node: 0
                        - directory: /mnt/pmem1
                          numa-node: 1
                """;
        ClientConfig config = buildConfig(yaml);
        List<PersistentMemoryDirectoryConfig> directoryConfigs = config.getNativeMemoryConfig()
                .getPersistentMemoryConfig()
                .getDirectoryConfigs();
        assertEquals(2, directoryConfigs.size());
        PersistentMemoryDirectoryConfig dir0Config = directoryConfigs.get(0);
        PersistentMemoryDirectoryConfig dir1Config = directoryConfigs.get(1);
        assertEquals("/mnt/pmem0", dir0Config.getDirectory());
        assertEquals(0, dir0Config.getNumaNode());
        assertEquals("/mnt/pmem1", dir1Config.getDirectory());
        assertEquals(1, dir1Config.getNumaNode());
    }

    @Override
    @Test
    public void testPersistentMemoryDirectoryConfigurationSimple() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    persistent-memory-directory: /mnt/pmem0""";

        ClientConfig config = buildConfig(yaml);
        PersistentMemoryConfig pmemConfig = config.getNativeMemoryConfig().getPersistentMemoryConfig();
        assertTrue(pmemConfig.isEnabled());

        List<PersistentMemoryDirectoryConfig> directoryConfigs = pmemConfig.getDirectoryConfigs();
        assertEquals(1, directoryConfigs.size());
        PersistentMemoryDirectoryConfig dir0Config = directoryConfigs.get(0);
        assertEquals("/mnt/pmem0", dir0Config.getDirectory());
        assertFalse(dir0Config.isNumaNodeSet());
    }

    @Override
    @Test(expected = InvalidConfigurationException.class)
    public void testPersistentMemoryDirectoryConfiguration_uniqueDirViolationThrows() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    persistent-memory:
                      directories:
                        - directory: /mnt/pmem0
                          numa-node: 0
                        - directory: /mnt/pmem0
                          numa-node: 1
                """;

        buildConfig(yaml);
    }

    @Override
    @Test(expected = InvalidConfigurationException.class)
    public void testPersistentMemoryDirectoryConfiguration_uniqueNumaNodeViolationThrows() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    persistent-memory:
                      directories:
                        - directory: /mnt/pmem0
                          numa-node: 0
                        - directory: /mnt/pmem1
                          numa-node: 0
                """;

        buildConfig(yaml);
    }

    @Override
    @Test(expected = InvalidConfigurationException.class)
    public void testPersistentMemoryDirectoryConfiguration_numaNodeConsistencyViolationThrows() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    persistent-memory:
                      directories:
                        - directory: /mnt/pmem0
                          numa-node: 0
                        - directory: /mnt/pmem1
                """;

        buildConfig(yaml);
    }

    @Override
    @Test
    public void testPersistentMemoryDirectoryConfiguration_simpleAndAdvancedPasses() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    persistent-memory-directory: /mnt/optane
                    persistent-memory:
                      enabled: false
                      directories:
                        - directory: /mnt/pmem0
                        - directory: /mnt/pmem1
                """;

        ClientConfig config = buildConfig(yaml);

        PersistentMemoryConfig pmemConfig = config.getNativeMemoryConfig().getPersistentMemoryConfig();
        assertFalse(pmemConfig.isEnabled());
        assertEquals(MOUNTED, pmemConfig.getMode());

        List<PersistentMemoryDirectoryConfig> directoryConfigs = pmemConfig.getDirectoryConfigs();
        assertEquals(3, directoryConfigs.size());
        PersistentMemoryDirectoryConfig dir0Config = directoryConfigs.get(0);
        PersistentMemoryDirectoryConfig dir1Config = directoryConfigs.get(1);
        PersistentMemoryDirectoryConfig dir2Config = directoryConfigs.get(2);
        assertEquals("/mnt/optane", dir0Config.getDirectory());
        assertFalse(dir0Config.isNumaNodeSet());
        assertEquals("/mnt/pmem0", dir1Config.getDirectory());
        assertFalse(dir1Config.isNumaNodeSet());
        assertEquals("/mnt/pmem1", dir2Config.getDirectory());
        assertFalse(dir2Config.isNumaNodeSet());
    }

    @Override
    @Test
    public void testPersistentMemoryConfiguration_SystemMemoryMode() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    persistent-memory:
                      mode: SYSTEM_MEMORY
                """;

        ClientConfig config = buildConfig(yaml);
        PersistentMemoryConfig pmemConfig = config.getNativeMemoryConfig().getPersistentMemoryConfig();
        assertEquals(SYSTEM_MEMORY, pmemConfig.getMode());
    }

    @Override
    @Test(expected = InvalidConfigurationException.class)
    public void testPersistentMemoryConfiguration_NotExistingModeThrows() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    persistent-memory:
                      mode: NOT_EXISTING_MODE
                """;

        buildConfig(yaml);
    }

    @Test
    @Override
    public void testNativeMemoryConfiguration_isBackwardCompatible() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    capacity:
                      value: 1337
                      unit: GIGABYTES
                """;

        ClientConfig clientConfig = buildConfig(yaml);
        assertThat(clientConfig.getNativeMemoryConfig().getCapacity())
                .isEqualToComparingFieldByField(Capacity.of(1337, GIGABYTES));
    }

    @Override
    @Test(expected = InvalidConfigurationException.class)
    public void testPersistentMemoryDirectoryConfiguration_SystemMemoryModeThrows() {
        String yaml = """
                hazelcast-client:
                  native-memory:
                    persistent-memory:
                      mode: SYSTEM_MEMORY
                      directories:
                        - directory: /mnt/pmem0
                """;

        buildConfig(yaml);
    }

    @Override
    public void testCompactSerialization_serializerRegistration() {
        String yaml = """
                hazelcast-client:
                    serialization:
                        compact-serialization:
                            serializers:
                                - serializer: example.serialization.SerializableEmployeeDTOSerializer
                """;

        SerializationConfig config = buildConfig(yaml).getSerializationConfig();
        CompactTestUtil.verifyExplicitSerializerIsUsed(config);
    }

    @Override
    public void testCompactSerialization_classRegistration() {
        String yaml = """
                hazelcast-client:
                    serialization:
                        compact-serialization:
                            classes:
                                - class: example.serialization.ExternalizableEmployeeDTO
                """;

        SerializationConfig config = buildConfig(yaml).getSerializationConfig();
        CompactTestUtil.verifyReflectiveSerializerIsUsed(config);
    }

    @Override
    public void testCompactSerialization_serializerAndClassRegistration() {
        String yaml = """
                hazelcast-client:
                    serialization:
                        compact-serialization:
                            serializers:
                                - serializer: example.serialization.SerializableEmployeeDTOSerializer
                            classes:
                                - class: example.serialization.ExternalizableEmployeeDTO
                """;

        SerializationConfig config = buildConfig(yaml).getSerializationConfig();
        CompactTestUtil.verifyExplicitSerializerIsUsed(config);
        CompactTestUtil.verifyReflectiveSerializerIsUsed(config);
    }

    @Override
    public void testCompactSerialization_duplicateSerializerRegistration() {
        String yaml = """
                hazelcast-client:
                    serialization:
                        compact-serialization:
                            serializers:
                                - serializer: example.serialization.EmployeeDTOSerializer
                                - serializer: example.serialization.EmployeeDTOSerializer
                """;

        SerializationConfig config = buildConfig(yaml).getSerializationConfig();
        assertThatThrownBy(() -> CompactTestUtil.verifySerializationServiceBuilds(config))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("Duplicate");
    }

    @Override
    public void testCompactSerialization_duplicateClassRegistration() {
        String yaml = """
                hazelcast-client:
                    serialization:
                        compact-serialization:
                            classes:
                                - class: example.serialization.ExternalizableEmployeeDTO
                                - class: example.serialization.ExternalizableEmployeeDTO
                """;

        SerializationConfig config = buildConfig(yaml).getSerializationConfig();
        assertThatThrownBy(() -> CompactTestUtil.verifySerializationServiceBuilds(config))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("Duplicate");
    }

    @Override
    public void testCompactSerialization_registrationsWithDuplicateClasses() {
        String yaml = """
                hazelcast-client:
                    serialization:
                        compact-serialization:
                            serializers:
                                - serializer: example.serialization.EmployeeDTOSerializer
                                - serializer: example.serialization.SameClassEmployeeDTOSerializer
                """;

        SerializationConfig config = buildConfig(yaml).getSerializationConfig();
        assertThatThrownBy(() -> CompactTestUtil.verifySerializationServiceBuilds(config))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("Duplicate")
                .hasMessageContaining("class");
    }

    @Override
    public void testCompactSerialization_registrationsWithDuplicateTypeNames() {
        String yaml = """
                hazelcast-client:
                    serialization:
                        compact-serialization:
                            serializers:
                                - serializer: example.serialization.EmployeeDTOSerializer
                                - serializer: example.serialization.SameTypeNameEmployeeDTOSerializer
                """;

        SerializationConfig config = buildConfig(yaml).getSerializationConfig();
        assertThatThrownBy(() -> CompactTestUtil.verifySerializationServiceBuilds(config))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("Duplicate")
                .hasMessageContaining("type name");
    }

    @Override
    public void testCompactSerialization_withInvalidSerializer() {
        String yaml = """
                hazelcast-client:
                    serialization:
                        compact-serialization:
                            serializers:
                                - serializer: does.not.exist.FooSerializer
                """;

        SerializationConfig config = buildConfig(yaml).getSerializationConfig();
        assertThatThrownBy(() -> CompactTestUtil.verifySerializationServiceBuilds(config))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("Cannot create an instance");
    }

    @Override
    public void testCompactSerialization_withInvalidCompactSerializableClass() {
        String yaml = """
                hazelcast-client:
                  serialization:
                    compact-serialization:
                      classes:
                        - class: does.not.exist.Foo
                """;

        SerializationConfig config = buildConfig(yaml).getSerializationConfig();
        assertThatThrownBy(() -> CompactTestUtil.verifySerializationServiceBuilds(config))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("Cannot load");
    }

    @Test
    public void testEmptyYaml() {
        String yaml = "hazelcast-client:\n";
        ClientConfig emptyConfig = buildConfig(yaml);
        ClientConfig defaultConfig = new ClientConfig();

        // Object equality was failing because of the classloaders of
        // these configs are different, ignoring this exception.
        emptyConfig.setClassLoader(defaultConfig.getClassLoader());

        assertEquals(defaultConfig, emptyConfig);
    }

    @Override
    public void testDefaultRoutingStrategyIsPicked_whenNoRoutingStrategyIsSetToMultiMemberRoutingConfig() {
        String yaml = """
                hazelcast-client:
                  network:
                    cluster-routing:
                      mode: MULTI_MEMBER
                """;

        ClientConfig clientConfig = buildConfig(yaml);

        assertEquals(RoutingMode.MULTI_MEMBER, clientConfig.getNetworkConfig().getClusterRoutingConfig().getRoutingMode());
        assertEquals(ClusterRoutingConfig.DEFAULT_ROUTING_STRATEGY,
                clientConfig.getNetworkConfig().getClusterRoutingConfig().getRoutingStrategy());
    }

    public static ClientConfig buildConfig(String yaml) {
        ByteArrayInputStream bis = new ByteArrayInputStream(yaml.getBytes());
        YamlClientConfigBuilder configBuilder = new YamlClientConfigBuilder(bis);
        return configBuilder.build();
    }

    static ClientConfig buildConfig(String xml, Properties properties) {
        ByteArrayInputStream bis = new ByteArrayInputStream(xml.getBytes());
        YamlClientConfigBuilder configBuilder = new YamlClientConfigBuilder(bis);
        configBuilder.setProperties(properties);
        return configBuilder.build();
    }

    static ClientConfig buildConfig(String yaml, String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return buildConfig(yaml, properties);
    }


}
