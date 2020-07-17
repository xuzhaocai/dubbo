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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractClusterInvoker
 *
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory
            .getLogger(AbstractClusterInvoker.class);
    protected final Directory<T> directory;//服务提供者的一个目录

    protected final boolean availablecheck; //集群中是否排除不可用的invoker
    // 判断是否已经销毁
    private AtomicBoolean destroyed = new AtomicBoolean(false);
    /**
     * 粘滞连接 Invoker
     *
     * http://dubbo.apache.org/zh-cn/docs/user/demos/stickiness.html
     * 粘滞连接用于有状态服务，尽可能让客户端总是向同一提供者发起调用，除非该提供者挂了，再连另一台。
     * 粘滞连接将自动开启延迟连接，以减少长连接数。
     *   延迟链接 ：延迟连接用于减少长连接数。当有调用发起时，再创建长连接
     *            <dubbo:protocol name="dubbo" lazy="true" />
     *            注意：该配置只对使用长连接的 dubbo 协议生效。︎
     */
    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null)//验证参数
            throw new IllegalArgumentException("service directory == null");

        this.directory = directory;

        //"cluster.availablecheck"  默认true  集群中是否排除不可用的invoker
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(Constants.CLUSTER_AVAILABLE_CHECK_KEY, Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK);
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
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {// 如果invoker不等于null 就使用invoker的

            return invoker.isAvailable();
        }
        // 否则就使用directory的
        return directory.isAvailable();
    }
    // 销毁
    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy(); // 先设置状态 ，然后调用directory的destroy操作
        }
    }

    /**
     * Select a invoker using loadbalance policy.</br>
     * a)Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or, 
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * b)Reslection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also 
     * guarantees this invoker is available.
     * 从候选的invoker 集合里面获取一个最终调用的invoker
     * @param loadbalance load balance policy
     * @param invocation
     * @param invokers invoker candidates
     * @param selected  exclude selected invokers or not
     * @return
     * @throws RpcException
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.isEmpty())
            return null;
        String methodName = invocation == null ? "" : invocation.getMethodName();
        // 是老使用一个invoker  默认是false的
        boolean sticky = invokers.get(0).getUrl().getMethodParameter(methodName, Constants.CLUSTER_STICKY_KEY, Constants.DEFAULT_CLUSTER_STICKY);
        {
            //ignore overloaded method
            // 如果stickyInvoker 不是null 而且没有在invokers 中了， 则置为空
            if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
                stickyInvoker = null;
            }
            //ignore concurrency problem
            // 支持粘滞连接  && stickyInvoker 不是null  && （排除列表是null  或者排除列表中不包含stickyInvoker ）
            if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
                if (availablecheck && stickyInvoker.isAvailable()) {//是否排除不可用的invoker
                    return stickyInvoker; // 直接返回 粘滞连接
                }
            }
        }
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);
        // 如果支持 粘滞连接  则设置 缓存 粘滞连接
        if (sticky) {
            stickyInvoker = invoker;
        }
        return invoker;
    }
    //进行选择
    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.isEmpty())//  invoker 列表是空就返回null
            return null;
        if (invokers.size() == 1) // 就一个的话就 就用第一个
            return invokers.get(0);
        if (loadbalance == null) {  // 获取负载均衡策略   默认是random（随机）
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(Constants.DEFAULT_LOADBALANCE);
        }

        // 使用策略进行选择
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //If the `invoker` is in the  `selected` or invoker is unavailable && availablecheck is true, reselect.
        if ((selected != null && selected.contains(invoker))    // selected 存在选择的invoker   或者 检测可用参数是true +  invoker 不可用
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {

                // 进行重新选择
                Invoker<T> rinvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rinvoker != null) { // 重新选择出来的不是null
                    invoker = rinvoker;
                } else { // 如果重新选择出来的是null
                    //Check the index of current selected invoker, if it's not the last one, choose the one at index+1.
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision  选择 后一个 或者是第一个invoker
                        invoker = index < invokers.size() - 1 ? invokers.get(index + 1) : invokers.get(0);
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first,
     * if all invokers are in `selected`,
     * just pick an available one using loadbalance policy.
     * 重新选择， 首先使用invokers 中没有在selected 里面的，
     * 如果所有的invokers 中都在selected 中，就根据负载均衡策略选择一个
     * @param loadbalance
     * @param invocation
     * @param invokers
     * @param selected
     * @return
     * @throws RpcException
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck)
            throws RpcException {

        //Allocating one in advance, this list is certain to be used.
        List<Invoker<T>> reselectInvokers = new ArrayList<Invoker<T>>(invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        //First, try picking a invoker not in `selected`.  只要没有选择过的。
        if (availablecheck) { // invoker.isAvailable() should be checked  检测是否可用
            for (Invoker<T> invoker : invokers) {    // 找出所有可用并且没有在selected 中的 invoker， 然后添加到reselectInvokers 中
                if (invoker.isAvailable()) {
                    if (selected == null || !selected.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }
            // 如果 reselectInvokers 不是空的， 就根据负载均衡的策略选择一个invoker
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        } else { // do not check invoker.isAvailable()  不检测可用性
            for (Invoker<T> invoker : invokers) {   // 遍历invokers ，找出不在selected 中的invoker 添加到reselectInvokers中
                if (selected == null || !selected.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }// 不是空的话，就使用负载均衡策略 选择一个
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        // Just pick an available invoker using loadbalance policy    只要可用就可以了
        {
            if (selected != null) {  // 这个只选择可用的就行了， 在没在selected 列表中的就不管了。。。
                for (Invoker<T> invoker : selected) {
                    if ((invoker.isAvailable()) // available first
                            && !reselectInvokers.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }// 使用负载均衡策略 选择一个
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        return null;
    }
    // 实现接口的invoker方法
    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        checkWhetherDestroyed();   // 检验是否销毁
        LoadBalance loadbalance = null;  //定义负载均衡

        // binding attachments into invocation.
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {// 就是将一些公共的kv 设置到invocation中
            ((RpcInvocation) invocation).addAttachments(contextAttachments);
        }
        // 获取所有服务提供者的集合
        List<Invoker<T>> invokers = list(invocation);
        if (invokers != null && !invokers.isEmpty()) {

            // invokers 不是空  根据dubbo spi 获取负载均衡策略  默认是random
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(RpcUtils.getMethodName(invocation), Constants.LOADBALANCE_KEY, Constants.DEFAULT_LOADBALANCE));
        }

        // 如果是异步调用，就设置调用编号
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);

        // 具体实现是由子类实现的
        return doInvoke(invocation, invokers, loadbalance);
    }
    // 检测是否已经销毁
    protected void checkWhetherDestroyed() {

        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    /**
     * 检验invokers    主要是判断是否为空
     * @param invokers
     * @param invocation
     */
    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (invokers == null || invokers.isEmpty()) {
            throw new RpcException("Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }
    // 模版方法， 交由子类来实现
    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    /**
     * 获取所有服务提供者的invoker
     * @param invocation
     * @return
     * @throws RpcException
     */
    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        // 从directory中获取所有的invokers
        List<Invoker<T>> invokers = directory.list(invocation);
        return invokers;
    }
}
