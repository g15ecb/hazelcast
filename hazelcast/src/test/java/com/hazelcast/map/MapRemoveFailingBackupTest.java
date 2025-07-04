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

package com.hazelcast.map;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.util.ThreadUtil;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.operation.BaseRemoveOperation;
import com.hazelcast.map.impl.operation.KeyBasedMapOperation;
import com.hazelcast.map.impl.operation.steps.RemoveOpSteps;
import com.hazelcast.map.impl.operation.steps.engine.State;
import com.hazelcast.map.impl.operation.steps.engine.Step;
import com.hazelcast.map.impl.proxy.MapProxyImpl;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.spi.impl.InternalCompletableFuture;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.spi.impl.operationservice.BackupOperation;
import com.hazelcast.spi.impl.operationservice.Operation;
import com.hazelcast.spi.impl.operationservice.OperationService;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.test.HazelcastParametrizedRunner;
import com.hazelcast.test.HazelcastSerialParametersRunnerFactory;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.annotation.Nonnull;
import java.util.Collection;

import static com.hazelcast.map.impl.MapService.SERVICE_NAME;
import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@RunWith(HazelcastParametrizedRunner.class)
@Parameterized.UseParametersRunnerFactory(HazelcastSerialParametersRunnerFactory.class)
@Category({QuickTest.class, ParallelJVMTest.class})
public class MapRemoveFailingBackupTest extends HazelcastTestSupport {

    @Parameterized.Parameters(name = "offload: {0}")
    public static Collection<Object[]> parameters() {
        return asList(new Object[][]{
                {true},
                {false}
        });
    }

    @Parameterized.Parameter
    public boolean offload;

    @Test
    public void testMapRemoveFailingBackupShouldNotLeadToStaleDataWhenReadBackupIsEnabled() {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        final String mapName = randomMapName();

        HazelcastInstance hz1 = factory.newHazelcastInstance(getConfig());
        HazelcastInstance hz2 = factory.newHazelcastInstance(getConfig());

        final NodeEngine nodeEngine = getNodeEngineImpl(hz1);
        final IMap<Object, Object> map1 = hz1.getMap(mapName);
        final IMap<Object, Object> map2 = hz2.getMap(mapName);
        MapProxyImpl<Object, Object> mock1 = (MapProxyImpl<Object, Object>) spy(map1);
        when(mock1.remove(anyString())).then(invocation -> {
            Object object = invocation.getArguments()[0];
            final Data key1 = nodeEngine.toData(object);
            RemoveOperation operation = new RemoveOperation(mapName, key1);
            int partitionId = nodeEngine.getPartitionService().getPartitionId(key1);
            operation.setThreadId(ThreadUtil.getThreadId());
            OperationService operationService = nodeEngine.getOperationService();
            InternalCompletableFuture<Data> f
                    = operationService.createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .setResultDeserialized(false).invoke();
            Data result = f.get();
            return nodeEngine.toObject(result);
        });

        final String key = "2";
        final String value = "value2";

        mock1.put(key, value);
        mock1.remove(key);
        assertTrueEventually(() -> {
            assertNull(map1.get(key));
            assertNull(map2.get(key));
        }, 30);
    }

    @Nonnull
    protected Config getConfig() {
        Config config = super.getConfig();
        config.getSerializationConfig().addDataSerializableFactory(100, new Factory());
        config.setProperty(ClusterProperty.PARTITION_BACKUP_SYNC_INTERVAL.getName(), "5");
        config.setProperty(MapServiceContext.PROP_FORCE_OFFLOAD_ALL_OPERATIONS, String.valueOf(offload));
        config.getMapConfig("default").setReadBackupData(true);
        return config;
    }

    private static class Factory implements DataSerializableFactory {
        @Override
        public IdentifiedDataSerializable create(int typeId) {
            if (typeId == 100) {
                return new RemoveOperation();
            } else if (typeId == 101) {
                return new ExceptionThrowingRemoveBackupOperation();
            }
            throw new IllegalArgumentException("Unsupported type " + typeId);
        }
    }

    private static class RemoveOperation extends BaseRemoveOperation {

        boolean successful;

        RemoveOperation(String name, Data dataKey) {
            super(name, dataKey);
        }

        RemoveOperation() {
        }

        @Override
        protected void runInternal() {
            dataOldValue = mapService.getMapServiceContext()
                    .toData(recordStore.remove(dataKey, getCallerProvenance()));
            successful = dataOldValue != null;
        }

        @Override
        public void afterRunInternal() {
            if (successful) {
                super.afterRunInternal();
            }
        }

        @Override
        public void applyState(State state) {
            super.applyState(state);

            successful = dataOldValue != null;
        }

        @Override
        public Step getStartingStep() {
            return RemoveOpSteps.READ;
        }

        @Override
        public Operation getBackupOperation() {
            return new ExceptionThrowingRemoveBackupOperation(name, dataKey);
        }

        @Override
        public boolean shouldBackup() {
            return successful;
        }

        @Override
        public int getFactoryId() {
            return 100;
        }

        @Override
        public int getClassId() {
            return 100;
        }
    }

    private static class ExceptionThrowingRemoveBackupOperation
            extends KeyBasedMapOperation implements BackupOperation {

        private ExceptionThrowingRemoveBackupOperation() {
        }

        ExceptionThrowingRemoveBackupOperation(String name, Data dataKey) {
            super(name, dataKey);
        }

        @Override
        protected void runInternal() {
            throw new UnsupportedOperationException("Don't panic this is what we want!");
        }

        @Override
        public Object getResponse() {
            return Boolean.TRUE;
        }

        @Override
        public int getFactoryId() {
            return 100;
        }

        @Override
        public int getClassId() {
            return 101;
        }
    }
}
