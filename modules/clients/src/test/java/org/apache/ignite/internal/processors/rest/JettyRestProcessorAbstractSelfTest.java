/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.rest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.Serializable;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cache.query.SqlQuery;
import org.apache.ignite.cache.query.annotations.QuerySqlField;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.FileSystemConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.igfs.IgfsGroupDataBlocksKeyMapper;
import org.apache.ignite.igfs.IgfsIpcEndpointConfiguration;
import org.apache.ignite.internal.processors.cache.IgniteCacheProxy;
import org.apache.ignite.internal.processors.cache.query.GridCacheSqlIndexMetadata;
import org.apache.ignite.internal.processors.cache.query.GridCacheSqlMetadata;
import org.apache.ignite.internal.processors.rest.handlers.GridRestCommandHandler;
import org.apache.ignite.internal.util.lang.GridTuple3;
import org.apache.ignite.internal.util.typedef.C1;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.P1;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.internal.visor.cache.VisorCacheClearTask;
import org.apache.ignite.internal.visor.cache.VisorCacheConfigurationCollectorTask;
import org.apache.ignite.internal.visor.cache.VisorCacheLoadTask;
import org.apache.ignite.internal.visor.cache.VisorCacheMetadataTask;
import org.apache.ignite.internal.visor.cache.VisorCacheMetricsCollectorTask;
import org.apache.ignite.internal.visor.cache.VisorCacheNodesTask;
import org.apache.ignite.internal.visor.cache.VisorCacheRebalanceTask;
import org.apache.ignite.internal.visor.cache.VisorCacheResetMetricsTask;
import org.apache.ignite.internal.visor.cache.VisorCacheStartTask;
import org.apache.ignite.internal.visor.cache.VisorCacheStopTask;
import org.apache.ignite.internal.visor.cache.VisorCacheSwapBackupsTask;
import org.apache.ignite.internal.visor.compute.VisorComputeCancelSessionsTask;
import org.apache.ignite.internal.visor.compute.VisorComputeResetMetricsTask;
import org.apache.ignite.internal.visor.compute.VisorComputeToggleMonitoringTask;
import org.apache.ignite.internal.visor.compute.VisorGatewayTask;
import org.apache.ignite.internal.visor.debug.VisorThreadDumpTask;
import org.apache.ignite.internal.visor.file.VisorFileBlockTask;
import org.apache.ignite.internal.visor.file.VisorLatestTextFilesTask;
import org.apache.ignite.internal.visor.igfs.VisorIgfsFormatTask;
import org.apache.ignite.internal.visor.igfs.VisorIgfsProfilerClearTask;
import org.apache.ignite.internal.visor.igfs.VisorIgfsProfilerTask;
import org.apache.ignite.internal.visor.igfs.VisorIgfsResetMetricsTask;
import org.apache.ignite.internal.visor.igfs.VisorIgfsSamplingStateTask;
import org.apache.ignite.internal.visor.log.VisorLogSearchTask;
import org.apache.ignite.internal.visor.misc.VisorAckTask;
import org.apache.ignite.internal.visor.misc.VisorLatestVersionTask;
import org.apache.ignite.internal.visor.misc.VisorResolveHostNameTask;
import org.apache.ignite.internal.visor.node.VisorNodeConfigurationCollectorTask;
import org.apache.ignite.internal.visor.node.VisorNodeDataCollectorTask;
import org.apache.ignite.internal.visor.node.VisorNodeDataCollectorTaskArg;
import org.apache.ignite.internal.visor.node.VisorNodeEventsCollectorTask;
import org.apache.ignite.internal.visor.node.VisorNodeGcTask;
import org.apache.ignite.internal.visor.node.VisorNodePingTask;
import org.apache.ignite.internal.visor.node.VisorNodeSuppressedErrorsTask;
import org.apache.ignite.internal.visor.query.VisorQueryArg;
import org.apache.ignite.internal.visor.query.VisorQueryCleanupTask;
import org.apache.ignite.internal.visor.query.VisorQueryNextPageTask;
import org.apache.ignite.internal.visor.query.VisorQueryTask;
import org.apache.ignite.lang.IgniteBiPredicate;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.testframework.GridTestUtils;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_JETTY_PORT;

/**
 * Tests for Jetty REST protocol.
 */
@SuppressWarnings("unchecked")
public abstract class JettyRestProcessorAbstractSelfTest extends AbstractRestProcessorSelfTest {
    /** Grid count. */
    private static final int GRID_CNT = 3;

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        System.setProperty(IGNITE_JETTY_PORT, Integer.toString(restPort()));

        super.beforeTestsStarted();

        initCache();
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        super.afterTestsStopped();

