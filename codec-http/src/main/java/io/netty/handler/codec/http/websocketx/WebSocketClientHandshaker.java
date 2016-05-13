/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.OneTimeTask;
import io.netty.util.internal.StringUtil;

import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.HashSet;
import java.util.Set;

import static io.netty.handler.codec.http.websocketx.WebSocketUtil.addIfNotNull;
import static io.netty.handler.codec.http.websocketx.WebSocketUtil.decoder;
import static io.netty.handler.codec.http.websocketx.WebSocketUtil.encoder;
import static io.netty.handler.codec.http.websocketx.WebSocketUtil.handler;

/**
 * Base class for web socket client handshake implementations
 */
public abstract class WebSocketClientHandshaker {
    private static final ClosedChannelException CLOSED_CHANNEL_EXCEPTION = new ClosedChannelException();

    static {
        CLOSED_CHANNEL_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

    private final URI uri;

    private final WebSocketVersion version;

    private volatile boolean handshakeComplete;

    private final String expectedSubprotocol;

    private volatile String actualSubprotocol;

    protected final HttpHeaders customHeaders;

    private final int maxFramePayloadLength;

    private volatile Set<? extends ChannelHandler> handlers;

    /**
     * Base constructor
     *
     * @param uri
     *            URL for web socket communications. e.g "ws://myhost.com/mypath". Subsequent web socket frames will be
     *            sent to this URL.
     * @param version
     *            Version of web socket specification to use to connect to the server
     * @param subprotocol
     *            Sub protocol request sent to the server.
     * @param customHeaders
     *            Map of custom headers to add to the client request
     * @param maxFramePayloadLength
     *            Maximum length of a frame's payload
     */
    protected WebSocketClientHandshaker(URI uri, WebSocketVersion version, String subprotocol,
                                        HttpHeaders customHeaders, int maxFramePayloadLength) {
        this.uri = uri;
        this.version = version;
        expectedSubprotocol = subprotocol;
        this.customHeaders = customHeaders;
        this.maxFramePayloadLength = maxFramePayloadLength;
    }

    /**
     * Returns the URI to the web socket. e.g. "ws://myhost.com/path"
     */
    public URI uri() {
        return uri;
    }

    /**
     * Version of the web socket specification that is being used
     */
    public WebSocketVersion version() {
        return version;
    }

    /**
     * Returns the max length for any frame's payload
     */
    public int maxFramePayloadLength() {
        return maxFramePayloadLength;
    }

    /**
     * Flag to indicate if the opening handshake is complete
     */
    public boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    private void setHandshakeComplete() {
        handshakeComplete = true;
    }

    /**
     * Returns the CSV of requested subprotocol(s) sent to the server as specified in the constructor
     */
    public String expectedSubprotocol() {
        return expectedSubprotocol;
    }

    /**
     * Returns the subprotocol response sent by the server. Only available after end of handshake.
     * Null if no subprotocol was requested or confirmed by the server.
     */
    public String actualSubprotocol() {
        return actualSubprotocol;
    }

    private void setActualSubprotocol(String actualSubprotocol) {
        this.actualSubprotocol = actualSubprotocol;
    }

    /**
     * Begins the opening handshake
     *
     * @param channel
     *            Channel
     * @deprecated use {@link #handshake(Channel, Set)}
     */
    @Deprecated
    public ChannelFuture handshake(Channel channel) {
        return handshake(channel, channel.newPromise());
    }

    /**
     * Begins the opening handshake
     *
     * @param channel
     *            Channel
     * @param handlers
     *            the HTTP specific {@link ChannelHandler}s to remove. This must contain either at least
     *            {@link HttpClientCodec} or {@link HttpResponseDecoder} and {@link HttpRequestEncoder}. Other handlers
     *            like {@link HttpObjectAggregator} and {@link HttpContentDecompressor} may be present as well.
     */
    public ChannelFuture handshake(Channel channel, Set<? extends ChannelHandler> handlers) {
        return handshake(channel, handlers, channel.newPromise());
    }

    /**
     * Begins the opening handshake
     *
     * @param channel
     *            Channel
     * @param promise
     *            the {@link ChannelPromise} to be notified when the opening handshake is sent
     * @deprecated use {@link #handshake(Channel, Set, ChannelPromise)}
     */
    @Deprecated
    public final ChannelFuture handshake(Channel channel, final ChannelPromise promise) {
        Set<ChannelHandler> handlers = new HashSet<ChannelHandler>();
        addIfNotNull(handlers, channel.pipeline(), HttpResponseDecoder.class);
        addIfNotNull(handlers, channel.pipeline(), HttpRequestEncoder.class);
        addIfNotNull(handlers, channel.pipeline(), HttpClientCodec.class);
        addIfNotNull(handlers, channel.pipeline(), HttpObjectAggregator.class);
        addIfNotNull(handlers, channel.pipeline(), HttpContentDecompressor.class);

        return handshake(channel, handlers, promise);
    }

    /**
     * Begins the opening handshake
     *
     * @param channel
     *            Channel
     * @param handlers
     *            the HTTP specific {@link ChannelHandler}s to remove. This must contain either at least
     *            {@link HttpClientCodec} or {@link HttpResponseDecoder} and {@link HttpRequestEncoder}. Other handlers
     *            like {@link HttpObjectAggregator} and {@link HttpContentDecompressor} may be present as well.
     * @param promise
     *            the {@link ChannelPromise} to be notified when the opening handshake is sent
     * @deprecated use {@link #handshake(Channel, Set, ChannelPromise)}
     */
    public final ChannelFuture handshake(Channel channel, final Set<? extends ChannelHandler> handlers,
                                         final ChannelPromise promise) {
        ObjectUtil.checkNotNull(channel, "channel");
        ObjectUtil.checkNotNull(promise, "promise");
        if (!isValidSet(ObjectUtil.checkNotNull(handlers, "handlers"))) {
            promise.setFailure(new IllegalStateException("ChannelPipeline does not contain " +
                    "a HttpResponseDecoder or HttpClientCodec"));
            return promise;
        }
        // Make a copy of the handlers as the user may modify it after passing in here.
        this.handlers = new HashSet<ChannelHandler>(handlers);

        FullHttpRequest request = newHandshakeRequest();

        channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    ChannelPipeline p = future.channel().pipeline();
                    ChannelHandler encoder = encoder(handlers, HttpRequestEncoder.class, HttpClientCodec.class);

                    final ChannelHandlerContext ctx = encoder == null ? null : p.context(encoder);
                    if (ctx == null) {
                        promise.setFailure(new IllegalStateException("ChannelPipeline does not contain " +
                                "a HttpRequestEncoder or HttpClientCodec"));
                        return;
                    }
                    p.addAfter(ctx.name(), "ws-encoder", newWebSocketEncoder());

                    promise.setSuccess();
                } else {
                    promise.setFailure(future.cause());
                }
            }
        });
        return promise;
    }

    /**
     * Returns a new {@link FullHttpRequest) which will be used for the handshake.
     */
    protected abstract FullHttpRequest newHandshakeRequest();

    /**
     * Validates and finishes the opening handshake initiated by {@link #handshake}}.
     *
     * @param channel
     *            Channel
     * @param response
     *            HTTP response containing the closing handshake details
     */
    public final void finishHandshake(Channel channel, FullHttpResponse response) {
        verify(response);

        // Verify the subprotocol that we received from the server.
        // This must be one of our expected subprotocols - or null/empty if we didn't want to speak a subprotocol
        String receivedProtocol = response.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        receivedProtocol = receivedProtocol != null ? receivedProtocol.trim() : null;
        String expectedProtocol = expectedSubprotocol != null ? expectedSubprotocol : "";
        boolean protocolValid = false;

        if (expectedProtocol.isEmpty() && receivedProtocol == null) {
            // No subprotocol required and none received
            protocolValid = true;
            setActualSubprotocol(expectedSubprotocol); // null or "" - we echo what the user requested
        } else if (!expectedProtocol.isEmpty() && receivedProtocol != null && !receivedProtocol.isEmpty()) {
            // We require a subprotocol and received one -> verify it
            for (String protocol : StringUtil.split(expectedSubprotocol, ',')) {
                if (protocol.trim().equals(receivedProtocol)) {
                    protocolValid = true;
                    setActualSubprotocol(receivedProtocol);
                    break;
                }
            }
        } // else mixed cases - which are all errors

        if (!protocolValid) {
            throw new WebSocketHandshakeException(String.format(
                    "Invalid subprotocol. Actual: %s. Expected one of: %s",
                    receivedProtocol, expectedSubprotocol));
        }

        setHandshakeComplete();

        Set<? extends ChannelHandler> handlers = this.handlers;
        assert handlers != null;

        this.handlers = null;

        final ChannelPipeline p = channel.pipeline();
        // Remove decompressor from pipeline if its in use
        HttpContentDecompressor decompressor = handler(handlers, HttpContentDecompressor.class);
        if (decompressor != null) {
            p.remove(decompressor);
        }

        // Remove aggregator if present before
        HttpObjectAggregator aggregator = handler(handlers, HttpObjectAggregator.class);
        if (aggregator != null) {
            p.remove(aggregator);
        }

        final ChannelHandler decoder = decoder(handlers, HttpResponseDecoder.class, HttpClientCodec.class);
        ChannelHandlerContext ctx = p.context(decoder);
        if (ctx == null) {
            throw new IllegalStateException("ChannelPipeline does not contain " +
                    "a HttpRequestEncoder or HttpClientCodec");
        }
        if (decoder instanceof HttpClientCodec) {
            final HttpClientCodec codec = (HttpClientCodec) decoder;
            // Remove the encoder part of the codec as the user may start writing frames after this method returns.
            codec.removeOutboundHandler();

            p.addAfter(ctx.name(), "ws-decoder", newWebsocketDecoder());

            // Delay the removal of the decoder so the user can setup the pipeline if needed to handle
            // WebSocketFrame messages.
            // See https://github.com/netty/netty/issues/4533
            channel.eventLoop().execute(new OneTimeTask() {
                @Override
                public void run() {
                    p.remove(codec);
                }
            });
        } else {
            ChannelHandler encoder = encoder(handlers, HttpRequestEncoder.class, HttpClientCodec.class);
            if (p.context(encoder) != null) {
                // Remove the encoder part of the codec as the user may start writing frames after this method returns.
                p.remove(encoder);
            }
            final ChannelHandlerContext context = ctx;
            p.addAfter(context.name(), "ws-decoder", newWebsocketDecoder());

            // Delay the removal of the decoder so the user can setup the pipeline if needed to handle
            // WebSocketFrame messages.
            // See https://github.com/netty/netty/issues/4533
            channel.eventLoop().execute(new OneTimeTask() {
                @Override
                public void run() {
                    p.remove(decoder);
                }
            });
        }
    }

    /**
     * Process the opening handshake initiated by {@link #handshake}}.
     *
     * @param channel
     *            Channel
     * @param response
     *            HTTP response containing the closing handshake details
     * @return future
     *            the {@link ChannelFuture} which is notified once the handshake completes.
     */
    public final ChannelFuture processHandshake(final Channel channel, HttpResponse response) {
        return processHandshake(channel, response, channel.newPromise());
    }

    /**
     * Process the opening handshake initiated by {@link #handshake}}.
     *
     * @param channel
     *            Channel
     * @param response
     *            HTTP response containing the closing handshake details
     * @param promise
     *            the {@link ChannelPromise} to notify once the handshake completes.
     * @return future
     *            the {@link ChannelFuture} which is notified once the handshake completes.
     */
    public final ChannelFuture processHandshake(final Channel channel, HttpResponse response,
                                                final ChannelPromise promise) {
        if (response instanceof FullHttpResponse) {
            try {
                finishHandshake(channel, (FullHttpResponse) response);
                promise.setSuccess();
            } catch (Throwable cause) {
                promise.setFailure(cause);
            }
        } else {
            Set<? extends ChannelHandler> handlers = this.handlers;
            assert handlers != null;

            ChannelPipeline p = channel.pipeline();
            ChannelHandler decoder = decoder(handlers, HttpResponseDecoder.class, HttpClientCodec.class);
            ChannelHandlerContext ctx = p.context(decoder);
            if (ctx == null) {
                return promise.setFailure(new IllegalStateException("ChannelPipeline does not contain " +
                        "a HttpResponseDecoder or HttpClientCodec"));
            }
            // Add aggregator and ensure we feed the HttpResponse so it is aggregated. A limit of 8192 should be more
            // then enough for the websockets handshake payload.
            //
            // TODO: Make handshake work without HttpObjectAggregator at all.
            String aggregatorName = "httpAggregator";
            p.addAfter(ctx.name(), aggregatorName, new HttpObjectAggregator(8192));
            p.addAfter(aggregatorName, "handshaker", new SimpleChannelInboundHandler<FullHttpResponse>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) throws Exception {
                    // Remove ourself and do the actual handshake
                    ctx.pipeline().remove(this);
                    try {
                        finishHandshake(channel, msg);
                        promise.setSuccess();
                    } catch (Throwable cause) {
                        promise.setFailure(cause);
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                    // Remove ourself and fail the handshake promise.
                    ctx.pipeline().remove(this);
                    promise.setFailure(cause);
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    // Fail promise if Channel was closed
                    promise.tryFailure(CLOSED_CHANNEL_EXCEPTION);
                    ctx.fireChannelInactive();
                }
            });
            try {
                ctx.fireChannelRead(ReferenceCountUtil.retain(response));
            } catch (Throwable cause) {
                promise.setFailure(cause);
            }
        }
        return promise;
    }

    /**
     * Verfiy the {@link FullHttpResponse} and throws a {@link WebSocketHandshakeException} if something is wrong.
     */
    protected abstract void verify(FullHttpResponse response);

    /**
     * Returns the decoder to use after handshake is complete.
     */
    protected abstract WebSocketFrameDecoder newWebsocketDecoder();

    /**
     * Returns the encoder to use after the handshake is complete.
     */
    protected abstract WebSocketFrameEncoder newWebSocketEncoder();

    /**
     * Performs the closing handshake
     *
     * @param channel
     *            Channel
     * @param frame
     *            Closing Frame that was received
     */
    public ChannelFuture close(Channel channel, CloseWebSocketFrame frame) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        return close(channel, frame, channel.newPromise());
    }

    /**
     * Performs the closing handshake
     *
     * @param channel
     *            Channel
     * @param frame
     *            Closing Frame that was received
     * @param promise
     *            the {@link ChannelPromise} to be notified when the closing handshake is done
     */
    public ChannelFuture close(Channel channel, CloseWebSocketFrame frame, ChannelPromise promise) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        return channel.writeAndFlush(frame, promise);
    }

    /**
     * Return the constructed raw path for the give {@link URI}.
     */
    static String rawPath(URI wsURL) {
        String path = wsURL.getRawPath();
        String query = wsURL.getQuery();
        if (query != null && !query.isEmpty()) {
            path = path + '?' + query;
        }

        return path == null || path.isEmpty() ? "/" : path;
    }

    private static boolean isValidSet(Set<? extends ChannelHandler> handlers) {
        return handler(handlers, HttpClientCodec.class) != null
                || (handler(handlers, HttpRequestEncoder.class) != null
                && handler(handlers, HttpResponseDecoder.class) != null);
    }
}
