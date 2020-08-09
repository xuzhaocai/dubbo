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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcStatus;

/**
 * LimitInvokerFilter
 * consumer actives
 */
@Activate(group = Constants.CONSUMER, value = Constants.ACTIVES_KEY)
public class ActiveLimitFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获取url
        URL url = invoker.getUrl();
        // 获取调用方法名
        String methodName = invocation.getMethodName();
        // 获取方法 actives 属性值 缺省是0 ，这actives 就是"每服务消费者每服务每方法最大并发调用数"
        int max = invoker.getUrl().getMethodParameter(methodName, Constants.ACTIVES_KEY, 0);
        // 获取对应url 对应method的一个RpcStatus
        RpcStatus count = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
        if (max > 0) {
            // 获取timeout
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, 0);
            // 开始时间
            long start = System.currentTimeMillis();
            //剩余时间= timeout
            long remain = timeout;

            /// 获取一个active值
            int active = count.getActive();

            // 当active 大于max，说明调用的已经超了设置最大并发数
            if (active >= max) {
                synchronized (count) {
                    // 活跃着 的还是大于 最大并发数
                    while ((active = count.getActive()) >= max) {
                        try {
                            // 进行等待  等待超时时间
                            count.wait(remain);
                        } catch (InterruptedException e) {
                        }
                        // 计算消耗多少时间
                        long elapsed = System.currentTimeMillis() - start;
                        // 剩余时间  超时时间-消耗时间
                        remain = timeout - elapsed;
                        if (remain <= 0) {// 小于等于0，说明没有剩余时间了，也就是超时了 ，这里直接抛出超时
                            throw new RpcException("Waiting concurrent invoke timeout in client-side for service:  "
                                    + invoker.getInterface().getName() + ", method: "
                                    + invocation.getMethodName() + ", elapsed: " + elapsed
                                    + ", timeout: " + timeout + ". concurrent invokes: " + active
                                    + ". max concurrent invoke limit: " + max);
                        }
                    }
                }
            }
        }
        try {
            // 开始时间
            long begin = System.currentTimeMillis();
            // 对应的RpcStatus 的 actives+1
            RpcStatus.beginCount(url, methodName);
            try {
                // 进行调用
                Result result = invoker.invoke(invocation);

                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, true);
                return result;
            } catch (RuntimeException t) {

                // 失败情况
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, false);
                throw t;
            }
        } finally {
            // 执行完唤醒
            if (max > 0) {
                synchronized (count) {
                    count.notify();
                }
            }
        }
    }

}
