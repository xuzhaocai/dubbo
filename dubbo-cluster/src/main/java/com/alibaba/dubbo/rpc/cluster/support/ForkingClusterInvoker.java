/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 *  并发调用特定数量的调用器，通常用于要求实时操作，但需要浪费更多的服务资源。
 *
 *
 *  并发请求，获取那个最快响应回来的
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link com.alibaba.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link com.alibaba.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            // 检查invokers 是否为空
            checkInvokers(invokers, invocation);
            // 记录选中的invoker们
            final List<Invoker<T>> selected;
            // forks 缺省2
            final int forks = getUrl().getParameter(Constants.FORKS_KEY, Constants.DEFAULT_FORKS);

            //timeout 缺省1s
            final int timeout = getUrl().getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
            // forks 小于等于0  或者 forks 大于的 invoker的列表
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;//使用全部的invokers
            } else {
                selected = new ArrayList<Invoker<T>>();
                for (int i = 0; i < forks; i++) {// 选择几个invoker
                    // TODO. Add some comment here, refer chinese version for more details.
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    if (!selected.contains(invoker)) {//Avoid add the same invoker several times.
                        selected.add(invoker);
                    }
                }
            }
            ///---------------------------------------------------------
            // 将选中的invoker们 放到context中
            RpcContext.getContext().setInvokers((List) selected);
            final AtomicInteger count = new AtomicInteger();// 计数器
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<Object>();// 队列
            for (final Invoker<T> invoker : selected) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Result result = invoker.invoke(invocation);
                            ref.offer(result);// 将结果放到队列中
                        } catch (Throwable e) {

                            // 计数器+1
                            int value = count.incrementAndGet();

                            // 如果 失败次数 大于等于 选中的invoker的时候
                            if (value >= selected.size()) {
                                ref.offer(e);// 将异常放到队列中
                            }
                        }
                    }
                });
            }
            //----------------------------------------------------------------------------
            try {

                // 在超时范围内获取第一个结果
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                if (ret instanceof Throwable) {
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                return (Result) ret;
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            // clear attachments which is binding to current thread. 清理当前线程context中的参数值
            RpcContext.getContext().clearAttachments();
        }
    }
}
