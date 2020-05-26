#### Bootstrap 
##### connect 开始出发调用链
###### channel 初始化和注册。
- 核心实现路径在 Bootstrap # doResolveAndConnect()
- 将 channel 注册到Selector中。
###### handler
- 实现在 ChannelInitializer

#### ServerBootstrap 
- 初始化 Channel、和注册到 Selector 流程一致
##### bind 触发调用链
- 实现 AbstractBootstrap # doBind()

##### 服务端Selector事件轮询 
- AbstractBootstrap # doBind0().

##### Netty 解决JDK 空轮训BUG
- NioEventLoop # run() 
- - unexpectedSelectorWakeup() 



### NioEventLoopGroup 初始化（构造器）
#### 最终实现在 MultithreadEventExecutorGroup 构造器
##### NioEventLoop与线程绑定
> NioEventLoopGroup 是线程池、NioEventLoop 执行线程。
- 绑定关系 SingleThreadEventExecutor # startThread()

##### NioEventLoop 线程启动
- AbstractBootstrap # bind() -> register()



### ChannelPipeline
#### 初始化
- `AbstractChannel 构造函数` -> DefaultChannelPipeline() 

#### outbound 事件传播
- Bootstrap # connect() -> doResolveAndConnect() -> doResolveAndConnect0() -> doConnect() -> 
    - DefaultChannelPipeline#connect() -> AbstractNioUnsafe#connect()  
 
#### Inbound 事件传播
- 在出站， AbstractNioUnsafe#connect() -》fulfillConnectPromise() 产生ChannelActive事件，开始入站流程。

### ByteBuf
#### ByteBufAllocator内存管理器
- AbstractByteBufAllocator# buffer()

##### 池化堆内，内存分配流程
- AbstractByteBufAllocator # newHeapBuffer()

##### DirectArena内存分配流程
- PooledByteBufAllocator # newDirectBuffer()

#### 内存分配重点
- PoolArena # allocate()

##### SubPage
- PoolChunk # allocateSubpage() 在子叶上进行内存分配。

### FastThreadLocal
- FastThreadLocal 构造方法。
### Recycler 对象回收
- recycle 回收，其实调用，`DefaultHandle` 的 recycle() 方法。
