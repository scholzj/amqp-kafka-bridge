/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.kafka.bridge.http;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.propagation.TextMapAdapter;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import io.strimzi.kafka.bridge.BridgeContentType;
import io.strimzi.kafka.bridge.EmbeddedFormat;
import io.strimzi.kafka.bridge.Endpoint;
import io.strimzi.kafka.bridge.SourceBridgeEndpoint;
import io.strimzi.kafka.bridge.config.BridgeConfig;
import io.strimzi.kafka.bridge.converter.MessageConverter;
import io.strimzi.kafka.bridge.http.converter.HttpBinaryMessageConverter;
import io.strimzi.kafka.bridge.http.converter.HttpJsonMessageConverter;
import io.strimzi.kafka.bridge.http.model.HttpBridgeError;
import io.strimzi.kafka.bridge.http.model.HttpBridgeResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import org.apache.kafka.common.serialization.Serializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HttpSourceBridgeEndpoint<K, V> extends SourceBridgeEndpoint<K, V> {

    private MessageConverter<K, V, Buffer, Buffer> messageConverter;

    public HttpSourceBridgeEndpoint(Vertx vertx, BridgeConfig bridgeConfig,
                                    EmbeddedFormat format, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        super(vertx, bridgeConfig, format, keySerializer, valueSerializer);
    }

    @Override
    public void open() {
        this.name = "kafka-bridge-producer-" + UUID.randomUUID();
        super.open();
    }

    @Override
    @SuppressWarnings({"checkstyle:NPathComplexity"})
    public void handle(Endpoint<?> endpoint) {
        RoutingContext routingContext = (RoutingContext) endpoint.get();
        // Tracing
        Tracer tracer = GlobalTracer.get();

        MultiMap rawHeaders = routingContext.request().headers();
        final HashMap<String, String> headers = new HashMap<String, String>();
        for (String key : rawHeaders.names()) {
            headers.put(key, rawHeaders.get(key));
        }

        String operationName = "produce";
        Tracer.SpanBuilder spanBuilder;
        try {
            SpanContext parentSpan = tracer.extract(Format.Builtin.HTTP_HEADERS, new TextMapAdapter(headers));
            if (parentSpan == null) {
                spanBuilder = tracer.buildSpan(operationName);
            } else {
                spanBuilder = tracer.buildSpan(operationName).asChildOf(parentSpan);
            }
        } catch (IllegalArgumentException e) {
            spanBuilder = tracer.buildSpan(operationName);
        }

        Span span = spanBuilder.withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER).start();
        // End of tracing

        messageConverter = this.buildMessageConverter();

        String topic = routingContext.pathParam("topicname");

        List<KafkaProducerRecord<K, V>> records;
        Integer partition = null;
        if (routingContext.pathParam("partitionid") != null) {
            try {
                partition = Integer.parseInt(routingContext.pathParam("partitionid"));
            } catch (NumberFormatException ne) {
                HttpBridgeError error = new HttpBridgeError(
                        HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                        "Specified partition is not a valid number");
                HttpUtils.sendResponse(routingContext, HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                        BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
                return;
            }
        }
        try {
            records = messageConverter.toKafkaRecords(topic, partition, routingContext.getBody());

            for (KafkaProducerRecord record :records)   {
                tracer.inject(span.context(), Format.Builtin.TEXT_MAP, new TextMap() {
                    @Override
                    public void put(String key, String value) {
                        record.addHeader(key, value);
                    }

                    @Override
                    public Iterator<Map.Entry<String, String>> iterator() {
                        throw new UnsupportedOperationException("TextMapInjectAdapter should only be used with Tracer.inject()");
                    }
                });
            }
        } catch (Exception e) {
            HttpBridgeError error = new HttpBridgeError(
                    HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                    e.getMessage());
            HttpUtils.sendResponse(routingContext, HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                    BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
            span.finish();
            return;
        }
        List<HttpBridgeResult<?>> results = new ArrayList<>(records.size());

        // start sending records asynchronously
        List<Future> sendHandlers = new ArrayList<>(records.size());
        for (KafkaProducerRecord<K, V> record : records) {
            Future<RecordMetadata> fut = Future.future();
            sendHandlers.add(fut);
            this.send(record, fut.completer());
        }

        // wait for ALL futures completed
        List<KafkaProducerRecord<K, V>> finalRecords = records;
        CompositeFuture.join(sendHandlers).setHandler(done -> {

            for (int i = 0; i < sendHandlers.size(); i++) {
                // check if, for each future, the sending operation is completed successfully or failed
                if (done.result() != null && done.result().succeeded(i)) {
                    RecordMetadata metadata = done.result().resultAt(i);
                    log.debug("Delivered record {} to Kafka on topic {} at partition {} [{}]", finalRecords.get(i), metadata.getTopic(), metadata.getPartition(), metadata.getOffset());
                    results.add(new HttpBridgeResult<>(metadata));
                } else {
                    String msg = done.cause().getMessage();
                    int code = getCodeFromMsg(msg);
                    log.error("Failed to deliver record " + finalRecords.get(i) + " due to {}", done.cause());
                    results.add(new HttpBridgeResult<>(new HttpBridgeError(code, msg)));
                }
            }
            HttpUtils.sendResponse(routingContext, HttpResponseStatus.OK.code(),
                    BridgeContentType.KAFKA_JSON, buildOffsets(results).toBuffer());
        });

        span.finish();
    }

    @Override
    public void handle(Endpoint<?> endpoint, Handler<?> handler) {

    }

    private JsonObject buildOffsets(List<HttpBridgeResult<?>> results) {
        JsonObject jsonResponse = new JsonObject();
        JsonArray offsets = new JsonArray();

        for (HttpBridgeResult<?> result : results) {
            JsonObject offset = null;
            if (result.getResult() instanceof RecordMetadata) {
                RecordMetadata metadata = (RecordMetadata) result.getResult();
                offset = new JsonObject()
                        .put("partition", metadata.getPartition())
                        .put("offset", metadata.getOffset());
            } else if (result.getResult() instanceof HttpBridgeError) {
                HttpBridgeError error = (HttpBridgeError) result.getResult();
                offset = error.toJson();
            }
            offsets.add(offset);
        }
        jsonResponse.put("offsets", offsets);
        return jsonResponse;
    }

    private int getCodeFromMsg(String msg) {
        if (msg.contains("Invalid partition")) {
            return HttpResponseStatus.NOT_FOUND.code();
        } else {
            return HttpResponseStatus.INTERNAL_SERVER_ERROR.code();
        }
    }

    private MessageConverter<K, V, Buffer, Buffer> buildMessageConverter() {
        switch (this.format) {
            case JSON:
                return (MessageConverter<K, V, Buffer, Buffer>) new HttpJsonMessageConverter();
            case BINARY:
                return (MessageConverter<K, V, Buffer, Buffer>) new HttpBinaryMessageConverter();
        }
        return null;
    }
}
