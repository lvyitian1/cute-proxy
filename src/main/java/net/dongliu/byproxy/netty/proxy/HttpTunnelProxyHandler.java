package net.dongliu.byproxy.netty.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import net.dongliu.byproxy.MessageListener;
import net.dongliu.byproxy.netty.NettyUtils;
import net.dongliu.byproxy.netty.SSLContextManager;
import net.dongliu.byproxy.utils.NetAddress;
import net.dongliu.byproxy.utils.Networks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.function.Supplier;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Handle http connect tunnel proxy request
 */
public class HttpTunnelProxyHandler extends SimpleChannelInboundHandler<HttpRequest>
        implements TunnelProxyHandlerTraits {
    private static final Logger logger = LoggerFactory.getLogger(HttpTunnelProxyHandler.class);

    @Nullable
    private final MessageListener messageListener;

    @Nullable
    private final SSLContextManager sslContextManager;
    @Nullable
    private final Supplier<ProxyHandler> proxyHandlerSupplier;

    public HttpTunnelProxyHandler(@Nullable MessageListener messageListener,
                                  @Nullable SSLContextManager sslContextManager,
                                  @Nullable Supplier<ProxyHandler> proxyHandlerSupplier) {
        this.messageListener = messageListener;
        this.sslContextManager = sslContextManager;
        this.proxyHandlerSupplier = proxyHandlerSupplier;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
        Promise<Channel> promise = ctx.executor().newPromise();
        Bootstrap bootstrap = initBootStrap(promise, ctx.channel().eventLoop());

        NetAddress address = Networks.parseAddress(request.uri());
        bootstrap.connect(address.getHost(), address.getPort()).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                ctx.channel().writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, BAD_GATEWAY));
                NettyUtils.closeOnFlush(ctx.channel());
            }
        });

        promise.addListener((FutureListener<Channel>) future -> {
            if (!future.isSuccess()) {
                ctx.channel().writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, BAD_GATEWAY));
                NettyUtils.closeOnFlush(ctx.channel());
                return;
            }

            Channel outboundChannel = future.getNow();
            ChannelFuture responseFuture = ctx.channel().writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, OK));
            responseFuture.addListener((ChannelFutureListener) channelFuture -> {
                ctx.pipeline().remove(HttpTunnelProxyHandler.this);
                ctx.pipeline().remove(HttpServerCodec.class);
                initTcpProxyHandlers(ctx, address, outboundChannel);
            });
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        logger.error("", e);
        NettyUtils.closeOnFlush(ctx.channel());
    }

    @Nullable
    @Override
    public MessageListener messageListener() {
        return messageListener;
    }

    @Nullable
    @Override
    public SSLContextManager sslContextManager() {
        return sslContextManager;
    }

    @Nullable
    @Override
    public Supplier<ProxyHandler> proxyHandlerSupplier() {
        return proxyHandlerSupplier;
    }
}