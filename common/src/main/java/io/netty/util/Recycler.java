/*
 * Copyright 2013 The Netty Project
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

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 *
 *            【 对象回收站 】`对象池`
 *
 *
 *   重要组件
 *      1、Stack
 *         1.1、availableSharedCapacity 存在多线程同时修改。使用 `AtomicInteger` 进行修饰
 *
 *      2、WeakOrderQueue 用于存储，其他线程回收，回收到当前线程所分配的对象，并且在合适的时机，Stack 会从异线程的 WeakOrderQueue 中收割对象。
 *         2.1、例如: Thread_B 回收 Thread_A 创建的对象，就会放在 Thread_A 的 `WeakOrderQueue`
 *
 *      3、Link: 每个 WeakOrderQueue 中都包含一个 Link 链表，回收对象都会被存在 Link 链表中的节点上，每个 Link 节点默认存储 16 个对象，当每个 Link 节点存储满了会创建新的 Link 节点放入链表尾部。
 *
 *      4、DefaultHandle 实例中保存了实际回收的对象，Stack 和 WeakOrderQueue 都使用 DefaultHandle 存储回收的对象。
 *          4.1、Stack 中 包含 elements 数组，保存 `DefaultHandler` 实例。
 *          4.2、
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int interval;
    private final int maxDelayedQueuesPerThread;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        // 每个线程中 Stack 中最多回收元素的个数。
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        // interval = 7
        interval = safeFindNextPositivePowerOfTwo(ratio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            /**
             * maxCapacityPerThread = 4* 1024
             * maxSharedCapacityFactor = 2
             * maxDelayedQueuesPerThread = CPU 核数 * 2
             */
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    /**
     * GET 流程
     * 1、从 FastThreadLocal。获取到对应 Stack。
     * 2、从 Stack 找  WeakOrderQueue -》 Link -》 DefaultHandle。
     *
     *
     *
     * Stack 中包含多个 WeakOrderQueue 链表的形式。
     *      WeakOrderQueue 中包含多个
     *          Link 包含 16个 DefaultHandle 双向链表的形式。
     *
     */
    @SuppressWarnings("unchecked")
    public final T get() {

        /**
         * maxCapacityPerThread 代表 Stack 最多能缓存多少个对象。
         *  NOOP_HANDLE 是一个 Handler 空实现。
         */
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        /**
         * 1、从 FastThreadLocal 中获取当前线程 Stack。
         */
        Stack<T> stack = threadLocal.get();
        /**
         *  2、从 {@link Stack#pop()} 中一个 DefaultHandle。
         *    第一次null，创建一个 `DefaultHandle`  {@link Stack#newHandle()}
         */
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            handle = stack.newHandle();

            // 创建的对象并保存到 DefaultHandle
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    private static final class DefaultHandle<T> implements Handle<T> {
        int lastRecycledId;
        int recycleId;

        boolean hasBeenRecycled;

        Stack<?> stack;
        Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        /**
         * 回收对象。流程
         * 1、获取 Stack。
         *    1、创建Stack 和当前是否是一个线程。
         *      2、
         *
         */
        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }
            /**
             * {@link Stack#push(DefaultHandle)}
             */
            stack.push(this);
        }
    }

    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.

        /**
         * 1、WeakOrderQueue 多个Link
         * 2、一个Link（存放16个 DefaultHandle）
         *
         *
         */
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];
            // 读指针。
            int readIndex;
            // 指向下一个 Link
            Link next;
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        private static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                while (head != null) {
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            void relink(Link link) {
                reclaimSpace(LINK_CAPACITY);
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             */
            Link newLink() {
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            /**
             * 判断是否还能缓存对象。
             * @param availableSharedCapacity
             * @return
             */
            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (;;) {

                    /**
                     * availableSharedCapacity 表示线程1的 Stack 运行外部线程给其缓存多少个对象。之前分析过 16384.
                     */
                    int available = availableSharedCapacity.get();
                    if (available < LINK_CAPACITY) {
                        return false;
                    }

                    // 通过 CAS操作，16384-16 表示 Stack 可以给其他线程缓存的对象数。
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final int id = ID_GENERATOR.getAndIncrement();
        private final int interval;
        private int handleRecycleCount;

        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        /**
         * head 头节点
         * tail 尾节点
         */
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super(thread);
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.interval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space

            /**
             *  {@link Head#reserveSpaceForLink(AtomicInteger)}
             */
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }

            /**
             * 创建 WeakOrderQueue {@link WeakOrderQueue#WeakOrderQueue(Stack, Thread)}
             */
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);

            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink();
            this.next = null;
        }

        void add(DefaultHandle<?> handle) {
            /**
             * 表示Handle 上次回收的ID。
             * ID：表示 weakOrderQueue 的ID。
             */
            handle.lastRecycledId = id;

            // While we also enforce the recycling ratio one we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control if the Stack
            if (handleRecycleCount < interval) {
                handleRecycleCount++;
                // Drop the item to prevent recycling to aggressive.
                return;
            }
            handleRecycleCount = 0;

            // 表示获取当前WeakOrderQueue 中指向最后一个 Link 指针，也就是尾指针。
            Link tail = this.tail;
            int writeIndex;

            // Link 中 元素等于 16个，需要进行扩容。(如果链表尾部 Link 已经写满，那么再新建一个 Link 追加到链表尾部)
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                /**
                 * 新创建一个 Link。
                 * 并将尾节点指向新的 Link。
                 */
                Link link = head.newLink();
                if (link == null) {
                    // Drop it.
                    return;
                }
                // 将尾节点指向新的 Link。
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = link;
                // 获取 tail 写指针。
                writeIndex = tail.get();
            }
            // 根据指针，写入 `DefaultHandle`
            tail.elements[writeIndex] = handle;

            /*
             * 设置为null, 如果 Stack 不再使用，期待被GC 回收，发现 handle 中还持有 Stack 的引用，那么就无法被GC 回收，从而造成内存泄露
             */
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // 下一个次，将从 writeIndex + 1 位置往里写。
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head.link;
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                head = head.next;
                this.head.relink(head);
            }

            final int srcStart = head.readIndex;
            int srcEnd = head.get();
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;

            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;

                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.relink(head.next);
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.

        // 所属的 Recycler
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).

        //  所属线程的弱引用
        final WeakReference<Thread> threadRef;

        /**
         * 表示在线程A中 创建的对象，在其他线程中的缓存最大个数。
         *
         * 异线程回收对象时，其他线程能保存的被回收对象的最大个数
         *
         *  `AtomicInteger` 是因为，多线程同时操作，`availableSharedCapacity`.
         */
        final AtomicInteger availableSharedCapacity;

        // WeakOrderQueue最大个数
        private final int maxDelayedQueues;

        // 对象池的最大大小，默认最大为 4k
        private final int maxCapacity;
        private final int interval;

        // 存储缓存数据的数组
        DefaultHandle<?>[] elements;

        // 缓存的 DefaultHandle 对象个数
        int size;
        private int handleRecycleCount;

        /**
         * cursor 寻找当前的WeakOrderQueue、【 其他线程链表指针 】
         * prev 是 cursor 的上一个节点。
         */
        private WeakOrderQueue cursor, prev;

        /**
         * head 指向最近创建与Stack 关联的 WeakOrderQueue.
         */
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues) {
            // 表示 Recycler 对象自身
            this.parent = parent;
            // 当前stack 绑定那个线程
            threadRef = new WeakReference<Thread>(thread);
            // 当前stack 最大容量，最多能盛放多少个元素
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));

            // stack 中存储的对象，类型为 DefaultHandle. 可以被外部对象引用，从而实现回收。
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.

            /**
             * 一个线程创建的对象，有可能会被另一个线程释放，而另一个线程释放的对象不会放在当前线程Stack中。
             * 而是存放在一个叫做 WeakOrderQueue 的数据结构中。里面存放一个个 DefaultHandle
             */
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {

            // 当前 Stack 对象数。
            int size = this.size;

            /*
             * Stack 的 elements 数组中有可用的对象实例, 则直接弹出。如果数组中，没有可用实例。则调用 `scavenge()`
             */
            if (size == 0) {
                /**
                 * 就尝试从其他线程回收的对象中转移一些到 elements 数组当中。{@link #scavenge()}
                 *
                 *  线程A 创建的 Stack, 被 线程B回收的情况。
                 */
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
                if (size <= 0) {
                    // double check, avoid races
                    return null;
                }
            }

            /**
             * 通过 size 数组下标，将 Handler 取出，并设置为 null。(将实例从栈顶，弹出)
             */
            size --;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            this.size = size;

            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        private boolean scavenge() {
            // continue an existing scavenge, if any

            /**
             * 尝试从 WeakOrderQueue 中转移对象实例到 Stack 中 {@link #scavengeSome()}
             */
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor

            // 如果迁移失败，就会重置 cursor 指针到 head 节点
            prev = null;
            cursor = head;
            return false;
        }

        /**
         * 表示，如果已经回收了对象，则直接返回；
         * 如果没有回收到对象，则将 prev, cursor 两个指针进行重置。
         * @return
         *
         *  1、首先获取 cursor 指针，cursor 指针代表要回收的 WeakOrderQueue。
         *  2、如果 cursor 为空，则让其指向头节点。如果头节点也为空，说明当前 Stack 没有与关联的 WeakOrderQueue。
         */
        private boolean scavengeSome() {
            WeakOrderQueue prev;
            // cursor 代表要回收的 WeakOrderQueue.
            WeakOrderQueue cursor = this.cursor;

            // 如果 cursor 指针为 null, 则是第一次从 WeakorderQueueu 链表中获取对象
            if (cursor == null) {
                prev = null;

                // 则指向头节点。
                cursor = head;

                // 如果头节点为空，说明当前 `Stack ` 没有与关联的 WeakOrderQueue.
                if (cursor == null) {
                    return false;
                }
            } else {
                // cursor 上一个节点。
                prev = this.prev;
            }

            boolean success = false;

            // 不断循环从 WeakOrderQueue 链表中找到一个可用的对象实例
            do {
                // 尝试迁移 WeakOrderQueue 中部分对象实例到 Stack 中。
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.getNext();
                if (cursor.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.

                    // 如果已退出的线程还有数据
                    if (cursor.hasFinalData()) {
                        // 表单当前 WeakOrderQueue 中还存在数据。
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    // 将已退出的线程从 WeakOrderQueue 链表中移除
                    if (prev != null) {
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        cursor.reclaimAllSpaceAndUnlink();
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                // 将 cursor 指针指向下一个 WeakOrderQueue
                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        /**
         * 当前线程和创建Stack时候保存的线程同一线程。
         * 1、同线程回收对象
         *      pushNow()
         *
         * 2、不同线程回收对象
         *      pushLater()
         * @param item
         */
        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.

                /**
                 * 同线程回收 {@link #pushNow(DefaultHandle)}
                 */
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.

                /**
                 * 不同线程回收 {@link #pushLater(DefaultHandle, Thread)}
                 */
                pushLater(item, currentThread);
            }
        }

        /**
         * 同线程回收。
         * @param item
         */
        private void pushNow(DefaultHandle<?> item) {

            // 防止被多次回收
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            // OWN_THREAD_ID 在每个recycle 中都是唯一固定的。
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            /**
             *  {@link #dropHandle(DefaultHandle)}
             *  // 1. 超出最大容量 2. 控制回收速率
             */
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            // 如果Size 大小等于 Stack中的数组Elements 的大小，则将数组Elements 进行扩容。
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }
            /**
             * 【 TODO 】 最后Size，通过数组下标的方式将当前 Handle 设置到Elements 的元素中。并将Size 进行自增。
             */
            elements[size] = item;
            this.size = size + 1;
        }

        /**
         * 异线程回收对象。不是放在 Stack 中而是，而是在 WeakOrderQueue
         * 1、异线程
         *   A创建的、B回收对象。
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            /**
             * DELAYED_RECYCLED, 是一个 FastThreadLocal 对象，
             *  {@link DELAYED_RECYCLED} initialValue() 方法，创建一个 WeakHashMap<{@link Stack}, {@link WeakOrderQueue}></>
             *  1、说明不同 Stack 对应不同的 WeakOrderQueue。
             *
             *  这里维护 Stack 和 WeakOrderQueue 对应关系。（存放着当前线程帮助其他线程回收对象的映射关系）
             *  假如：
             *      item 是 Thread_A 分配的对象，当前线程是 Thread_B, 那么 Thread_B 帮助 Thread_A 回收 item。
             *      那么 `DELAYED_RECYCLED` 放入的 key 是 Stack_A, 然后从 delayedRecycled 中取出 Stack_A 对应到的 WeakOrderQueue.
             *
             *
             *
             *  2、通过 Stack 获取到对应 `WeakOrderQueue`
             */
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();

            // 取出对象绑定 Stack 对应的 WeakOrderQueue.
            WeakOrderQueue queue = delayedRecycled.get(this);
            // 说明，线程B，没有回收过线程A的对象。
            if (queue == null) {
                /**
                 * delayedRecycled.size() 表示当前线程回收其他创建对象线程的个数。也就是有几个其他线程在当前线程回收对象。
                 * `maxDelayedQueues` 表示最多能回收多个对象（每个线程最多帮助2倍，CPU 核数的线程回收线程）。如果超过这个值。表示当前线程不能再回收其他线程对象了。
                 *
                 *  `WeakOrderQueue.DUMMY` 设置不可用状态。
                 */
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                /**
                 * 创建 WeakOrderQueue {@link #newWeakOrderQueue(Thread)}
                 *
                 */
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // drop object
                    return;
                }
                // 将Stack、WeakOrderQueue 进行关联。
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }
            /**
             * 将创建的 WeakOrderQueue 添加Handle. {@link WeakOrderQueue#add(DefaultHandle)}
             */
            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            // 表示当前对象之前是没有被回收过，
            if (!handle.hasBeenRecycled) {
                /**
                 * handleRecycleCount 表示当前位置 Stack 回收多少次对象。
                 *   回收多少次，不代表回收多少对象。不是每次回收都会被成功保存在Stack。
                 */
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                handleRecycleCount = 0;
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
