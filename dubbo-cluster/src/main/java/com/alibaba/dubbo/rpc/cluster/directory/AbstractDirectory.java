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
package com.alibaba.dubbo.rpc.cluster.directory;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.router.MockInvokersSelector;
import com.alibaba.dubbo.rpc.cluster.router.tag.TagRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Abstract implementation of Directory: Invoker list returned from this Directory's list method have been filtered by Routers
 * 抽象Directory
 * 实现了公用的路由规则( Router )的逻辑。
 */
public abstract class AbstractDirectory<T> implements Directory<T> {

    // logger
    private static final Logger logger = LoggerFactory.getLogger(AbstractDirectory.class);
    //注册中心url
    private final URL url;
    // 是否已经销毁
    private volatile boolean destroyed = false;
    // 消费者url
    private volatile URL consumerUrl;
    //router 数组
    private volatile List<Router> routers;

    public AbstractDirectory(URL url) {
        this(url, null);
    }

    public AbstractDirectory(URL url, List<Router> routers) {
        this(url, url, routers);
    }

    public AbstractDirectory(URL url, URL consumerUrl, List<Router> routers) {
        if (url == null)
            throw new IllegalArgumentException("url == null");
        this.url = url;
        this.consumerUrl = consumerUrl;

        // 设置routers
        setRouters(routers);
    }

    @Override
    public List<Invoker<T>> list(Invocation invocation) throws RpcException {
        if (destroyed) { //判断是否销毁
            throw new RpcException("Directory already destroyed .url: " + getUrl());
        }


        List<Invoker<T>> invokers = doList(invocation);
        List<Router> localRouters = this.routers; // local reference

        // 根据路由规则筛选 router
        if (localRouters != null && !localRouters.isEmpty()) {
            for (Router router : localRouters) {
                try {


                    /**判断 "runtime" 为 true 才执行：是否在每次调用时执行路由规则，
                     * 否则只在提供者地址列表变更时预先执行并缓存结果，调用时直接从缓存中获取路由结果。
                     * 如果用了参数路由，必须设为 true，需要注意设置会影响调用的性能，可不填 ，缺省为 flase。
                     */
                    if (router.getUrl() == null || router.getUrl().getParameter(Constants.RUNTIME_KEY, false)) {
                        invokers = router.route(invokers, getConsumerUrl(), invocation);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
                }
            }
        }
        return invokers;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    public List<Router> getRouters() {
        return routers;
    }

    /**
     * 设置路由规则
     * @param routers
     */
    protected void setRouters(List<Router> routers) {
        // copy list
        routers = routers == null ? new ArrayList<Router>() : new ArrayList<Router>(routers);
        // append url router   获取router参数
        String routerkey = url.getParameter(Constants.ROUTER_KEY);
        if (routerkey != null && routerkey.length() > 0) {
            RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class).getExtension(routerkey);
            routers.add(routerFactory.getRouter(url));
        }
        // append mock invoker selector  添加几个系统自带的路由处理
        routers.add(new MockInvokersSelector());
        routers.add(new TagRouter());

        //排序
        Collections.sort(routers);
        this.routers = routers;
    }

    public URL getConsumerUrl() {
        return consumerUrl;
    }

    public void setConsumerUrl(URL consumerUrl) {
        this.consumerUrl = consumerUrl;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void destroy() {
        destroyed = true;
    }
    // 子类实现
    protected abstract List<Invoker<T>> doList(Invocation invocation) throws RpcException;

}
