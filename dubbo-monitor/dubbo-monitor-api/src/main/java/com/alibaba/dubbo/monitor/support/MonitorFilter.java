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
package com.alibaba.dubbo.monitor.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.monitor.Monitor;
import com.alibaba.dubbo.monitor.MonitorFactory;
import com.alibaba.dubbo.monitor.MonitorService;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MonitorFilter. (SPI, Singleton, ThreadSafe)
 */
@Activate(group = {Constants.PROVIDER, Constants.CONSUMER})
public class MonitorFilter implements Filter  {

    private static final Logger logger = LoggerFactory.getLogger(MonitorFilter.class);
    // 缓存着 key=接口名.方法名       value= 计数器
    private final ConcurrentMap<String, AtomicInteger> concurrents = new ConcurrentHashMap<String, AtomicInteger>();

    private MonitorFactory monitorFactory;

    public void setMonitorFactory(MonitorFactory monitorFactory) {
        this.monitorFactory = monitorFactory;
    }

    // intercepting invocation
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        if (invoker.getUrl().hasParameter(Constants.MONITOR_KEY)) {// 判断是否有monitor参数
            RpcContext context = RpcContext.getContext(); // provider must fetch context before invoke() gets called
            // 获取远端host
            String remoteHost = context.getRemoteHost();
            long start = System.currentTimeMillis(); // record start timestamp
            // 计数器+1
            getConcurrent(invoker, invocation).incrementAndGet(); // count up 自增加1
            try {
                // 进行调用
                Result result = invoker.invoke(invocation); // proceed invocation chain

                collect(invoker, invocation, result, remoteHost, start, false);// 进行收集
                return result;
            } catch (RpcException e) {
                collect(invoker, invocation, null, remoteHost, start, true);
                throw e;
            } finally {
                getConcurrent(invoker, invocation).decrementAndGet(); // count down 自减1
            }
        } else {// 没有monitor参数就放行
            return invoker.invoke(invocation);
        }
    }

    // collect info
    private void collect(Invoker<?> invoker, Invocation invocation, Result result, String remoteHost, long start, boolean error) {
        try {
            // ---- service statistics ----
            long elapsed = System.currentTimeMillis() - start; // invocation cost  调用耗时
            int concurrent = getConcurrent(invoker, invocation).get(); // current concurrent count  获取当前的并发数
            String application = invoker.getUrl().getParameter(Constants.APPLICATION_KEY);  // application
            String service = invoker.getInterface().getName(); // service name    //接口名
            String method = RpcUtils.getMethodName(invocation); // method name   // 方法名
            String group = invoker.getUrl().getParameter(Constants.GROUP_KEY);   // 分组
            String version = invoker.getUrl().getParameter(Constants.VERSION_KEY);  // version
            URL url = invoker.getUrl().getUrlParameter(Constants.MONITOR_KEY);  // monitorUrl
            // 根据monitorUrl 从监控工厂中获取对应的监控对象
            Monitor monitor = monitorFactory.getMonitor(url);// 获取Monitor
            if (monitor == null) {
                return;
            }
            int localPort;
            String remoteKey;
            String remoteValue;
            if (Constants.CONSUMER_SIDE.equals(invoker.getUrl().getParameter(Constants.SIDE_KEY))) {// 如果是服务调用端
                // ---- for service consumer ----
                localPort = 0;
                remoteKey = MonitorService.PROVIDER;
                remoteValue = invoker.getUrl().getAddress();// 远端地址，在这里就是服务提供者方的地址
            } else {// 如果是服务提供者端
                // ---- for service provider ----
                localPort = invoker.getUrl().getPort();// 获取服务提供者端的port
                remoteKey = MonitorService.CONSUMER;
                remoteValue = remoteHost;// 远端地址，这里是服务提供者方的地址
            }
            String input = "", output = "";
            // 这两个会在Serialize层添加上
            // 获取input
            if (invocation.getAttachment(Constants.INPUT_KEY) != null) {// input
                input = invocation.getAttachment(Constants.INPUT_KEY);
            }
            // 获取output
            if (result != null && result.getAttachment(Constants.OUTPUT_KEY) != null) {// output
                output = result.getAttachment(Constants.OUTPUT_KEY);
            }
            //-----------------------monitor收集-------------------------
            monitor.collect(new URL(Constants.COUNT_PROTOCOL,// count
                    NetUtils.getLocalHost(), localPort,
                    service + "/" + method,// path
                    // 下面这一堆被安排在了 parameters中
                    MonitorService.APPLICATION, application,
                    MonitorService.INTERFACE, service,
                    MonitorService.METHOD, method,
                    remoteKey, remoteValue,

                    // 是否是错误（这里变动的是key）
                    error ? MonitorService.FAILURE : MonitorService.SUCCESS, "1",
                    MonitorService.ELAPSED, String.valueOf(elapsed),// 耗时
                    MonitorService.CONCURRENT, String.valueOf(concurrent),// 当前的一个并发数
                    Constants.INPUT_KEY, input,
                    Constants.OUTPUT_KEY, output,
                    Constants.GROUP_KEY, group,
                    Constants.VERSION_KEY, version));
        } catch (Throwable t) {
            logger.error("Failed to monitor count service " + invoker.getUrl() + ", cause: " + t.getMessage(), t);
        }
    }

    // concurrent counter   获取计数器
    private AtomicInteger getConcurrent(Invoker<?> invoker, Invocation invocation) {
        String key = invoker.getInterface().getName() + "." + invocation.getMethodName();// 接口名.方法名
        AtomicInteger concurrent = concurrents.get(key);// 获取统计器
        if (concurrent == null) {// 没有就新建
            concurrents.putIfAbsent(key, new AtomicInteger());
            concurrent = concurrents.get(key);
        }
        return concurrent;
    }

}
