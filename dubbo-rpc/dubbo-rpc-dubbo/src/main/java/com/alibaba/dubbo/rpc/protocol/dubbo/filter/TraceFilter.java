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
package com.alibaba.dubbo.rpc.protocol.dubbo.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.fastjson.JSON;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TraceFilter
 */
@Activate(group = Constants.PROVIDER)
public class TraceFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(TraceFilter.class);

    private static final String TRACE_MAX = "trace.max";

    private static final String TRACE_COUNT = "trace.count";
    // 缓存
    private static final ConcurrentMap<String, Set<Channel>> tracers = new ConcurrentHashMap<String, Set<Channel>>();
    // 添加Tracer
    public static void addTracer(Class<?> type, String method, Channel channel, int max) {
        channel.setAttribute(TRACE_MAX, max);// trace.max
        channel.setAttribute(TRACE_COUNT, new AtomicInteger());// 统计count
        // 拼装key，如果method没有的话就使用type的全类名， 如果有method话就是 全类名.方法名
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName();
        Set<Channel> channels = tracers.get(key);// 从缓存中获取
        if (channels == null) {// 如果有找到对应的channel
            tracers.putIfAbsent(key, new ConcurrentHashSet<Channel>());
            channels = tracers.get(key);
        }
        channels.add(channel);// 添加到set集合中
    }
    // 移除tracer
    public static void removeTracer(Class<?> type, String method, Channel channel) {
        channel.removeAttribute(TRACE_MAX);// 先从channel 中移除这两个属性
        channel.removeAttribute(TRACE_COUNT);

        // 拼装key
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName();
        Set<Channel> channels = tracers.get(key);
        if (channels != null) {
            channels.remove(channel);// 移除
        }
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        long start = System.currentTimeMillis();// 开始时间
        Result result = invoker.invoke(invocation);
        long end = System.currentTimeMillis();// 结束时间
        if (tracers.size() > 0) {
            // 拼装key，先使用全类名.方法名的 形式
            String key = invoker.getInterface().getName() + "." + invocation.getMethodName();// 接口全路径.方法名
            Set<Channel> channels = tracers.get(key);// 获取对应tracers
            if (channels == null || channels.isEmpty()) {// 没有对应的tracer 就使用 接口全类名做 key
                key = invoker.getInterface().getName();// 接口名
                channels = tracers.get(key);// 使用全类名key再获取一遍
            }
            if (channels != null && !channels.isEmpty()) {//
                for (Channel channel : new ArrayList<Channel>(channels)) {//遍历这堆channel
                    if (channel.isConnected()) {//不是关闭状态的话
                        try {
                            int max = 1;
                            // 从channel中获取trace.max属性
                            Integer m = (Integer) channel.getAttribute(TRACE_MAX);//获取trace.max属性
                            if (m != null) {//如果 max 是null的话，max=m
                                max = (int) m;
                            }
                            int count = 0;

                            // 这个其实是个计数器
                            AtomicInteger c = (AtomicInteger) channel.getAttribute(TRACE_COUNT);// trace.count
                            if (c == null) {// 没有就新建然后设置进去
                                c = new AtomicInteger();
                                channel.setAttribute(TRACE_COUNT, c);
                            }
                            count = c.getAndIncrement();// 调用次数+1
                            if (count < max) {// 当count小于max的时候

                                // 获取那个终端上的头 ，这个不用纠结
                                String prompt = channel.getUrl().getParameter(Constants.PROMPT_KEY, Constants.DEFAULT_PROMPT);

                                // 发送 耗时信息
                                channel.send("\r\n" + RpcContext.getContext().getRemoteAddress() + " -> "
                                        + invoker.getInterface().getName()
                                        + "." + invocation.getMethodName()
                                        + "(" + JSON.toJSONString(invocation.getArguments()) + ")" + " -> " + JSON.toJSONString(result.getValue())
                                        + "\r\nelapsed: " + (end - start) + " ms."
                                        + "\r\n\r\n" + prompt);
                            }
                            if (count >= max - 1) {// 当调用总次数超过 max的时候
                                channels.remove(channel);// 就将channel移除
                            }
                        } catch (Throwable e) {
                            channels.remove(channel);
                            logger.warn(e.getMessage(), e);
                        }
                    } else {
                        channels.remove(channel);
                    }
                }
            }
        }
        // 返回result
        return result;
    }

}
