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
package io.netty.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract {@link Future} implementation which does not allow for cancellation.
 *
 * @param <V>
 */
public abstract class AbstractFuture<V> implements Future<V> {

    /**
     * 永久阻塞等待获取结果的方法
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @Override
    public V get() throws InterruptedException, ExecutionException {

        /**
         * 调用响应中断的永久等待方法进行阻塞
         *  例如 {@link DefaultPromise#await()}
         */
        await();

        // 从永久阻塞中唤醒后，先判断Future是否执行异常
        Throwable cause = cause();
        if (cause == null) {

            /**
             * 异常为空说明执行成功，调用getNow()方法返回结果
             *
             *  例如: {@link DefaultPromise#getNow()}
             */
            return getNow();
        }

        // 异常为空不为空，这里区分特定的取消异常则转换为CancellationException抛出
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }

        // 非取消异常的其他所有异常都被包装为执行异常ExecutionException抛出
        throw new ExecutionException(cause);
    }

    /**
     * 带超时阻塞等待获取结果的方法
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {

        /**
         * 调用响应中断的带超时时限等待方法进行阻塞
         *  例如 {@link DefaultPromise#await(long, TimeUnit)}
         */
        if (await(timeout, unit)) {
            Throwable cause = cause();
            if (cause == null) {
                return getNow();
            }

            // 异常为空不为空，这里区分特定的取消异常则转换为CancellationException抛出
            if (cause instanceof CancellationException) {
                throw (CancellationException) cause;
            }
            throw new ExecutionException(cause);
        }

        // 非取消异常的其他所有异常都被包装为执行异常ExecutionException抛出
        throw new TimeoutException();
    }
}
