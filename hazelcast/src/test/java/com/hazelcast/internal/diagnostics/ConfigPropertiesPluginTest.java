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

package com.hazelcast.internal.diagnostics;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import static com.hazelcast.internal.diagnostics.DiagnosticsPlugin.RUN_ONCE_PERIOD_MS;
import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static org.junit.Assert.assertEquals;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class ConfigPropertiesPluginTest extends AbstractDiagnosticsPluginTest {

    private ConfigPropertiesPlugin plugin;

    @Before
    public void setup() {
        Config config = new Config()
                .setProperty("property1", "value1");
        HazelcastInstance hz = createHazelcastInstance(config);
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        nodeEngine.getDiagnostics().setConfig(new DiagnosticsConfig().setProperty("property2", "value2"));
        plugin = new ConfigPropertiesPlugin(nodeEngine);
        plugin.onStart();
    }

    @Test
    public void testGetPeriodMillis() {
        assertEquals(RUN_ONCE_PERIOD_MS, plugin.getPeriodMillis());
    }

    @Test
    public void testRun() {
        plugin.run(logWriter);
        assertContains("property1=value1");
        assertContains("property2=value2");
    }
}
