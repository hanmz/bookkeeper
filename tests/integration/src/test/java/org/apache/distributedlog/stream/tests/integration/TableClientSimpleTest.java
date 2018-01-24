/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.distributedlog.stream.tests.integration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.bookkeeper.common.concurrent.FutureUtils.result;
import static org.apache.distributedlog.stream.protocol.ProtocolConstants.DEFAULT_STREAM_CONF;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.common.util.OrderedScheduler;
import org.apache.distributedlog.api.StorageClient;
import org.apache.distributedlog.api.kv.PTable;
import org.apache.distributedlog.api.kv.exceptions.KvApiException;
import org.apache.distributedlog.api.kv.result.Code;
import org.apache.distributedlog.api.kv.result.KeyValue;
import org.apache.distributedlog.clients.StorageClientBuilder;
import org.apache.distributedlog.clients.admin.StorageAdminClient;
import org.apache.distributedlog.clients.config.StorageClientSettings;
import org.apache.distributedlog.stream.proto.NamespaceConfiguration;
import org.apache.distributedlog.stream.proto.NamespaceProperties;
import org.apache.distributedlog.stream.proto.StreamConfiguration;
import org.apache.distributedlog.stream.proto.StreamProperties;
import org.apache.distributedlog.stream.proto.common.Endpoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Integration test for table service.
 */
@Slf4j
public class TableClientSimpleTest extends StorageServerTestBase {

    @Rule
    public final TestName testName = new TestName();

    private final String namespace = "test_namespace";
    private OrderedScheduler scheduler;
    private StorageAdminClient adminClient;
    private StorageClient storageClient;
    private URI defaultBackendUri;

    @Override
    protected void doSetup() throws Exception {
        defaultBackendUri = URI.create("distributedlog://" + cluster.getZkServers() + "/stream/storage");
        scheduler = OrderedScheduler.newSchedulerBuilder()
            .name("table-client-test")
            .numThreads(1)
            .build();
        StorageClientSettings settings = StorageClientSettings.newBuilder()
            .addEndpoints(cluster.getRpcEndpoints().toArray(new Endpoint[cluster.getRpcEndpoints().size()]))
            .usePlaintext(true)
            .build();
        String namespace = "test_namespace";
        adminClient = StorageClientBuilder.newBuilder()
            .withSettings(settings)
            .buildAdmin();
        storageClient = StorageClientBuilder.newBuilder()
            .withSettings(settings)
            .withNamespace(namespace)
            .build();
    }

    @Override
    protected void doTeardown() throws Exception {
        if (null != adminClient) {
            adminClient.close();
        }
        if (null != storageClient) {
            storageClient.close();
        }
        if (null != scheduler) {
            scheduler.shutdown();
        }
    }

    private static ByteBuf getLKey(int i) {
        return Unpooled.wrappedBuffer(String.format("test-lkey-%06d", i).getBytes(UTF_8));
    }

    private static ByteBuf getValue(int i) {
        return Unpooled.wrappedBuffer(String.format("test-val-%06d", i).getBytes(UTF_8));
    }

