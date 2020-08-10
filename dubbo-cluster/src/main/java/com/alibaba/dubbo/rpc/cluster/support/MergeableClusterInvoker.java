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
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.Merger;
import com.alibaba.dubbo.rpc.cluster.merger.MergerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unchecked")
public class MergeableClusterInvoker<T> implements Invoker<T> {
    //log
    private static final Logger log = LoggerFactory.getLogger(MergeableClusterInvoker.class);
    /**
     * Directory$Adaptive 对象
     */
    private final Directory<T> directory;

    /**
     * 线程池
     * 使用cachedThreadPool
     */
    private ExecutorService executor = Executors.newCachedThreadPool(new NamedThreadFactory("mergeable-cluster-executor", true));

    public MergeableClusterInvoker(Directory<T> directory) {
        this.directory = directory;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Result invoke(final Invocation invocation) throws RpcException {


        // 获得invoker列表
        List<Invoker<T>> invokers = directory.list(invocation);
        //判断方法中 是否有 merger属性
        String merger = getUrl().getMethodParameter(invocation.getMethodName(), Constants.MERGER_KEY);
        if (ConfigUtils.isEmpty(merger)) { // If a method doesn't have a merger, only invoke one Group


            // 如果没有存在merger属性，找一个可用的invoker
            for (final Invoker<T> invoker : invokers) {
                if (invoker.isAvailable()) {
                    return invoker.invoke(invocation);
                }
            }

            //使用迭代器找一个使用
            return invokers.iterator().next().invoke(invocation);
        }
        ///----------这个就是有merger属性的了-------------


        Class<?> returnType;
        try {
            // 通过反射获得返回值类型
            returnType = getInterface().getMethod(
                    invocation.getMethodName(), invocation.getParameterTypes()).getReturnType();
        } catch (NoSuchMethodException e) {
            returnType = null;
        }



        //提交线程池，并行执行，发起 RPC 调用，并添加到 results 中
        //调用所有的invoker
        Map<String, Future<Result>> results = new HashMap<String, Future<Result>>();
        for (final Invoker<T> invoker : invokers) {
            Future<Result> future = executor.submit(new Callable<Result>() {
                @Override
                public Result call() throws Exception {
                    // 发起rpc调用
                    return invoker.invoke(new RpcInvocation(invocation, invoker));
                }
            });
            results.put(invoker.getUrl().getServiceKey(), future);
        }

        Object result = null;
        ///阻塞等待执行执行结果，并添加到 resultList 中
        List<Result> resultList = new ArrayList<Result>(results.size());
        // 获取超时时间 默认1s
        int timeout = getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        for (Map.Entry<String, Future<Result>> entry : results.entrySet()) {
            Future<Result> future = entry.getValue();
            try {
                Result r = future.get(timeout, TimeUnit.MILLISECONDS);
                if (r.hasException()) {  // 发生异常 ，打印log
                    log.error("Invoke " + getGroupDescFromServiceKey(entry.getKey()) + 
                                    " failed: " + r.getException().getMessage(), 
                            r.getException());
                } else {  // 将结果添加到list中
                    resultList.add(r);
                }
            } catch (Exception e) {  ///调用异常 抛出RpcException
                throw new RpcException("Failed to invoke service " + entry.getKey() + ": " + e.getMessage(), e);
            }
        }
        // 判断结果集
        if (resultList.isEmpty()) { // 为空的时候
            return new RpcResult((Object) null);// 放回个空的result
        } else if (resultList.size() == 1) {  //一个的时候 直接将那个返回就好了
            return resultList.iterator().next();
        }
        // 返回值是void
        if (returnType == void.class) {
            return new RpcResult((Object) null);
        }



        //若 merger 为 "." 开头，指定合并方法，将调用返回结果的指定方法进行合并，合并方法的参数类型必须是返回结果类型本身。
        if (merger.startsWith(".")) {
            merger = merger.substring(1);
            Method method;
            try {
                method = returnType.getMethod(merger, returnType);
            } catch (NoSuchMethodException e) {
                throw new RpcException("Can not merge result because missing method [ " + merger + " ] in class [ " + 
                        returnType.getClass().getName() + " ]");
            }

            // 设置method access属性
            if (!Modifier.isPublic(method.getModifiers())) {
                method.setAccessible(true);
            }
            result = resultList.remove(0).getValue();
            try {

                // 方法返回类型匹配，合并时，修改 result
                if (method.getReturnType() != void.class
                        && method.getReturnType().isAssignableFrom(result.getClass())) {
                    for (Result r : resultList) {
                        result = method.invoke(result, r.getValue());
                    }

                    // 方法返回类型不匹配，合并时，不修改 result
                } else {
                    for (Result r : resultList) {
                        method.invoke(result, r.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RpcException("Can not merge result: " + e.getMessage(), e);
            }
        } else {
            Merger resultMerger;

            // 根据返回值类型匹配merger
            if (ConfigUtils.isDefault(merger)) {  // true 或者是 default
                // 根据返回值类型获取Merger
                resultMerger = MergerFactory.getMerger(returnType);
            } else {// 指定了 merger类型 ，通过dubbo spi 获取 对应的merger对象
                resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
            }



            if (resultMerger != null) {
                List<Object> rets = new ArrayList<Object>(resultList.size());
                for (Result r : resultList) {
                    rets.add(r.getValue());
                }

                // 使用merger 进行合并
                result = resultMerger.merge(
                        rets.toArray((Object[]) Array.newInstance(returnType, 0)));
            } else {//抛出异常 没有merge进行合并
                throw new RpcException("There is no merger to merge result.");
            }
        }
        return new RpcResult(result);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        directory.destroy();
    }

    private String getGroupDescFromServiceKey(String key) {
        int index = key.indexOf("/");
        if (index > 0) {
            return "group [ " + key.substring(0, index) + " ]";
        }
        return key;
    }
}
