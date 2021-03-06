/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.rest.http;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.CREATED;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.List;

import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.util.CharsetUtil;

import com.google.protobuf.ByteString;
import org.apache.kafka.rest.RestApiProto.RestApiMessage;
import org.apache.kafka.rest.RestApiProto;
import org.apache.kafka.rest.metrics.MetricsManager;
import org.apache.kafka.rest.producer.Producer;
import org.apache.kafka.rest.util.HttpUtil;
import org.apache.kafka.rest.validation.Validator;

public class SubmissionHandler extends SimpleChannelUpstreamHandler {

    public static final String HEADER_OBSOLETE_DOCUMENT = "X-Obsolete-Document";

    private static final Logger LOG = Logger.getLogger(SubmissionHandler.class);

    // REST endpoints
    public static final String ENDPOINT_SUBMIT = "submit";

    private final Producer producer;
    private final ChannelGroup channelGroup;
    private final MetricsManager metricsManager;

    public SubmissionHandler(Validator validator,
                             Producer producer,
                             ChannelGroup channelGroup,
                             MetricsManager metricsManager) {
        this.producer = producer;
        this.channelGroup = channelGroup;
        this.metricsManager = metricsManager;
    }

    private void updateRequestMetrics(String namespace, String method, int size) {
        this.metricsManager.getHttpMetricForNamespace(namespace).updateRequestMetrics(method, size);
        this.metricsManager.getGlobalHttpMetric().updateRequestMetrics(method, size);
    }

    private void updateResponseMetrics(String namespace, int status) {
        if (namespace != null) {
            this.metricsManager.getHttpMetricForNamespace(namespace).updateResponseMetrics(status);
        }
        this.metricsManager.getGlobalHttpMetric().updateResponseMetrics(status);
    }

    private void handlePost(MessageEvent e, KafkaRestApiHttpRequest request) {
        HttpResponseStatus status = BAD_REQUEST;
        ChannelBuffer content = request.getContent();
        String remoteIpAddress = HttpUtil.getRemoteAddr(request, ((InetSocketAddress)e.getChannel().getRemoteAddress()).getAddress().getHostAddress());
        if (content.readable() && content.readableBytes() > 0) {
            RestApiMessage.Builder templateBuilder = RestApiMessage.newBuilder();
            setMessageFields(request, e, templateBuilder, System.currentTimeMillis(), false);
            RestApiMessage template = templateBuilder.buildPartial();

            RestApiMessage.Builder storeBuilder = RestApiMessage.newBuilder(template);
            storeBuilder.setPayload(ByteString.copyFrom(content.toByteBuffer()));
            storeBuilder.setId(request.getId());
            producer.send(storeBuilder.build());

            if (request.containsHeader(HEADER_OBSOLETE_DOCUMENT)) {
                handleObsoleteDocuments(request,remoteIpAddress,request.getHeaders(HEADER_OBSOLETE_DOCUMENT), template);
            } else {
                LOG.info("IP "+remoteIpAddress+" "+request.getNamespace()+" HTTP_PUT "+request.getId());
            }

            status = CREATED;
        }

        updateRequestMetrics(request.getNamespace(), request.getMethod().getName(), content.readableBytes());
        writeResponse(status, e, request.getNamespace(), URI.create(request.getId()).toString());
    }

    protected void setMessageFields(KafkaRestApiHttpRequest request, MessageEvent event, RestApiMessage.Builder builder, long timestamp, boolean setId) {
        builder.setNamespace(request.getNamespace());
        if (request.getApiVersion() != null) {
            builder.setApiVersion(request.getApiVersion());
        }

        List<String> partitions = request.getPartitions();
        if (partitions != null && !partitions.isEmpty()) {
            builder.addAllPartition(partitions);
        }

        builder.setIpAddr(ByteString.copyFrom(HttpUtil.getRemoteAddr(request, ((InetSocketAddress)event.getChannel().getRemoteAddress()).getAddress())));
        builder.setTimestamp(timestamp);

        if (setId) {
            builder.setId(request.getId());
        }
    }

    private void handleObsoleteDocuments(KafkaRestApiHttpRequest request, String remoteIpAddress,List<String> headers, RestApiMessage template) {
        // According to RFC 2616, the standard for multi-valued document headers is
        // a comma-separated list:
        // http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.2
        //  ------------------------------------------------------------------
        //   Multiple message-header fields with the same field-name MAY be
        //   present in a message if and only if the entire field-value for
        //   that header field is defined as a comma-separated list
        //   [i.e., #(values)]. It MUST be possible to combine the multiple
        //   header fields into one "field-name: field-value" pair, without
        //   changing the semantics of the message, by appending each
        //   subsequent field-value to the first, each separated by a comma.
        //   The order in which header fields with the same field-name are
        //   received is therefore significant to the interpretation of the
        //   combined field value, and thus a proxy MUST NOT change the order
        //   of these field values when a message is forwarded.
        //  ------------------------------------------------------------------
        String deleteIDs = "";
        for (String header : headers) {
            // Split on comma, delete each one.
            // The performance penalty for supporting multiple values is
            // tested in KafkaRestApiHttpRequestTest.testSplitPerformance().
            if (header != null) {
                for (String obsoleteIdRaw : header.split(",")) {
                    deleteIDs += obsoleteIdRaw.trim()+",";
                    // Use the given message as a base for creating each delete message.
                    RestApiMessage.Builder deleteBuilder = RestApiMessage.newBuilder(template);
                    deleteBuilder.setOperation(RestApiMessage.Operation.DELETE);
                    deleteBuilder.setId(obsoleteIdRaw.trim());
                    producer.send(deleteBuilder.build());
                }
            }
        }
        LOG.info("IP "+remoteIpAddress+" "+request.getNamespace()+" HTTP_PUT "+request.getId()+" HTTP_DELETE "+deleteIDs);
    }

