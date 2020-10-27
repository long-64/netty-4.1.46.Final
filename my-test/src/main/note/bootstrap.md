### Bootstrap
- 客户端初始化入口 `connect()` 方法
    - `doResolveAndConnect0`
        - `doConnect` 核心实现

> childGroup 的 register() 方法，就是将 workerGroup 中某个 EventLoop 和 NioSocketChannel 进行关联。

##### `initAndRegister` 初始化Channel -> `NioSocketChannel 对象`
- 
- 并注册Channel。

#### `NioEventLoopGroup`
- 入口在构造器。



### `ServerBootstrap`
- 服务启动代码
    - `AbstractBootstrap#bind()`
