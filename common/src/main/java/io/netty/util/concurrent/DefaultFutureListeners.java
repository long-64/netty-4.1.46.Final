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

import java.util.Arrays;


/**
 *  默认监听器
 */
final class DefaultFutureListeners {

    private GenericFutureListener<? extends Future<?>>[] listeners;
    private int size;
    private int progressiveSize; // the number of progressive listeners

    /**
     * 这个构造相对特别，是为了让Promise中的listeners（Object类型）实例由单个GenericFutureListener实例转换为DefaultFutureListeners类型
     * @param first
     * @param second
     */
    @SuppressWarnings("unchecked")
    DefaultFutureListeners(
            GenericFutureListener<? extends Future<?>> first, GenericFutureListener<? extends Future<?>> second) {
        listeners = new GenericFutureListener[2];
        listeners[0] = first;
        listeners[1] = second;
        size = 2;
        if (first instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
        if (second instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    public void add(GenericFutureListener<? extends Future<?>> l) {
        GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        final int size = this.size;

        // 每次扩容是原来的 2 倍。
        if (size == listeners.length) {
            this.listeners = listeners = Arrays.copyOf(listeners, size << 1);
        }

        // 把当前的GenericFutureListener加入数组中
        listeners[size] = l;

        // 监听器总数量加1
        this.size = size + 1;

        // 如果为GenericProgressiveFutureListener，则带进度指示的监听器总数量加1
        if (l instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    public void remove(GenericFutureListener<? extends Future<?>> l) {
        final GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        int size = this.size;
        for (int i = 0; i < size; i ++) {
            if (listeners[i] == l) {

                // 计算需要需要移动的监听器的下标
                int listenersToMove = size - i - 1;
                if (listenersToMove > 0) {
                    System.arraycopy(listeners, i + 1, listeners, i, listenersToMove);
                }

                // 当前监听器总量的最后一个位置设置为null，数量减1
                listeners[-- size] = null;
                this.size = size;

                // 如果监听器是GenericProgressiveFutureListener，则带进度指示的监听器总数量减1
                if (l instanceof GenericProgressiveFutureListener) {
                    progressiveSize --;
                }
                return;
            }
        }
    }

    public GenericFutureListener<? extends Future<?>>[] listeners() {
        return listeners;
    }

    /**
     * 返回监听者的数量。
     * @return
     */
    public int size() {
        return size;
    }

    /**
     * 返回带进度指示的监听器总数量
     * @return
     */
    public int progressiveSize() {
        return progressiveSize;
    }
}