    @Test
    public void testTableSimpleAPI() throws Exception {
        // Create a namespace
        NamespaceConfiguration nsConf = NamespaceConfiguration.newBuilder()
            .setDefaultStreamConf(DEFAULT_STREAM_CONF)
            .build();
        NamespaceProperties nsProps = result(adminClient.createNamespace(namespace, nsConf));
        assertEquals(namespace, nsProps.getNamespaceName());
        assertEquals(nsConf.getDefaultStreamConf(), nsProps.getDefaultStreamConf());

        // Create a stream
        String streamName = testName.getMethodName() + "_stream";
        StreamConfiguration streamConf = StreamConfiguration.newBuilder(DEFAULT_STREAM_CONF)
            .build();
        StreamProperties streamProps = result(
            adminClient.createStream(namespace, streamName, streamConf));
        assertEquals(streamName, streamProps.getStreamName());
        assertEquals(
            StreamConfiguration.newBuilder(streamConf)
                .setBackendServiceUrl(defaultBackendUri.toString())
                .build(),
            streamProps.getStreamConf());

        // Open the table
        PTable<ByteBuf, ByteBuf> table = result(storageClient.openPTable(streamName));
        byte[] rKey = "routing-key".getBytes(UTF_8);
        byte[] lKey = "testing-key".getBytes(UTF_8);
        byte[] value1 = "testing-value-1".getBytes(UTF_8);
        byte[] value2 = "testing-value-2".getBytes(UTF_8);

        // put first key
        ByteBuf rKeyBuf = Unpooled.wrappedBuffer(rKey);
        ByteBuf lKeyBuf = Unpooled.wrappedBuffer(lKey);
        ByteBuf valBuf1 = Unpooled.wrappedBuffer(value1);
        ByteBuf valBuf2 = Unpooled.wrappedBuffer(value2);

        // normal put
        assertNull(result(
            table.put(rKeyBuf, lKeyBuf, valBuf1)));

        // putIfAbsent failure
        assertArrayEquals(
            value1,
            ByteBufUtil.getBytes(result(table.putIfAbsent(rKeyBuf, lKeyBuf, valBuf2))));

        // delete failure
        assertFalse(
            result(table.delete(rKeyBuf, lKeyBuf, valBuf2)));

        // delete success
        assertTrue(
            result(table.delete(rKeyBuf, lKeyBuf, valBuf1)));

        // get
        assertNull(
            result(table.get(rKeyBuf, lKeyBuf)));

        // putIfAbsent success
        assertNull(
            result(table.putIfAbsent(rKeyBuf, lKeyBuf, valBuf2)));

        // get returns val2
        assertArrayEquals(
            value2,
            ByteBufUtil.getBytes(result(table.get(rKeyBuf, lKeyBuf))));

        // vPut failure
        try {
            result(
                table.vPut(rKeyBuf, lKeyBuf, valBuf1, 9999L));
            fail("Should fail vPut if the version doesn't match");
        } catch (KvApiException e) {
            assertEquals(Code.BAD_REVISION, e.getCode());
        }

        // vPut success
        assertEquals(
            1L,
            result(
                table.vPut(rKeyBuf, lKeyBuf, valBuf1, 0L)).longValue());

        // vDelete failure
        try {
            result(
                table.vDelete(rKeyBuf, lKeyBuf, 9999L));
            fail("Should fail vDelete if the version doesn't match");
        } catch (KvApiException e) {
            assertEquals(Code.BAD_REVISION, e.getCode());
        }

        // vDelete success
        try (KeyValue<ByteBuf, ByteBuf> prevKv = result(
            table.vDelete(rKeyBuf, lKeyBuf, 1L))) {
            assertNotNull(prevKv);
            assertEquals(1L, prevKv.version());
            assertArrayEquals(
                value1,
                ByteBufUtil.getBytes(prevKv.value()));
        }

        // write a range of key
        int numKvs = 100;
        rKeyBuf = Unpooled.wrappedBuffer("test-key".getBytes(UTF_8));
        for (int i = 0; i < numKvs; i++) {
            lKeyBuf = getLKey(i);
            valBuf1 = getValue(i);
            result(table.put(rKeyBuf, lKeyBuf, valBuf1));
        }

        // get ranges
        ByteBuf lStartKey = getLKey(20);
        ByteBuf lEndKey = getLKey(50);
        List<KeyValue<ByteBuf, ByteBuf>> kvs = result(
            table.range(rKeyBuf, lStartKey, lEndKey));
        assertEquals(31, kvs.size());
        int i = 20;
        for (KeyValue<ByteBuf, ByteBuf> kvPair : kvs) {
            assertEquals(getLKey(i), kvPair.key());
            assertEquals(getValue(i), kvPair.value());
            ++i;
            kvPair.close();
        }
        assertEquals(51, i);

        // delete range
        kvs = result(
            table.deleteRange(rKeyBuf, lStartKey, lEndKey));
        assertEquals(31, kvs.size());
        i = 20;
        for (KeyValue<ByteBuf, ByteBuf> kvPair : kvs) {
            assertEquals(getLKey(i), kvPair.key());
            assertEquals(getValue(i), kvPair.value());
            ++i;
            kvPair.close();
        }
        assertEquals(51, i);

        // get ranges again
        kvs = result(table.range(rKeyBuf, lStartKey, lEndKey));
        assertTrue(kvs.isEmpty());

        byte[] lIncrKey = "test-incr-lkey".getBytes(UTF_8);
        ByteBuf lIncrKeyBuf = Unpooled.wrappedBuffer(lIncrKey);

        for (int j = 0; j < 5; j++) {
            result(table.increment(rKeyBuf, lIncrKeyBuf, 100L));
            long number = result(table.getNumber(rKeyBuf, lIncrKeyBuf));
            assertEquals(100L * (j + 1), number);
        }
    }
}
