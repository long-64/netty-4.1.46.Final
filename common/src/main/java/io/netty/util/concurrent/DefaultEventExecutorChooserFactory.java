/*
 * Copyright 2016 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation which uses simple round-robin to choose next {@link EventExecutor}.
 */
@UnstableApi
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    private DefaultEventExecutorChooserFactory() { }

    @SuppressWarnings("unchecked")
    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        /**
         * 判断 是否是2的平方。{@link #isPowerOfTwo(int)}
         * 这两个类，都实现 {@link EventExecutorChooser#next()} 方法
         *   主要功能是，数组索引循环移位。
         */
        if (isPowerOfTwo(executors.length)) {

            /**
             * 2 的平方。
             */
            return new PowerOfTwoEventExecutorChooser(executors);
        } else {
            return new GenericEventExecutorChooser(executors);
        }
    }

    /**
     *  CPU直接对二进制位操作的速度是最快的。
     *   回忆负数表示，采用补码表示负数的目的就是为了硬件操作方便，可以把减法转换成加法来运算。
     *   对于正数，它的反码、补码都为本身
     *   对于负数，它的反码就是除去符号位取反，如果加1就得到了它的补码。
     *   而int变量val直接和其自身负数-val做逻辑与运算，如果val是2的幂次，最后还是val，可以简单验证
     *
     * @param val
     * @return
     */
    private static boolean isPowerOfTwo(int val) {
        return (val & -val) == val;
    }

    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        /**
         * 每次索引自增后 与数组长度取模。
         *
         *    在计算机底层：& 比 % 效率要高。
         *
         *   既 a%b, 当且仅当 b 是 2的幂次，a%b 等价于 a &(b-1)
         */
        @Override
        public EventExecutor next() {
            return executors[idx.getAndIncrement() & executors.length - 1];
        }
    }

    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            return executors[Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}
