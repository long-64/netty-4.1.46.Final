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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 *
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    // The order in which child ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);
    private volatile EventLoopGroup childGroup;
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        /**
         * 主从模型。绑定 Reactor 线程池
         * bossGroup、主要用于处理TCP连接请求
         * workGroup、主要用于IO读写以及 task 执行。
         *
         * bossGroup = parentGroup，在 {@link #doBind(SocketAddress)} 初始化和注册Channel 使用
         * workGroup = {@link #init(Channel)}
         *
         */
        super.group(parentGroup);
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");
        synchronized (childOptions) {
            if (value == null) {
                childOptions.remove(childOption);
            } else {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    /**
     *
     *  channel： NioSocketChannel 实例。
     *
     * @param channel
     */
    @Override
    void init(Channel channel) {
        // 设置 channel 参数。
        setChannelOptions(channel, newOptionsArray(), logger);
        // 设置 channel 附加属性
        setAttributes(channel, attrs0().entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY));
        // 获取负责网络事件的职责链，用于管理和执行，channelHandler
        ChannelPipeline p = channel.pipeline();

        /**
         * workGroup，处理I/O相关操作的线程组, 在构造对象时传入的 `childGroup`
         */
        final EventLoopGroup currentChildGroup = childGroup;
        //创建ServerBootstrap时设置的childHandler
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions;
        synchronized (childOptions) {
            //创建ServerBootstrap时设置的socket属性childOptions
            currentChildOptions = childOptions.entrySet().toArray(EMPTY_OPTION_ARRAY);
        }
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = childAttrs.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY);

        /**
         * 处理 workerGroup
         * pipeline 中添加一个 {@link ServerBootstrapAcceptor} 的handler
         *  中的 childGroup 也就是 workerGroup
         *
         *  {@link ServerBootstrapAcceptor#channelRead(ChannelHandlerContext, Object)} 这个方法是在 Client 连接到 Server 是，Java 底层 Nio 的
         *      ServerSocketChannel 就会有一个 SelectionKey_OP_ACCEPT 事件就绪，接着就会调用 {@link io.netty.channel.socket.nio.NioServerSocketChannel#doReadMessages(List)}
         *
         */
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) {
                final ChannelPipeline pipeline = ch.pipeline();
                //将创建ServerBootstrap时设置的handler添加到NioServerSocketChannel的责任链上
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    pipeline.addLast(handler);
                }

                //封装成task任务交由channel对应的 `eventLoop线程` 来执行，防止并发操作 `channel`
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        /**
                         * 添加ServerBootstrapAcceptor，主要用于接收TCP连接后初始化并注册NioSocketChannel到workGroup
                         *  {@link ServerBootstrapAcceptor
                         *
                         *   疑问: `ServerBootstrapAcceptor` 注册过程为什么需要封装成异步 task.
                         *       初始化时，还没有将 channel，注册到 Selector 对象上，所以还无法注册 Accept 事件到 Selector 上。所以事先添加 ChannelInitializer 处理器。
                         *       等待 Channel 注册完成后，再向 Pipeline 中添加 ServerBootstrapAcceptor 处理器。
                         */
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {
        //workGroup，处理I/O相关操作的线程组
        private final EventLoopGroup childGroup;
        //创建ServerBootstrap时设置的childHandler
        private final ChannelHandler childHandler;
        //创建ServerBootstrap时设置的socket属性childOptions
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        //创建ServerBootstrap时设置的附加参数childAttrs
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        private final Runnable enableAutoReadTask;

        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        /**
         *
         * 主要用于，接收TCP 连接后，初始化并注册 NioSocketChannel 到 workGroup
         *
         * 调用时机
         *      其实当一个Client连接到Server时，Java底层NIO的ServerSocketChannel就会有一个SelectionKey.OP_ACCEPT的事件就绪，
         *      接着就会调用 {@link io.netty.channel.socket.nio.NioServerSocketChannel#doReadMessages(List)}
         *
         *      在doReadMessages()方法中，通过调用javaChannel().accept()方法获取客户端新连接的SocketChannel对象，
         *      紧接着实例化一个NioSocketChannel，并且传入NioServerSocketChannel对象（即this）。
         *      由此可知，我们创建的 {@link io.netty.channel.socket.nio.NioSocketChannel} 的父类Channel就是 NioServerSocketChannel实例。
         *      接下来利用Netty的ChannelPipeline机制，将读取事件逐级发送到各个Handler中，
         */
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // msg 就是 NIOSocketChannel。
            final Channel child = (Channel) msg;
            //为NioSocketChannel的责任链上添加childHandler

            child.pipeline().addLast(childHandler);
            //为NioSocketChannel添加socket属性Option
            setChannelOptions(child, childOptions, logger);
            //为NioSocketChannel添加附加参数attr
            setAttributes(child, childAttrs);

            try {

                /**
                 * 将child注册到workGroup线程组上并添加 Listener 用于处理注册失败后的关闭操作
                 */
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        /**
         *  若ServerSocketChannel在accept子连接时抛出异常，若ServerSocketChannel的autoRead为true，
         *  则设置其为false，即不允许自动接收客户端连接，并延迟1s后再设置其为true，使其允许自动接收客户端连接；
         * @param ctx
         * @param cause
         * @throws Exception
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                //定时task需要提交给channel对应的eventLoop线程处理
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
