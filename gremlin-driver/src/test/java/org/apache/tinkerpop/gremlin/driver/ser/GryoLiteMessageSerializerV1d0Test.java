/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.driver.ser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.tinkerpop.gremlin.driver.MessageSerializer;
import org.apache.tinkerpop.gremlin.driver.message.RequestMessage;
import org.apache.tinkerpop.gremlin.driver.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.driver.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Tree;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.io.AbstractIoRegistry;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoClassResolver;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoIo;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceEdge;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerFactory;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.apache.tinkerpop.shaded.kryo.ClassResolver;
import org.apache.tinkerpop.shaded.kryo.Kryo;
import org.apache.tinkerpop.shaded.kryo.KryoException;
import org.apache.tinkerpop.shaded.kryo.Registration;
import org.apache.tinkerpop.shaded.kryo.Serializer;
import org.apache.tinkerpop.shaded.kryo.io.Input;
import org.apache.tinkerpop.shaded.kryo.io.Output;
import org.junit.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Serializer tests that cover non-lossy serialization/deserialization methods.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class GryoLiteMessageSerializerV1d0Test {
    private static final Map<String, Object> config = new HashMap<String, Object>() {{
        put(AbstractGryoMessageSerializerV1d0.TOKEN_SERIALIZE_RESULT_TO_STRING, true);
    }};

    private UUID requestId = UUID.fromString("6457272A-4018-4538-B9AE-08DD5DDC0AA1");
    private ResponseMessage.Builder responseMessageBuilder = ResponseMessage.build(requestId);
    private static ByteBufAllocator allocator = UnpooledByteBufAllocator.DEFAULT;

    public MessageSerializer binarySerializer = new GryoLiteMessageSerializerV1d0();

    public MessageSerializer textSerializer = new GryoLiteMessageSerializerV1d0();

    public GryoLiteMessageSerializerV1d0Test() {
        textSerializer.configure(config, null);
    }

    @Test
    public void shouldConfigureIoRegistry() throws Exception {
        final MessageSerializer serializer = new GryoLiteMessageSerializerV1d0();
        final Map<String, Object> config = new HashMap<String, Object>() {{
            put(AbstractGryoMessageSerializerV1d0.TOKEN_IO_REGISTRIES, Arrays.asList(ColorIoRegistry.class.getName()));
        }};

        serializer.configure(config, null);

        final ResponseMessage toSerialize = ResponseMessage.build(requestId).result(Color.RED).create();
        final ByteBuf bb = serializer.serializeResponseAsBinary(toSerialize, allocator);
        final ResponseMessage deserialized = serializer.deserializeResponse(bb);

        assertCommon(deserialized);
        assertEquals(Color.RED, deserialized.getResult().getData());
    }

    @Test
    public void shouldConfigureCustomClassResolver() {
        final MessageSerializer serializer = new GryoLiteMessageSerializerV1d0();
        final Map<String, Object> config = new HashMap<String, Object>() {{
            put(AbstractGryoMessageSerializerV1d0.TOKEN_CLASS_RESOLVER_SUPPLIER, ErrorOnlyClassResolverSupplier.class.getName());
        }};

        serializer.configure(config, null);

        try {
            serializer.serializeResponseAsBinary(responseMessageBuilder.create(), allocator);
            fail("Should fail because the ClassResolver used here always generates an error");
        } catch (Exception ex) {
            assertEquals("java.lang.RuntimeException: Registration is not allowed with this ClassResolver - it is not a good implementation", ex.getMessage());
        }
    }

    @Test
    public void shouldConfigureCustomClassResolverFromInstance() {
        final MessageSerializer serializer = new GryoLiteMessageSerializerV1d0();
        final Map<String, Object> config = new HashMap<String, Object>() {{
            put(AbstractGryoMessageSerializerV1d0.TOKEN_CLASS_RESOLVER_SUPPLIER, ErrorOnlyClassResolverSupplierAsInstance.class.getName());
        }};

        serializer.configure(config, null);

        try {
            serializer.serializeResponseAsBinary(responseMessageBuilder.create(), allocator);
            fail("Should fail because the ClassResolver used here always generates an error");
        } catch (Exception ex) {
            assertEquals("java.lang.RuntimeException: Registration is not allowed with this ClassResolver - it is not a good implementation", ex.getMessage());
        }
    }

    @Test
    public void shouldSerializeIterable() throws Exception {
        final ArrayList<Integer> list = new ArrayList<>();
        list.add(1);
        list.add(100);

        final ResponseMessage response = convertBinary(list);
        assertCommon(response);

        final List<Integer> deserializedFunList = (List<Integer>) response.getResult().getData();
        assertEquals(2, deserializedFunList.size());
        assertEquals(new Integer(1), deserializedFunList.get(0));
        assertEquals(new Integer(100), deserializedFunList.get(1));
    }

    @Test
    public void shouldSerializeIterableToString() throws Exception {
        final ArrayList<Integer> list = new ArrayList<>();
        list.add(1);
        list.add(100);

        final ResponseMessage response = convertText(list);
        assertCommon(response);

        final List deserializedFunList = (List) response.getResult().getData();
        assertEquals(2, deserializedFunList.size());
        assertEquals("1", deserializedFunList.get(0));
        assertEquals("100", deserializedFunList.get(1));
    }

    @Test
    public void shouldSerializeIterableToStringWithNull() throws Exception {
        final ArrayList<Integer> list = new ArrayList<>();
        list.add(1);
        list.add(null);
        list.add(100);

        final ResponseMessage response = convertText(list);
        assertCommon(response);

        final List deserializedFunList = (List) response.getResult().getData();
        assertEquals(3, deserializedFunList.size());
        assertEquals("1", deserializedFunList.get(0).toString());
        assertEquals("null", deserializedFunList.get(1).toString());
        assertEquals("100", deserializedFunList.get(2).toString());
    }

    @Test
    public void shouldSerializeIterableWithNull() throws Exception {
        final ArrayList<Integer> list = new ArrayList<>();
        list.add(1);
        list.add(null);
        list.add(100);

        final ResponseMessage response = convertBinary(list);
        assertCommon(response);

        final List<Integer> deserializedFunList = (List<Integer>) response.getResult().getData();
        assertEquals(3, deserializedFunList.size());
        assertEquals(new Integer(1), deserializedFunList.get(0));
        assertNull(deserializedFunList.get(1));
        assertEquals(new Integer(100), deserializedFunList.get(2));
    }

    @Test
    public void shouldSerializeMap() throws Exception {
        final Map<String, Object> map = new HashMap<>();
        final Map<String, String> innerMap = new HashMap<>();
        innerMap.put("a", "b");

        map.put("x", 1);
        map.put("y", "some");
        map.put("z", innerMap);

        final ResponseMessage response = convertBinary(map);
        assertCommon(response);

        final Map<String, Object> deserializedMap = (Map<String, Object>) response.getResult().getData();
        assertEquals(3, deserializedMap.size());
        assertEquals(1, deserializedMap.get("x"));
        assertEquals("some", deserializedMap.get("y"));

        final Map<String, String> deserializedInnerMap = (Map<String, String>) deserializedMap.get("z");
        assertEquals(1, deserializedInnerMap.size());
        assertEquals("b", deserializedInnerMap.get("a"));
    }

    @Test
    public void shouldSerializeMapEntry() throws Exception {
        final Graph graph = TinkerGraph.open();
        final Vertex v1 = graph.addVertex();
        final Date d = new Date();

        final Map<Object, Object> map = new HashMap<>();
        map.put("x", 1);
        map.put(v1, 100);
        map.put(d, "test");

        final ResponseMessage response = convertBinary(IteratorUtils.asList(map.entrySet()));
        assertCommon(response);

        final List<Map.Entry<Object, Object>> deserializedEntries = (List<Map.Entry<Object, Object>>) response.getResult().getData();
        assertEquals(3, deserializedEntries.size());
        deserializedEntries.forEach(e -> {
            if (e.getKey().equals("x"))
                assertEquals(1, e.getValue());
            else if (e.getKey().equals(v1))
                assertEquals(100, e.getValue());
            else if (e.getKey().equals(d))
                assertEquals("test", e.getValue());
            else
                fail("Map entries contains a key that is not part of what was serialized");
        });
    }

    @Test
    public void shouldSerializeEdge() throws Exception {
        final Graph g = TinkerGraph.open();
        final Vertex v1 = g.addVertex();
        final Vertex v2 = g.addVertex();
        final Edge e = v1.addEdge("test", v2);
        e.property("abc", 123);

        final Iterable<Edge> iterable = IteratorUtils.list(g.edges());

        final ResponseMessage response = convertBinary(iterable);
        assertCommon(response);

        final List<ReferenceEdge> edgeList = (List<ReferenceEdge>) response.getResult().getData();
        assertEquals(1, edgeList.size());

        final ReferenceEdge deserializedEdge = edgeList.get(0);
        assertEquals(e.id(), deserializedEdge.id());
        assertEquals("test", deserializedEdge.label());

        assertEquals(0, IteratorUtils.count(deserializedEdge.properties()));
        assertEquals(v1.id(), deserializedEdge.outVertex().id());
        assertEquals("", deserializedEdge.outVertex().label());
        assertEquals(v2.id(), deserializedEdge.inVertex().id());
        assertEquals("", deserializedEdge.inVertex().label());
    }

    @Test
    public void shouldSerializeTree() throws Exception {
        final Graph g = TinkerFactory.createModern();
        final Tree t = g.traversal().V().out().out().tree().by("name").next();

        final ResponseMessage response = convertBinary(t);
        assertCommon(response);

        final Tree deserialized = (Tree) response.getResult().getData();
        assertEquals(t, deserialized);

        assertThat(deserialized.containsKey("marko"), is(true));
        assertEquals(1, deserialized.size());

        final Tree markoChildren = (Tree) deserialized.get("marko");
        assertThat(markoChildren.containsKey("josh"), is(true));
        assertEquals(1, markoChildren.size());

        final Tree joshChildren = (Tree) markoChildren.get("josh");
        assertThat(joshChildren.containsKey("lop"), is(true));
        assertThat(joshChildren.containsKey("ripple"), is(true));
        assertEquals(2, joshChildren.size());
    }

    @Test
    public void shouldSerializeVertexWithEmbeddedMap() throws Exception {
        final Graph g = TinkerGraph.open();
        final Vertex v = g.addVertex();
        final Map<String, Object> map = new HashMap<>();
        map.put("x", 500);
        map.put("y", "some");

        final ArrayList<Object> friends = new ArrayList<>();
        friends.add("x");
        friends.add(5);
        friends.add(map);

        v.property(VertexProperty.Cardinality.single, "friends", friends);

        final List list = IteratorUtils.list(g.vertices());

        final ResponseMessage response = convertBinary(list);
        assertCommon(response);

        final List<ReferenceVertex> vertexList = (List<ReferenceVertex>) response.getResult().getData();
        assertEquals(1, vertexList.size());

        final ReferenceVertex deserializedVertex = vertexList.get(0);
        assertEquals(0L, deserializedVertex.id());
        assertEquals("", deserializedVertex.label());

        assertEquals(0, IteratorUtils.count(deserializedVertex.properties()));
    }

    @Test
    public void shouldSerializeToMapWithElementForKey() throws Exception {
        final TinkerGraph graph = TinkerFactory.createClassic();
        final GraphTraversalSource g = graph.traversal();
        final Map<Vertex, Integer> map = new HashMap<>();
        map.put(g.V().has("name", "marko").next(), 1000);

        final ResponseMessage response = convertBinary(map);
        assertCommon(response);

        final Map<Vertex, Integer> deserializedMap = (Map<Vertex, Integer>) response.getResult().getData();
        assertEquals(1, deserializedMap.size());

        final Vertex deserializedMarko = deserializedMap.keySet().iterator().next();
        assertEquals(0, IteratorUtils.count(deserializedMarko.properties()));
        assertEquals(1, deserializedMarko.id());
        assertEquals("", deserializedMarko.label());

        assertEquals(new Integer(1000), deserializedMap.values().iterator().next());
    }

    @Test
    public void shouldSerializeFullResponseMessage() throws Exception {
        final UUID id = UUID.randomUUID();

        final Map<String, Object> metaData = new HashMap<>();
        metaData.put("test", "this");
        metaData.put("one", 1);

        final Map<String, Object> attributes = new HashMap<>();
        attributes.put("test", "that");
        attributes.put("two", 2);

        final ResponseMessage response = ResponseMessage.build(id)
                .responseMetaData(metaData)
                .code(ResponseStatusCode.SUCCESS)
                .result("some-result")
                .statusAttributes(attributes)
                .statusMessage("worked")
                .create();

        final ByteBuf bb = binarySerializer.serializeResponseAsBinary(response, allocator);
        final ResponseMessage deserialized = binarySerializer.deserializeResponse(bb);

        assertEquals(id, deserialized.getRequestId());
        assertEquals("this", deserialized.getResult().getMeta().get("test"));
        assertEquals(1, deserialized.getResult().getMeta().get("one"));
        assertEquals("some-result", deserialized.getResult().getData());
        assertEquals("that", deserialized.getStatus().getAttributes().get("test"));
        assertEquals(2, deserialized.getStatus().getAttributes().get("two"));
        assertEquals(ResponseStatusCode.SUCCESS.getValue(), deserialized.getStatus().getCode().getValue());
        assertEquals("worked", deserialized.getStatus().getMessage());
    }

    @Test
    public void shouldHaveTooSmallBufferToSerializeResponseMessage() throws Exception {
        final UUID id = UUID.randomUUID();

        final Map<String, Object> metaData = new HashMap<>();
        metaData.put("test", "this");
        metaData.put("one", 1);

        final Map<String, Object> attributes = new HashMap<>();
        attributes.put("test", "that");
        attributes.put("two", 2);

        final ResponseMessage response = ResponseMessage.build(id)
                .responseMetaData(metaData)
                .code(ResponseStatusCode.SUCCESS)
                .result("some-result")
                .statusAttributes(attributes)
                .statusMessage("worked")
                .create();

        final MessageSerializer binarySerializerWithSmallBuffer = new GryoLiteMessageSerializerV1d0();
        final Map<String, Object> configWithSmallBuffer = new HashMap<String, Object>() {{
            put("bufferSize", 1);
        }};
        binarySerializerWithSmallBuffer.configure(configWithSmallBuffer, null);

        try {
            binarySerializerWithSmallBuffer.serializeResponseAsBinary(response, allocator);
            fail("Should have a buffer size that is too small");
        } catch (Exception ex) {
            final Throwable root = ExceptionUtils.getRootCause(ex);
            assertThat(root, instanceOf(KryoException.class));
        }
    }

    @Test
    public void shouldReturnAllBytesInResponse() throws Exception {
        final UUID id = UUID.randomUUID();

        final Map<String, Object> metaData = new HashMap<>();
        metaData.put("test", "this");
        metaData.put("one", 1);

        final Map<String, Object> attributes = new HashMap<>();
        attributes.put("test", "that");
        attributes.put("two", 2);

        final ResponseMessage response = ResponseMessage.build(id)
                .responseMetaData(metaData)
                .code(ResponseStatusCode.SUCCESS)
                .result("some-result")
                .statusAttributes(attributes)
                .statusMessage("worked")
                .create();

        final MessageSerializer binarySerializerWithSmallBuffer = new GryoLiteMessageSerializerV1d0();
        final Map<String, Object> configWithSmallBuffer = new HashMap<String, Object>() {{
            // set to bufferSize < total message size but still greater than any individual object requires
            put("bufferSize", 50);
        }};
        binarySerializerWithSmallBuffer.configure(configWithSmallBuffer, null);

        ByteBuf buf = binarySerializerWithSmallBuffer.serializeResponseAsBinary(response, allocator);
        assertTrue(buf.isReadable());
        assertEquals(82, buf.readableBytes());
    }

    @Test
    public void shouldSerializeFullRequestMessage() throws Exception {
        final UUID id = UUID.randomUUID();

        final RequestMessage request = RequestMessage.build("try")
                .overrideRequestId(id)
                .processor("pro")
                .addArg("test", "this")
                .create();
        final ByteBuf bb = binarySerializer.serializeRequestAsBinary(request, allocator);
        final int mimeLen = bb.readByte();
        bb.readBytes(new byte[mimeLen]);
        final RequestMessage deserialized = binarySerializer.deserializeRequest(bb);

        assertEquals(id, deserialized.getRequestId());
        assertEquals("pro", deserialized.getProcessor());
        assertEquals("try", deserialized.getOp());
        assertEquals("this", deserialized.getArgs().get("test"));
    }

    @Test
    public void shouldHaveTooSmallBufferToSerializeRequestMessage() throws Exception {
        final UUID id = UUID.randomUUID();

        final RequestMessage request = RequestMessage.build("try")
                .overrideRequestId(id)
                .processor("pro")
                .addArg("test", "this")
                .create();

        final MessageSerializer binarySerializerWithSmallBuffer = new GryoLiteMessageSerializerV1d0();
        final Map<String, Object> configWithSmallBuffer = new HashMap<String, Object>() {{
            put("bufferSize", 1);
        }};
        binarySerializerWithSmallBuffer.configure(configWithSmallBuffer, null);

        try {
            binarySerializerWithSmallBuffer.serializeRequestAsBinary(request, allocator);
            fail("Should have a buffer size that is too small");
        } catch (Exception ex) {
            final Throwable root = ExceptionUtils.getRootCause(ex);
            assertThat(root, instanceOf(KryoException.class));
        }
    }

    @Test
    public void shouldReturnAllBytesInRequest() throws Exception {
        final UUID id = UUID.randomUUID();

        final RequestMessage request = RequestMessage.build("try")
                .overrideRequestId(id)
                .processor("pro")
                .addArg("test", "this")
                .create();

        final MessageSerializer binarySerializerWithSmallBuffer = new GryoLiteMessageSerializerV1d0();
        final Map<String, Object> configWithSmallBuffer = new HashMap<String, Object>() {{
            // set to bufferSize < total message size but still greater than any individual object requires
            put("bufferSize", 50);
        }};
        binarySerializerWithSmallBuffer.configure(configWithSmallBuffer, null);

        ByteBuf buf = binarySerializerWithSmallBuffer.serializeRequestAsBinary(request, allocator);
        assertTrue(buf.isReadable());
        assertEquals(76, buf.readableBytes());
    }

    private void assertCommon(final ResponseMessage response) {
        assertEquals(requestId, response.getRequestId());
        assertEquals(ResponseStatusCode.SUCCESS, response.getStatus().getCode());
    }

    private ResponseMessage convertBinary(final Object toSerialize) throws SerializationException {
        final ByteBuf bb = binarySerializer.serializeResponseAsBinary(responseMessageBuilder.result(toSerialize).create(), allocator);
        return binarySerializer.deserializeResponse(bb);
    }

    private ResponseMessage convertText(final Object toSerialize) throws SerializationException {
        final ByteBuf bb = textSerializer.serializeResponseAsBinary(responseMessageBuilder.result(toSerialize).create(), allocator);
        return textSerializer.deserializeResponse(bb);
    }

    public static class ErrorOnlyClassResolverSupplierAsInstance implements Supplier<ClassResolver> {

        private static final ErrorOnlyClassResolverSupplierAsInstance instance = new ErrorOnlyClassResolverSupplierAsInstance();

        private ErrorOnlyClassResolverSupplierAsInstance() {}

        public static ErrorOnlyClassResolverSupplierAsInstance getInstance() {
            return instance;
        }

        @Override
        public ClassResolver get() {
            return new ErrorOnlyClassResolver();
        }
    }

    public static class ErrorOnlyClassResolverSupplier implements Supplier<ClassResolver> {
        @Override
        public ClassResolver get() {
            return new ErrorOnlyClassResolver();
        }
    }

    public static class ErrorOnlyClassResolver extends GryoClassResolver {
        @Override
        public Registration getRegistration(Class clazz) {
            throw new RuntimeException("Registration is not allowed with this ClassResolver - it is not a good implementation");
        }
    }

    public static class ColorIoRegistry extends AbstractIoRegistry {
        public ColorIoRegistry() {
            register(GryoIo.class, Color.class, new ColorSerializer());
        }
    }

    public static class ColorSerializer extends Serializer<Color> {
        @Override
        public void write(final Kryo kryo, final Output output, final Color color) {
            output.write(color.equals(Color.RED) ? 1 : 0);
        }

        @Override
        public Color read(final Kryo kryo, final Input input, final Class<Color> aClass) {
            return input.read() == 1 ? Color.RED : Color.BLACK;
        }
    }
}