    private void handleDelete(MessageEvent e, KafkaRestApiHttpRequest request) {
        RestApiMessage.Builder bmsgBuilder = RestApiMessage.newBuilder();
        setMessageFields(request, e, bmsgBuilder, System.currentTimeMillis(), true);
        String remoteIpAddress = HttpUtil.getRemoteAddr(request, ((InetSocketAddress)e.getChannel().getRemoteAddress()).getAddress().getHostAddress());
        LOG.info("IP "+remoteIpAddress+" "+request.getNamespace()+" HTTP_DELETE "+request.getId());
        bmsgBuilder.setOperation(RestApiMessage.Operation.DELETE);
        producer.send(bmsgBuilder.build());
        updateRequestMetrics(request.getNamespace(), request.getMethod().getName(), 0);
        writeResponse(OK, e, request.getNamespace(), null);
    }

    private void handleOptions(MessageEvent e, KafkaRestApiHttpRequest request) {
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "POST,PUT,DELETE");
        response.addHeader("Access-Control-Allow-Headers", "X-Requested-With, Content-Type, Content-Length");
        ChannelFuture future = e.getChannel().write(response);
        future.addListener(ChannelFutureListener.CLOSE);
    }

    private void writeResponse(HttpResponseStatus status, MessageEvent e, String namespace, String entity) {
        // Build the response object.
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, status);
        response.addHeader(CONTENT_TYPE, "plain/text");
        if (entity != null) {
            ChannelBuffer buf = ChannelBuffers.wrappedBuffer(entity.getBytes(CharsetUtil.UTF_8));
            response.setContent(buf);
            response.setHeader(CONTENT_LENGTH, response.getContent().readableBytes());
        }

        // Write response
        ChannelFuture future = e.getChannel().write(response);
        future.addListener(ChannelFutureListener.CLOSE);

        updateResponseMetrics(namespace, response.getStatus().getCode());
    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) {
        this.channelGroup.add(e.getChannel());
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Object msg = e.getMessage();

        if (msg instanceof KafkaRestApiHttpRequest) {
            KafkaRestApiHttpRequest request = (KafkaRestApiHttpRequest) e.getMessage();
            if (ENDPOINT_SUBMIT.equals(request.getEndpoint())) {
                if ((request.getMethod() == HttpMethod.POST || request.getMethod() == HttpMethod.PUT)) {
                    handlePost(e, request);
                } else if (request.getMethod() == HttpMethod.GET) {
                    writeResponse(METHOD_NOT_ALLOWED, e, request.getNamespace(), null);
                } else if (request.getMethod() == HttpMethod.DELETE) {
                    handleDelete(e, request);
                } else if (request.getMethod() == HttpMethod.OPTIONS) {
                    handleOptions(e,request);
                }
            } else {
                String remoteIpAddress = HttpUtil.getRemoteAddr(request, ((InetSocketAddress)e.getChannel().getRemoteAddress()).getAddress().getHostAddress());
                LOG.warn(String.format("Tried to access invalid resource - \"%s\" \"%s\"", remoteIpAddress, request.getHeader("User-Agent")));
                writeResponse(NOT_FOUND, e, null, null);
            }
        } else {
            writeResponse(INTERNAL_SERVER_ERROR, e, null, null);
        }
    }



    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        Throwable cause = e.getCause();
        HttpResponse response = null;
        if (cause instanceof ClosedChannelException) {
            // NOOP
        } else if (cause instanceof TooLongFrameException) {
            response = new DefaultHttpResponse(HTTP_1_1, REQUEST_ENTITY_TOO_LARGE);
        } else if (cause instanceof InvalidPathException) {
            response = new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        } else if (cause instanceof HttpSecurityException) {
            LOG.error(cause.getMessage());
            response = new DefaultHttpResponse(HTTP_1_1, FORBIDDEN);
        } else {
            LOG.error(cause.getMessage());
            response = new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }

        if (response != null) {
            ChannelFuture future = e.getChannel().write(response);
            future.addListener(ChannelFutureListener.CLOSE);
            updateResponseMetrics(null, response.getStatus().getCode());
        }
    }
}