        System.clearProperty(IGNITE_JETTY_PORT);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        grid(0).cache(null).removeAll();
    }

    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return GRID_CNT;
    }

    /**
     * @return Port to use for rest. Needs to be changed over time
     *      because Jetty has some delay before port unbind.
     */
    protected abstract int restPort();

    /**
     * @return Security enabled flag. Should be the same with {@code ctx.security().enabled()}.
     */
    protected boolean securityEnabled() {
        return false;
    }

    /**
     * @param params Command parameters.
     * @return Returned content.
     * @throws Exception If failed.
     */
    protected String content(Map<String, String> params) throws Exception {
        String addr = "http://" + LOC_HOST + ":" + restPort() + "/ignite?";

        for (Map.Entry<String, String> e : params.entrySet())
            addr += e.getKey() + '=' + e.getValue() + '&';

        URL url = new URL(addr);

        URLConnection conn = url.openConnection();

        String signature = signature();

        if (signature != null)
            conn.setRequestProperty("X-Signature", signature);

        InputStream in = conn.getInputStream();

        LineNumberReader rdr = new LineNumberReader(new InputStreamReader(in));

        StringBuilder buf = new StringBuilder(256);

        for (String line = rdr.readLine(); line != null; line = rdr.readLine())
            buf.append(line);

        return buf.toString();
    }

    /**
     * @param json JSON response.
     * @param ptrn Pattern to match.
     */
    @SuppressWarnings("TypeMayBeWeakened")
    protected void jsonEquals(String json, String ptrn) {
        assertTrue("JSON mismatch [json=" + json + ", ptrn=" + ptrn + ']', Pattern.matches(ptrn, json));
    }

    /**
     * @param res Response.
     * @param success Success flag.
     * @return Regex pattern for JSON.
     */
    private String cachePattern(String res, boolean success) {
        return "\\{\\\"affinityNodeId\\\":\\\"\\w{8}-\\w{4}-\\w{4}-\\w{4}-\\w{12}\\\"\\," +
            "\\\"error\\\":\\\"\\\"\\," +
            "\\\"response\\\":\\\"" + res + "\\\"\\," +
            "\\\"sessionToken\\\":\\\"" + (securityEnabled() && success ? ".+" : "") + "\\\"," +
            "\\\"successStatus\\\":" + (success ? 0 : 1) + "\\}";
    }

    /**
     * @param err Error.
     * @return Regex pattern for JSON.
     */
    private String errorPattern(String err) {
        return "\\{" +
            "\\\"error\\\":\\\"" + err + "\\\"\\," +
            "\\\"response\\\":null\\," +
            "\\\"sessionToken\\\":\\\"\\\"," +
            "\\\"successStatus\\\":" + 1 + "\\}";
    }

    /**
     * @param res Response.
     * @param success Success flag.
     * @return Regex pattern for JSON.
     */
    private String integerPattern(int res, boolean success) {
        return "\\{\\\"error\\\":\\\"\\\"\\," +
            "\\\"response\\\":" + res + "\\," +
            "\\\"sessionToken\\\":\\\"" + (securityEnabled() && success ? ".+" : "") + "\\\"," +
            "\\\"successStatus\\\":" + (success ? 0 : 1) + "\\}";
    }

    /**
     * @param res Response.
     * @param success Success flag.
     * @return Regex pattern for JSON.
     */
    private String cacheBulkPattern(String res, boolean success) {
        return "\\{\\\"affinityNodeId\\\":\\\"\\\"\\," +
            "\\\"error\\\":\\\"\\\"\\," +
            "\\\"response\\\":" + res + "\\," +
            "\\\"sessionToken\\\":\\\"" + (securityEnabled() && success ? ".+" : "") + "\\\"," +
            "\\\"successStatus\\\":" + (success ? 0 : 1) + "\\}";
    }

    /**
     * @param res Response.
     * @param success Success flag.
     * @return Regex pattern for JSON.
     */
    private String cacheBulkPattern(int res, boolean success) {
        return "\\{\\\"affinityNodeId\\\":\\\"\\\"\\," +
            "\\\"error\\\":\\\"\\\"\\," +
            "\\\"response\\\":" + res + "\\," +
            "\\\"sessionToken\\\":\\\"" + (securityEnabled() && success ? ".+" : "") + "\\\"," +
            "\\\"successStatus\\\":" + (success ? 0 : 1) + "\\}";
    }

    /**
     * @param res Response.
     * @param success Success flag.
     * @return Regex pattern for JSON.
     */
    private String cachePattern(boolean res, boolean success) {
        return "\\{\\\"affinityNodeId\\\":\\\"\\w{8}-\\w{4}-\\w{4}-\\w{4}-\\w{12}\\\"\\," +
            "\\\"error\\\":\\\"\\\"\\," +
            "\\\"response\\\":" + res + "\\," +
            "\\\"sessionToken\\\":\\\"" + (securityEnabled() && success ? ".+" : "") + "\\\"," +
            "\\\"successStatus\\\":" + (success ? 0 : 1) + "\\}";
    }

    /**
     * @param res Response.
     * @param success Success flag.
     * @return Regex pattern for JSON.
     */
    private String cacheBulkPattern(boolean res, boolean success) {
        return "\\{\\\"affinityNodeId\\\":\\\"\\\"\\," +
            "\\\"error\\\":\\\"\\\"\\," +
            "\\\"response\\\":" + res + "\\," +
            "\\\"sessionToken\\\":\\\"" + (securityEnabled() && success ? ".+" : "") + "\\\"," +
            "\\\"successStatus\\\":" + (success ? 0 : 1) + "\\}";
    }

    /**
     * @param res Response.
     * @param success Success flag.
     * @return Regex pattern for JSON.
     */
    private String cacheMetricsPattern(String res, boolean success) {
        return "\\{\\\"affinityNodeId\\\":\\\"(\\w{8}-\\w{4}-\\w{4}-\\w{4}-\\w{12})?\\\"\\," +
            "\\\"error\\\":\\\"\\\"\\," +
            "\\\"response\\\":" + res + "\\," +
            "\\\"sessionToken\\\":\\\"" + (securityEnabled() && success ? ".+" : "") + "\\\"," +
            "\\\"successStatus\\\":" + (success ? 0 : 1) + "\\}";
    }

    /**
     * @param res Response.
     * @param success Success flag.
     * @return Regex pattern for JSON.
     */
    protected String pattern(String res, boolean success) {
        return "\\{\\\"error\\\":\\\"" + (!success ? ".+" : "") + "\\\"\\," +
            "\\\"response\\\":" + res + "\\," +
            "\\\"sessionToken\\\":\\\"" + (securityEnabled() && success ? ".+" : "") + "\\\"," +
            "\\\"successStatus\\\":" + (success ? 0 : 1) + "\\}";
    }

    /**
     * @param res Response.
     * @param success Success flag.
     * @return Regex pattern for JSON.
     */
    private String stringPattern(String res, boolean success) {
        return "\\{\\\"error\\\":\\\"" + (!success ? ".+" : "") + "\\\"\\," +
            "\\\"response\\\":\\\"" + res + "\\\"\\," +
            "\\\"sessionToken\\\":\\\"" + (securityEnabled() && success ? ".+" : "") + "\\\"," +
            "\\\"successStatus\\\":" + (success ? 0 : 1) + "\\}";
    }

    /**
     * @throws Exception If failed.
     */
    public void testGet() throws Exception {
        jcache().put("getKey", "getVal");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_GET.key(), "key", "getKey"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Get command result: " + ret);

        jsonEquals(ret, cachePattern("getVal", true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testCacheSize() throws Exception {
        jcache().removeAll();

        jcache().put("getKey", "getVal");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_SIZE.key()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Size command result: " + ret);

        jsonEquals(ret, cacheBulkPattern(1, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testIgniteName() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.NAME.key()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Name command result: " + ret);

        jsonEquals(ret, stringPattern(getTestGridName(0), true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testGetOrCreateCache() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.GET_OR_CREATE_CACHE.key(), "cacheName", "testCache"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Name command result: " + ret);

        grid(0).cache("testCache").put("1", "1");

        ret = content(F.asMap("cmd", GridRestCommand.DESTROY_CACHE.key(), "cacheName", "testCache"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        assertNull(grid(0).cache("testCache"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testGetAll() throws Exception {
        jcache().put("getKey1", "getVal1");
        jcache().put("getKey2", "getVal2");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_GET_ALL.key(), "k1", "getKey1", "k2", "getKey2"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Get all command result: " + ret);

        jsonEquals(ret,
            // getKey[12] is used since the order is not determined.
            cacheBulkPattern("\\{\\\"getKey[12]\\\":\\\"getVal[12]\\\"\\,\\\"getKey[12]\\\":\\\"getVal[12]\\\"\\}",
                true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testIncorrectPut() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_PUT.key(), "key", "key0"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());
        jsonEquals(ret, errorPattern("Failed to find mandatory parameter in request: val"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testContainsKey() throws Exception {
        grid(0).cache(null).put("key0", "val0");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_CONTAINS_KEY.key(), "key", "key0"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testContainesKeys() throws Exception {
        grid(0).cache(null).put("key0", "val0");
        grid(0).cache(null).put("key1", "val1");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_CONTAINS_KEYS.key(),
            "k1", "key0", "k2", "key1"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cacheBulkPattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testGetAndPut() throws Exception {
        grid(0).cache(null).put("key0", "val0");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_GET_AND_PUT.key(), "key", "key0", "val", "val1"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern("val0", true));

        assertEquals("val1", grid(0).cache(null).get("key0"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testGetAndPutIfAbsent() throws Exception {
        grid(0).cache(null).put("key0", "val0");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_GET_AND_PUT_IF_ABSENT.key(),
            "key", "key0", "val", "val1"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern("val0", true));

        assertEquals("val0", grid(0).cache(null).get("key0"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutIfAbsent2() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_PUT_IF_ABSENT.key(),
            "key", "key0", "val", "val1"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));

        assertEquals("val1", grid(0).cache(null).get("key0"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testRemoveValue() throws Exception {
        grid(0).cache(null).put("key0", "val0");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_REMOVE_VALUE.key(),
            "key", "key0", "val", "val1"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(false, true));

        assertEquals("val0", grid(0).cache(null).get("key0"));

        ret = content(F.asMap("cmd", GridRestCommand.CACHE_REMOVE_VALUE.key(),
            "key", "key0", "val", "val0"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));

        assertNull(grid(0).cache(null).get("key0"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testGetAndRemove() throws Exception {
        grid(0).cache(null).put("key0", "val0");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_GET_AND_REMOVE.key(),
            "key", "key0"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern("val0", true));

        assertNull(grid(0).cache(null).get("key0"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testReplaceValue() throws Exception {
        grid(0).cache(null).put("key0", "val0");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_REPLACE_VALUE.key(),
            "key", "key0", "val", "val1", "val2", "val2"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(false, true));

        assertEquals("val0", grid(0).cache(null).get("key0"));

        ret = content(F.asMap("cmd", GridRestCommand.CACHE_REPLACE_VALUE.key(),
            "key", "key0", "val", "val1", "val2", "val0"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));

        assertEquals("val1", grid(0).cache(null).get("key0"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testGetAndReplace() throws Exception {
        grid(0).cache(null).put("key0", "val0");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_GET_AND_REPLACE.key(),
            "key", "key0", "val", "val1"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern("val0", true));

        assertEquals("val1", grid(0).cache(null).get("key0"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testPut() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_PUT.key(),
            "key", "putKey", "val", "putVal"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Put command result: " + ret);

        assertEquals("putVal", jcache().localPeek("putKey", CachePeekMode.ONHEAP));

        jsonEquals(ret, cachePattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutWithExpiration() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_PUT.key(),
            "key", "putKey", "val", "putVal", "exp", "2000"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));

        assertEquals("putVal", jcache().get("putKey"));

        Thread.sleep(2100);

        assertNull(jcache().get("putKey"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testAdd() throws Exception {
        jcache().put("addKey1", "addVal1");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_ADD.key(),
            "key", "addKey2", "val", "addVal2"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));

        assertEquals("addVal1", jcache().localPeek("addKey1", CachePeekMode.ONHEAP));
        assertEquals("addVal2", jcache().localPeek("addKey2", CachePeekMode.ONHEAP));
    }

    /**
     * @throws Exception If failed.
     */
    public void testAddWithExpiration() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_ADD.key(),
            "key", "addKey", "val", "addVal", "exp", "2000"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));

        assertEquals("addVal", jcache().get("addKey"));

        Thread.sleep(2100);

        assertNull(jcache().get("addKey"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAll() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_PUT_ALL.key(),
            "k1", "putKey1", "k2", "putKey2",
            "v1", "putVal1", "v2", "putVal2"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Put all command result: " + ret);

        assertEquals("putVal1", jcache().localPeek("putKey1", CachePeekMode.ONHEAP));
        assertEquals("putVal2", jcache().localPeek("putKey2", CachePeekMode.ONHEAP));

        jsonEquals(ret, cacheBulkPattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testRemove() throws Exception {
        jcache().put("rmvKey", "rmvVal");

        assertEquals("rmvVal", jcache().localPeek("rmvKey", CachePeekMode.ONHEAP));

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_REMOVE.key(),
            "key", "rmvKey"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Remove command result: " + ret);

        assertNull(jcache().localPeek("rmvKey", CachePeekMode.ONHEAP));

        jsonEquals(ret, cachePattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testRemoveAll() throws Exception {
        jcache().put("rmvKey1", "rmvVal1");
        jcache().put("rmvKey2", "rmvVal2");
        jcache().put("rmvKey3", "rmvVal3");
        jcache().put("rmvKey4", "rmvVal4");

        assertEquals("rmvVal1", jcache().localPeek("rmvKey1", CachePeekMode.ONHEAP));
        assertEquals("rmvVal2", jcache().localPeek("rmvKey2", CachePeekMode.ONHEAP));
        assertEquals("rmvVal3", jcache().localPeek("rmvKey3", CachePeekMode.ONHEAP));
        assertEquals("rmvVal4", jcache().localPeek("rmvKey4", CachePeekMode.ONHEAP));

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_REMOVE_ALL.key(),
            "k1", "rmvKey1", "k2", "rmvKey2"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Remove all command result: " + ret);

        assertNull(jcache().localPeek("rmvKey1", CachePeekMode.ONHEAP));
        assertNull(jcache().localPeek("rmvKey2", CachePeekMode.ONHEAP));
        assertEquals("rmvVal3", jcache().localPeek("rmvKey3", CachePeekMode.ONHEAP));
        assertEquals("rmvVal4", jcache().localPeek("rmvKey4", CachePeekMode.ONHEAP));

        jsonEquals(ret, cacheBulkPattern(true, true));

        ret = content(F.asMap("cmd", GridRestCommand.CACHE_REMOVE_ALL.key()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Remove all command result: " + ret);

        assertNull(jcache().localPeek("rmvKey1", CachePeekMode.ONHEAP));
        assertNull(jcache().localPeek("rmvKey2", CachePeekMode.ONHEAP));
        assertNull(jcache().localPeek("rmvKey3", CachePeekMode.ONHEAP));
        assertNull(jcache().localPeek("rmvKey4", CachePeekMode.ONHEAP));
        assertTrue(jcache().localSize() == 0);

        jsonEquals(ret, cacheBulkPattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testCas() throws Exception {
        jcache().put("casKey", "casOldVal");

        assertEquals("casOldVal", jcache().localPeek("casKey", CachePeekMode.ONHEAP));

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_CAS.key(),
            "key", "casKey", "val2", "casOldVal", "val1", "casNewVal"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("CAS command result: " + ret);

        assertEquals("casNewVal", jcache().localPeek("casKey", CachePeekMode.ONHEAP));

        jsonEquals(ret, cachePattern(true, true));

        jcache().remove("casKey");
    }

    /**
     * @throws Exception If failed.
     */
    public void testReplace() throws Exception {
        jcache().put("repKey", "repOldVal");

        assertEquals("repOldVal", jcache().localPeek("repKey", CachePeekMode.ONHEAP));

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_REPLACE.key(),
            "key", "repKey", "val", "repVal"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Replace command result: " + ret);

        assertEquals("repVal", jcache().localPeek("repKey", CachePeekMode.ONHEAP));

        jsonEquals(ret, cachePattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testReplaceWithExpiration() throws Exception {
        jcache().put("replaceKey", "replaceVal");

        assertEquals("replaceVal", jcache().get("replaceKey"));

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_REPLACE.key(),
            "key", "replaceKey", "val", "replaceValNew", "exp", "2000"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));

        assertEquals("replaceValNew", jcache().get("replaceKey"));

        // Use larger value to avoid false positives.
        Thread.sleep(2100);

        assertNull(jcache().get("replaceKey"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testAppend() throws Exception {
        jcache().put("appendKey", "appendVal");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_APPEND.key(),
            "key", "appendKey", "val", "_suffix"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));

        assertEquals("appendVal_suffix", jcache().get("appendKey"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testPrepend() throws Exception {
        jcache().put("prependKey", "prependVal");

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_PREPEND.key(),
            "key", "prependKey", "val", "prefix_"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, cachePattern(true, true));

        assertEquals("prefix_prependVal", jcache().get("prependKey"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testIncrement() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.ATOMIC_INCREMENT.key(),
            "key", "incrKey", "init", "2", "delta", "3"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, integerPattern(5, true));

        assertEquals(5, grid(0).atomicLong("incrKey", 0, true).get());

        ret = content(F.asMap("cmd", GridRestCommand.ATOMIC_INCREMENT.key(), "key", "incrKey", "delta", "10"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, integerPattern(15, true));

        assertEquals(15, grid(0).atomicLong("incrKey", 0, true).get());
    }

    /**
     * @throws Exception If failed.
     */
    public void testDecrement() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.ATOMIC_DECREMENT.key(),
            "key", "decrKey", "init", "15", "delta", "10"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, integerPattern(5, true));

        assertEquals(5, grid(0).atomicLong("decrKey", 0, true).get());

        ret = content(F.asMap("cmd", GridRestCommand.ATOMIC_DECREMENT.key(),
            "key", "decrKey", "delta", "3"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, integerPattern(2, true));

        assertEquals(2, grid(0).atomicLong("decrKey", 0, true).get());
    }

    /**
     * @throws Exception If failed.
     */
    public void testCar() throws Exception {
        jcache().put("casKey", "casOldVal");

        assertEquals("casOldVal", jcache().localPeek("casKey", CachePeekMode.ONHEAP));

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_CAS.key(),
            "key", "casKey", "val2", "casOldVal"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("CAR command result: " + ret);

        assertNull(jcache().localPeek("casKey", CachePeekMode.ONHEAP));

        jsonEquals(ret, cachePattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutIfAbsent() throws Exception {
        assertNull(jcache().localPeek("casKey", CachePeekMode.ONHEAP));

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_CAS.key(),
            "key", "casKey", "val1", "casNewVal"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("PutIfAbsent command result: " + ret);

        assertEquals("casNewVal", jcache().localPeek("casKey", CachePeekMode.ONHEAP));

        jsonEquals(ret, cachePattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testCasRemove() throws Exception {
        jcache().put("casKey", "casVal");

        assertEquals("casVal", jcache().localPeek("casKey", CachePeekMode.ONHEAP));

        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_CAS.key(), "key", "casKey"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("CAS Remove command result: " + ret);

        assertNull(jcache().localPeek("casKey", CachePeekMode.ONHEAP));

        jsonEquals(ret, cachePattern(true, true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testMetrics() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.CACHE_METRICS.key()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Cache metrics command result: " + ret);

        jsonEquals(ret, cacheMetricsPattern("\\{.+\\}", true));
    }

    /**
     * @param metas Metadata for Ignite caches.
     * @throws Exception If failed.
     */
    private void testMetadata(Collection<GridCacheSqlMetadata> metas) throws Exception {
        Map<String, String> params = F.asMap("cmd", GridRestCommand.CACHE_METADATA.key());

        String cacheNameArg = F.first(metas).cacheName();

        if (cacheNameArg != null)
            params.put("cacheName", cacheNameArg);

        String ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Cache metadata result: " + ret);

        jsonEquals(ret, pattern("\\[.+\\]", true));

        Collection<Map> results = (Collection)JSONObject.fromObject(ret).get("response");

        assertEquals(metas.size(), results.size());
        assertEquals(cacheNameArg, F.first(results).get("cacheName"));

        for (Map res : results) {
            final Object cacheName = res.get("cacheName");

            GridCacheSqlMetadata meta = F.find(metas, null, new P1<GridCacheSqlMetadata>() {
                @Override public boolean apply(GridCacheSqlMetadata meta) {
                    return F.eq(meta.cacheName(), cacheName);
                }
            });

            assertNotNull("REST return metadata for unexpected cache: " + cacheName, meta);

            Collection types = (Collection)res.get("types");

            assertNotNull(types);
            assertEqualsCollections(meta.types(), types);

            Map keyClasses = (Map)res.get("keyClasses");

            assertNotNull(keyClasses);
            assertTrue(meta.keyClasses().equals(keyClasses));

            Map valClasses = (Map)res.get("valClasses");

            assertNotNull(valClasses);
            assertTrue(meta.valClasses().equals(valClasses));

            Map fields = (Map)res.get("fields");

            assertNotNull(fields);
            assertTrue(meta.fields().equals(fields));

            Map indexesByType = (Map)res.get("indexes");

            assertNotNull(indexesByType);
            assertEquals(meta.indexes().size(), indexesByType.size());

            for (Map.Entry<String, Collection<GridCacheSqlIndexMetadata>> metaIndexes : meta.indexes().entrySet()) {
                Collection<Map> indexes = (Collection<Map>)indexesByType.get(metaIndexes.getKey());

                assertNotNull(indexes);
                assertEquals(metaIndexes.getValue().size(), indexes.size());

                for (final GridCacheSqlIndexMetadata metaIdx : metaIndexes.getValue()) {
                    Map idx = F.find(indexes, null, new P1<Map>() {
                        @Override public boolean apply(Map map) {
                            return metaIdx.name().equals(map.get("name"));
                        }
                    });

                    assertNotNull(idx);

                    assertEqualsCollections(metaIdx.fields(), (Collection)idx.get("fields"));
                    assertEqualsCollections(metaIdx.descendings(), (Collection)idx.get("descendings"));
                    assertEquals(metaIdx.unique(), idx.get("unique"));
                }
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testMetadataLocal() throws Exception {
        IgniteCacheProxy<?, ?> cache = F.first(grid(0).context().cache().publicCaches());

        assertNotNull("Should have configured public cache!", cache);

        Collection<GridCacheSqlMetadata> meta = cache.context().queries().sqlMetadata();

        testMetadata(meta);
    }

    /**
     * @throws Exception If failed.
     */
    public void testMetadataRemote() throws Exception {
        CacheConfiguration<Integer, String> partialCacheCfg = new CacheConfiguration<>("partial");

        partialCacheCfg.setIndexedTypes(Integer.class, String.class);
        partialCacheCfg.setNodeFilter(new NodeIdFilter(grid(1).localNode().id()));

        IgniteCacheProxy<Integer, String> c = (IgniteCacheProxy<Integer, String>)grid(1).createCache(partialCacheCfg);

        Collection<GridCacheSqlMetadata> metas = c.context().queries().sqlMetadata();

        testMetadata(metas);
    }

    /**
     * @throws Exception If failed.
     */
    public void testTopology() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.TOPOLOGY.key(), "attr", "false", "mtr", "false"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Topology command result: " + ret);

        jsonEquals(ret, pattern("\\[\\{.+\\}\\]", true));

        JSONObject json = JSONObject.fromObject(ret);

        Collection<Map> nodes = (Collection)json.get("response");

        assertEquals(GRID_CNT, nodes.size());

        for (Map node : nodes) {
            assertEquals(JSONNull.getInstance(), node.get("attributes"));
            assertEquals(JSONNull.getInstance(), node.get("metrics"));

            Collection<Map> caches = (Collection)node.get("caches");

            Collection<IgniteCacheProxy<?, ?>> publicCaches = grid(0).context().cache().publicCaches();

            assertNotNull(caches);
            assertEquals(publicCaches.size(), caches.size());

            for (Map cache : caches) {
                final String cacheName = cache.get("name").equals("") ? null : (String)cache.get("name");

                IgniteCacheProxy<?, ?> publicCache = F.find(publicCaches, null, new P1<IgniteCacheProxy<?, ?>>() {
                    @Override public boolean apply(IgniteCacheProxy<?, ?> c) {
                        return F.eq(c.getName(), cacheName);
                    }
                });

                assertNotNull(publicCache);

                CacheMode cacheMode = CacheMode.valueOf((String)cache.get("mode"));

                assertEquals(publicCache.getConfiguration(CacheConfiguration.class).getCacheMode(),cacheMode);
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testNode() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.NODE.key(), "attr", "true", "mtr", "true", "id",
            grid(0).localNode().id().toString()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Topology command result: " + ret);

        jsonEquals(ret, pattern("\\{.+\\}", true));

        ret = content(F.asMap("cmd", GridRestCommand.NODE.key(), "attr", "false", "mtr", "false", "ip", LOC_HOST));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Topology command result: " + ret);

        jsonEquals(ret, pattern("\\{.+\\}", true));

        ret = content(F.asMap("cmd", GridRestCommand.NODE.key(), "attr", "false", "mtr", "false", "ip", LOC_HOST, "id",
            UUID.randomUUID().toString()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Topology command result: " + ret);

        jsonEquals(ret, pattern("null", true));
    }

    /**
     * Tests {@code exe} command.
     * <p>
     * Note that attempt to execute unknown task (UNKNOWN_TASK) will result in exception on server.
     *
     * @throws Exception If failed.
     */
    public void testExe() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.EXE.key()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Exe command result: " + ret);

        jsonEquals(ret, pattern("null", false));

        // Attempt to execute unknown task (UNKNOWN_TASK) will result in exception on server.
        ret = content(F.asMap("cmd", GridRestCommand.EXE.key(), "name", "UNKNOWN_TASK"));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Exe command result: " + ret);

        jsonEquals(ret, pattern("null", false));

        grid(0).compute().localDeployTask(TestTask1.class, TestTask1.class.getClassLoader());
        grid(0).compute().localDeployTask(TestTask2.class, TestTask2.class.getClassLoader());

        ret = content(F.asMap("cmd", GridRestCommand.EXE.key(), "name", TestTask1.class.getName()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Exe command result: " + ret);

        jsonEquals(ret, pattern("\\{.+\\}", true));

        ret = content(F.asMap("cmd", GridRestCommand.EXE.key(), "name", TestTask2.class.getName()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Exe command result: " + ret);

        jsonEquals(ret, pattern("\\{.+" + TestTask2.RES + ".+\\}", true));

        ret = content(F.asMap("cmd", GridRestCommand.RESULT.key()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        info("Exe command result: " + ret);

        jsonEquals(ret, pattern("null", false));
    }

    /**
     * Tests execution of Visor tasks via {@link VisorGatewayTask}.
     *
     * @throws Exception If failed.
     */
    public void testVisorGateway() throws Exception {
        ClusterNode locNode = grid(1).localNode();

        final String successRes = pattern(
            "\\{\\\"error\\\":\\\"\\\",\\\"finished\\\":true,\\\"id\\\":\\\"[^\\\"]+\\\",\\\"result\\\":.+}", true);

        final IgniteUuid cid = grid(1).context().cache().internalCache("person").context().dynamicDeploymentId();

        String ret = content(new VisorGatewayArgument(VisorCacheConfigurationCollectorTask.class)
            .forNode(locNode)
            .collection(IgniteUuid.class, cid));

        info("VisorCacheConfigurationCollectorTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorCacheNodesTask.class)
            .forNode(locNode)
            .argument("person"));

        info("VisorCacheNodesTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorCacheLoadTask.class)
            .forNode(locNode)
            .tuple3(Set.class, Long.class, Object[].class, "person", 0, "null"));

        info("VisorCacheLoadTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorCacheSwapBackupsTask.class)
            .forNode(locNode)
            .set(String.class, "person"));

        info("VisorCacheSwapBackupsTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorCacheRebalanceTask.class)
            .forNode(locNode)
            .set(String.class, "person"));

        info("VisorCacheRebalanceTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorCacheMetadataTask.class)
            .forNode(locNode)
            .argument("person"));

        info("VisorCacheMetadataTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorCacheResetMetricsTask.class)
            .forNode(locNode)
            .argument("person"));

        info("VisorCacheResetMetricsTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorIgfsSamplingStateTask.class)
            .forNode(locNode)
            .pair(String.class, Boolean.class, "igfs", false));

        info("VisorIgfsSamplingStateTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorIgfsProfilerClearTask.class)
            .forNode(locNode)
            .argument("igfs"));

        info("VisorIgfsProfilerClearTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorIgfsProfilerTask.class)
            .forNode(locNode)
            .argument("igfs"));

        info("VisorIgfsProfilerTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorIgfsFormatTask.class)
            .forNode(locNode)
            .argument("igfs"));

        info("VisorIgfsFormatTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorIgfsResetMetricsTask.class)
            .forNode(locNode)
            .set(String.class, "igfs"));

        info("VisorIgfsResetMetricsTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorThreadDumpTask.class)
            .forNode(locNode));

        info("VisorThreadDumpTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorLatestTextFilesTask.class)
            .forNode(locNode)
            .pair(String.class, String.class, "", ""));

        info("VisorLatestTextFilesTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorLatestVersionTask.class)
            .forNode(locNode));

        info("VisorLatestVersionTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorFileBlockTask.class)
            .forNode(locNode)
            .argument(VisorFileBlockTask.VisorFileBlockArg.class, "", 0L, 1, 0L));

        info("VisorFileBlockTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorNodePingTask.class)
            .forNode(locNode)
            .argument(UUID.class, locNode.id()));

        info("VisorNodePingTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorNodeConfigurationCollectorTask.class)
            .forNode(locNode));

        info("VisorNodeConfigurationCollectorTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorComputeResetMetricsTask.class)
            .forNode(locNode));

        info("VisorComputeResetMetricsTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorQueryTask.class)
            .forNode(locNode)
            .argument(VisorQueryArg.class, "person", URLEncoder.encode("select * from Person"), false, 1));

        info("VisorQueryTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        JSONObject json = JSONObject.fromObject(ret);

        final String qryId = (String)((Map)((Map)((Map)json.get("response")).get("result")).get("value")).get("queryId");

        ret = content(new VisorGatewayArgument(VisorQueryNextPageTask.class)
            .forNode(locNode)
            .pair(String.class, Integer.class, qryId, 1));

        info("VisorQueryNextPageTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorQueryCleanupTask.class)
            .map(UUID.class, Set.class, F.asMap(locNode.id(), qryId)));

        info("VisorQueryCleanupTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorResolveHostNameTask.class)
            .forNode(locNode));

        info("VisorResolveHostNameTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        // Multinode tasks

        ret = content(new VisorGatewayArgument(VisorComputeCancelSessionsTask.class)
            .map(UUID.class, Set.class, new HashMap()));

        info("VisorComputeCancelSessionsTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorCacheMetricsCollectorTask.class)
            .pair(Boolean.class, Set.class, false, "person"));

        info("VisorCacheMetricsCollectorTask result: " + ret);

        ret = content(new VisorGatewayArgument(VisorCacheMetricsCollectorTask.class)
            .forNodes(grid(1).cluster().nodes())
            .pair(Boolean.class, Set.class, false, "person"));

        info("VisorCacheMetricsCollectorTask (with nodes) result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorLogSearchTask.class)
            .argument(VisorLogSearchTask.VisorLogSearchArg.class, ".", ".", "abrakodabra.txt", 1));

        info("VisorLogSearchTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorNodeGcTask.class));

        info("VisorNodeGcTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorAckTask.class)
            .argument("MSG"));

        info("VisorAckTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorNodeEventsCollectorTask.class)
            .argument(VisorNodeEventsCollectorTask.VisorNodeEventsCollectorTaskArg.class,
                "null", "null", "null", "taskName", "null"));

        info("VisorNodeEventsCollectorTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorNodeDataCollectorTask.class)
            .argument(VisorNodeDataCollectorTaskArg.class, false,
                "CONSOLE_" + UUID.randomUUID(), UUID.randomUUID(), 10, false));

        info("VisorNodeDataCollectorTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorComputeToggleMonitoringTask.class)
            .pair(String.class, Boolean.class, UUID.randomUUID(), false));

        info("VisorComputeToggleMonitoringTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorNodeSuppressedErrorsTask.class)
            .map(UUID.class, Long.class, new HashMap()));

        info("VisorNodeSuppressedErrorsTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorCacheClearTask.class)
            .forNode(locNode)
            .argument("person"));

        info("VisorCacheClearTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        /** Spring XML to start cache via Visor task. */
        final String START_CACHE =
            "<beans xmlns=\"http://www.springframework.org/schema/beans\"\n" +
                    "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "    xsi:schemaLocation=\"http://www.springframework.org/schema/beans\n" +
                    "        http://www.springframework.org/schema/beans/spring-beans-2.5.xsd\">\n" +
                    "    <bean id=\"cacheConfiguration\" class=\"org.apache.ignite.configuration.CacheConfiguration\">\n" +
                    "        <property name=\"cacheMode\" value=\"PARTITIONED\"/>\n" +
                    "        <property name=\"name\" value=\"c\"/>\n" +
                    "   </bean>\n" +
                    "</beans>";

        ret = content(new VisorGatewayArgument(VisorCacheStartTask.class)
            .argument(VisorCacheStartTask.VisorCacheStartArg.class, false, "person2", URLEncoder.encode(START_CACHE)));

        info("VisorCacheStartTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);

        ret = content(new VisorGatewayArgument(VisorCacheStopTask.class)
            .forNode(locNode)
            .argument(String.class, "c"));

        info("VisorCacheStopTask result: " + ret);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, successRes);
    }

    /**
     * @throws Exception If failed.
     */
    public void testVersion() throws Exception {
        String ret = content(F.asMap("cmd", GridRestCommand.VERSION.key()));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        jsonEquals(ret, stringPattern(".+", true));
    }

    /**
     * @throws Exception If failed.
     */
    public void testQueryArgs() throws Exception {
        String qry = "salary > ? and salary <= ?";

        Map<String, String> params = new HashMap<>();
        params.put("cmd", GridRestCommand.EXECUTE_SQL_QUERY.key());
        params.put("type", "Person");
        params.put("pageSize", "10");
        params.put("cacheName", "person");
        params.put("qry", URLEncoder.encode(qry));
        params.put("arg1", "1000");
        params.put("arg2", "2000");

        String ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        JSONObject json = JSONObject.fromObject(ret);

        List items = (List)((Map)json.get("response")).get("items");

        assertEquals(2, items.size());

        assertFalse(queryCursorFound());
    }

    /**
     * @throws Exception If failed.
     */
    public void testQueryScan() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("cmd", GridRestCommand.EXECUTE_SCAN_QUERY.key());
        params.put("pageSize", "10");
        params.put("cacheName", "person");

        String ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        JSONObject json = JSONObject.fromObject(ret);

        List items = (List)((Map)json.get("response")).get("items");

        assertEquals(4, items.size());

        assertFalse(queryCursorFound());
    }

    /**
     * @throws Exception If failed.
     */
    public void testFilterQueryScan() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("cmd", GridRestCommand.EXECUTE_SCAN_QUERY.key());
        params.put("pageSize", "10");
        params.put("cacheName", "person");
        params.put("className", ScanFilter.class.getName());

        String ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        JSONObject json = JSONObject.fromObject(ret);

        List items = (List)((Map)json.get("response")).get("items");

        assertEquals(2, items.size());

        assertFalse(queryCursorFound());
    }

    /**
     * @throws Exception If failed.
     */
    public void testIncorrectFilterQueryScan() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("cmd", GridRestCommand.EXECUTE_SCAN_QUERY.key());
        params.put("pageSize", "10");
        params.put("cacheName", "person");
        params.put("className", ScanFilter.class.getName() + 1);

        String ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        JSONObject json = JSONObject.fromObject(ret);

        String err = (String)json.get("error");

        assertTrue(err.contains("Failed to find target class"));
    }

    /**
     * @throws Exception If failed.
     */
    public void testQuery() throws Exception {
        grid(0).cache(null).put("1", "1");
        grid(0).cache(null).put("2", "2");
        grid(0).cache(null).put("3", "3");

        Map<String, String> params = new HashMap<>();
        params.put("cmd", GridRestCommand.EXECUTE_SQL_QUERY.key());
        params.put("type", "String");
        params.put("pageSize", "1");
        params.put("qry", URLEncoder.encode("select * from String"));

        String ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        JSONObject json = JSONObject.fromObject(ret);

        Integer qryId = (Integer)((Map)json.get("response")).get("queryId");

        assertNotNull(qryId);

        ret = content(F.asMap("cmd", GridRestCommand.FETCH_SQL_QUERY.key(),
            "pageSize", "1", "qryId", String.valueOf(qryId)));

        json = JSONObject.fromObject(ret);

        Integer qryId0 = (Integer)((Map)json.get("response")).get("queryId");

        Boolean last = (Boolean)((Map)json.get("response")).get("last");

        assertEquals(qryId0, qryId);
        assertFalse(last);

        ret = content(F.asMap("cmd", GridRestCommand.FETCH_SQL_QUERY.key(),
            "pageSize", "1", "qryId", String.valueOf(qryId)));

        json = JSONObject.fromObject(ret);

        qryId0 = (Integer)((Map)json.get("response")).get("queryId");

        last = (Boolean)((Map)json.get("response")).get("last");

        assertEquals(qryId0, qryId);
        assertTrue(last);

        assertFalse(queryCursorFound());
    }

    /**
     * @throws Exception If failed.
     */
    public void testSqlFieldsQuery() throws Exception {
        String qry = "select concat(firstName, ' ', lastName) from Person";

        Map<String, String> params = new HashMap<>();
        params.put("cmd", GridRestCommand.EXECUTE_SQL_FIELDS_QUERY.key());
        params.put("pageSize", "10");
        params.put("cacheName", "person");
        params.put("qry", URLEncoder.encode(qry));

        String ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        JSONObject json = JSONObject.fromObject(ret);

        List items = (List)((Map)json.get("response")).get("items");

        assertEquals(4, items.size());

        assertFalse(queryCursorFound());
    }

    /**
     * @throws Exception If failed.
     */
    public void testSqlFieldsMetadataQuery() throws Exception {
        String qry = "select firstName, lastName from Person";

        Map<String, String> params = new HashMap<>();
        params.put("cmd", GridRestCommand.EXECUTE_SQL_FIELDS_QUERY.key());
        params.put("pageSize", "10");
        params.put("cacheName", "person");
        params.put("qry", URLEncoder.encode(qry));

        String ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        JSONObject json = JSONObject.fromObject(ret);

        List items = (List)((Map)json.get("response")).get("items");

        List meta = (List)((Map)json.get("response")).get("fieldsMetadata");

        assertEquals(4, items.size());

        assertEquals(2, meta.size());

        JSONObject o = (JSONObject)meta.get(0);

        assertEquals("FIRSTNAME", o.get("fieldName"));
        assertEquals("java.lang.String", o.get("fieldTypeName"));
        assertEquals("person", o.get("schemaName"));
        assertEquals("PERSON", o.get("typeName"));

        assertFalse(queryCursorFound());
    }

    /**
     * @throws Exception If failed.
     */
    public void testQueryClose() throws Exception {
        String qry = "salary > ? and salary <= ?";

        Map<String, String> params = new HashMap<>();
        params.put("cmd", GridRestCommand.EXECUTE_SQL_QUERY.key());
        params.put("type", "Person");
        params.put("pageSize", "1");
        params.put("cacheName", "person");
        params.put("qry", URLEncoder.encode(qry));
        params.put("arg1", "1000");
        params.put("arg2", "2000");

        String ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        JSONObject json = JSONObject.fromObject(ret);

        List items = (List)((Map)json.get("response")).get("items");

        assertEquals(1, items.size());

        assertTrue(queryCursorFound());

        Integer qryId = (Integer)((Map)json.get("response")).get("queryId");

        assertNotNull(qryId);

        ret = content(F.asMap("cmd", GridRestCommand.CLOSE_SQL_QUERY.key(),
            "cacheName", "person", "qryId", String.valueOf(qryId)));

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        assertFalse(queryCursorFound());
    }

    /**
     * @throws Exception If failed.
     */
    public void testQueryDelay() throws Exception {
        String qry = "salary > ? and salary <= ?";

        Map<String, String> params = new HashMap<>();
        params.put("cmd", GridRestCommand.EXECUTE_SQL_QUERY.key());
        params.put("type", "Person");
        params.put("pageSize", "1");
        params.put("cacheName", "person");
        params.put("qry", URLEncoder.encode(qry));
        params.put("arg1", "1000");
        params.put("arg2", "2000");

        String ret = null;

        for (int i = 0; i < 10; ++i)
            ret = content(params);

        assertNotNull(ret);
        assertTrue(!ret.isEmpty());

        JSONObject json = JSONObject.fromObject(ret);

        List items = (List)((Map)json.get("response")).get("items");

        assertEquals(1, items.size());

        assertTrue(queryCursorFound());

        U.sleep(10000);

        assertFalse(queryCursorFound());
    }

    /**
     * @return Signature.
     * @throws Exception If failed.
     */
    protected abstract String signature() throws Exception;

    /**
     * @return True if any query cursor is available.
     */
    private boolean queryCursorFound() {
        boolean found = false;

        for (int i = 0; i < GRID_CNT; ++i) {
            Map<GridRestCommand, GridRestCommandHandler> handlers =
                GridTestUtils.getFieldValue(grid(i).context().rest(), "handlers");

            GridRestCommandHandler qryHnd = handlers.get(GridRestCommand.CLOSE_SQL_QUERY);

            ConcurrentHashMap<Long, Iterator> its = GridTestUtils.getFieldValue(qryHnd, "qryCurs");

            found |= its.size() != 0;
        }

        return found;
    }

    /**
     * Init cache.
     */
    private void initCache() {
        CacheConfiguration<Integer, Person> personCacheCfg = new CacheConfiguration<>("person");

        personCacheCfg.setIndexedTypes(Integer.class, Person.class);

        IgniteCache<Integer, Person> personCache = grid(0).getOrCreateCache(personCacheCfg);

        personCache.clear();

        Person p1 = new Person("John", "Doe", 2000);
        Person p2 = new Person("Jane", "Doe", 1000);
        Person p3 = new Person("John", "Smith", 1000);
        Person p4 = new Person("Jane", "Smith", 2000);

        personCache.put(p1.getId(), p1);
        personCache.put(p2.getId(), p2);
        personCache.put(p3.getId(), p3);
        personCache.put(p4.getId(), p4);

        SqlQuery<Integer, Person> qry = new SqlQuery<>(Person.class, "salary > ? and salary <= ?");

        qry.setArgs(1000, 2000);

        assertEquals(2, personCache.query(qry).getAll().size());
    }

    /**
     * Person class.
     */
    public static class Person implements Serializable {
        /** Person id. */
        private static int PERSON_ID = 0;

        /** Person ID (indexed). */
        @QuerySqlField(index = true)
        private Integer id;

        /** First name (not-indexed). */
        @QuerySqlField
        private String firstName;

        /** Last name (not indexed). */
        @QuerySqlField
        private String lastName;

        /** Salary (indexed). */
        @QuerySqlField(index = true)
        private double salary;

        /**
         * @param firstName First name.
         * @param lastName Last name.
         * @param salary Salary.
         */
        Person(String firstName, String lastName, double salary) {
            id = PERSON_ID++;

            this.firstName = firstName;
            this.lastName = lastName;
            this.salary = salary;
        }

        /**
         * @return First name.
         */
        public String getFirstName() {
            return firstName;
        }

        /**
         * @return Last name.
         */
        public String getLastName() {
            return lastName;
        }
        /**
         * @return Salary.
         */
        public double getSalary() {

            return salary;
        }

        /**
         * @return Id.
         */
        public Integer getId() {
            return id;
        }
    }

    /**
     * Test filter for scan query.
     */
    public static class ScanFilter implements IgniteBiPredicate<Integer, Person> {
        /** {@inheritDoc} */
        @Override public boolean apply(Integer integer, Person person) {
            return person.salary > 1000;
        }
    }

    /** Filter by node ID. */
    private static class NodeIdFilter implements IgnitePredicate<ClusterNode> {
        /** */
        private final UUID nid;

        /**
         * @param nid Node ID where cache should be started.
         */
        NodeIdFilter(UUID nid) {
            this.nid = nid;
        }

        /** {@inheritDoc} */
        @Override public boolean apply(ClusterNode n) {
            return n.id().equals(nid);
        }
    }

    /**
     * Helper for build {@link VisorGatewayTask} arguments.
     */
    public static class VisorGatewayArgument extends HashMap<String, String> {
        /** Latest argument index. */
        private int idx = 3;

        /**
         * Construct helper object.
         *
         * @param cls Class of executed task.
         */
        public VisorGatewayArgument(Class cls) {
            super(F.asMap(
                "cmd", GridRestCommand.EXE.key(),
                "name", VisorGatewayTask.class.getName(),
                "p1", "null",
                "p2", cls.getName()
            ));
        }

        /**
         * Execute task on node.
         *
         * @param node Node.
         * @return This helper for chaining method calls.
         */
        public VisorGatewayArgument forNode(ClusterNode node) {
            put("p1", node.id().toString());

            return this;
        }

        /**
         * Prepare list of node IDs.
         *
         * @param nodes Collection of nodes.
         * @return This helper for chaining method calls.
         */
        public VisorGatewayArgument forNodes(Collection<ClusterNode> nodes) {
            put("p1", concat(F.transform(nodes, new C1<ClusterNode, UUID>() {
                /** {@inheritDoc} */
                @Override public UUID apply(ClusterNode node) {
                    return node.id();
                }
            }).toArray(), ";"));

            return this;
        }

        /**
         * Add string argument.
         *
         * @param val Value.
         * @return This helper for chaining method calls.
         */
        public VisorGatewayArgument argument(String val) {
            put("p" + idx++, String.class.getName());
            put("p" + idx++, val);

            return this;
        }

        /**
         * Add custom class argument.
         *
         * @param cls Class.
         * @param vals Values.
         * @return This helper for chaining method calls.
         */
        public VisorGatewayArgument argument(Class cls, Object ... vals) {
            put("p" + idx++, cls.getName());

            for (Object val : vals)
                put("p" + idx++, val != null ? val.toString() : null);

            return this;
        }

        /**
         * Add collection argument.
         *
         * @param cls Class.
         * @param vals Values.
         * @return This helper for chaining method calls.
         */
        public VisorGatewayArgument collection(Class cls, Object ... vals) {
            put("p" + idx++, Collection.class.getName());
            put("p" + idx++, cls.getName());
            put("p" + idx++, concat(vals, ";"));

            return this;
        }

        /**
         * Add tuple argument.
         *
         * @param keyCls Key class.
         * @param valCls Values class.
         * @param key Key.
         * @param val Value.
         * @return This helper for chaining method calls.
         */
        public VisorGatewayArgument pair(Class keyCls, Class valCls, Object key, Object val) {
            put("p" + idx++, IgniteBiTuple.class.getName());
            put("p" + idx++, keyCls.getName());
            put("p" + idx++, valCls.getName());
            put("p" + idx++, key != null ? key.toString() : "null");
            put("p" + idx++, val != null ? val.toString() : "null");

            return this;
        }

        /**
         * Add tuple argument.
         *
         * @param firstCls Class of first argument.
         * @param secondCls Class of second argument.
         * @param thirdCls Class of third argument.
         * @param first First argument.
         * @param second Second argument.
         * @param third Third argument.
         * @return This helper for chaining method calls.
         */
        public VisorGatewayArgument tuple3(Class firstCls, Class secondCls, Class thirdCls,
            Object first, Object second, Object third) {
            put("p" + idx++, GridTuple3.class.getName());
            put("p" + idx++, firstCls.getName());
            put("p" + idx++, secondCls.getName());
            put("p" + idx++, thirdCls.getName());
            put("p" + idx++, first != null ? first.toString() : "null");
            put("p" + idx++, second != null ? second.toString() : "null");
            put("p" + idx++, third != null ? third.toString() : "null");

            return this;
        }

        /**
         * Add set argument.
         *
         * @param cls Class.
         * @param vals Values.
         * @return This helper for chaining method calls.
         */
        public VisorGatewayArgument set(Class cls, Object ... vals) {
            put("p" + idx++, Set.class.getName());
            put("p" + idx++, cls.getName());
            put("p" + idx++, concat(vals, ";"));

            return this;
        }

        /**
         * Add map argument.
         *
         * @param keyCls Key class.
         * @param valCls Value class.
         * @param map Map.
         */
        public VisorGatewayArgument map(Class keyCls, Class valCls, Map<?, ?> map) {
            put("p" + idx++, Map.class.getName());
            put("p" + idx++, keyCls.getName());
            put("p" + idx++, valCls.getName());

            SB sb = new SB();

            boolean first = true;

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first)
                    sb.a(";");

                sb.a(entry.getKey());

                if (entry.getValue() != null)
                    sb.a("=").a(entry.getValue());

                first = false;
            }

            put("p" + idx++, URLEncoder.encode(sb.toString()));

            return this;
        }

        /**
         * Concat object with delimiter.
         *
         * @param vals Values.
         * @param delim Delimiter.
         */
        private static String concat(Object[] vals, String delim) {
            SB sb = new SB();

            boolean first = true;

            for (Object val : vals) {
                if (!first)
                    sb.a(delim);

                sb.a(val);

                first = false;
            }

            return sb.toString();
        };
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        CacheConfiguration cacheIgfs_data = new CacheConfiguration();

        cacheIgfs_data.setName("igfs-data");
        cacheIgfs_data.setCacheMode(CacheMode.PARTITIONED);
        cacheIgfs_data.setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);
        cacheIgfs_data.setBackups(0);

        cacheIgfs_data.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

        cacheIgfs_data.setAffinityMapper(new IgfsGroupDataBlocksKeyMapper(512));

        CacheConfiguration cacheIgfs_meta = new CacheConfiguration();

        cacheIgfs_meta.setName("igfs-meta");
        cacheIgfs_meta.setCacheMode(CacheMode.REPLICATED);
        cacheIgfs_meta.setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);

        cacheIgfs_meta.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

        cfg.setCacheConfiguration(cfg.getCacheConfiguration()[0], cacheIgfs_data, cacheIgfs_meta);

        FileSystemConfiguration igfs = new FileSystemConfiguration();

        igfs.setName("igfs");
        igfs.setDataCacheName("igfs-data");
        igfs.setMetaCacheName("igfs-meta");

        igfs.setIpcEndpointConfiguration(new IgfsIpcEndpointConfiguration());

        cfg.setFileSystemConfiguration(igfs);

        return cfg;
    }
}
