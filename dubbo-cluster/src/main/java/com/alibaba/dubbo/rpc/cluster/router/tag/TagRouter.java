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
package com.alibaba.dubbo.rpc.cluster.router.tag;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.router.AbstractRouter;

import java.util.ArrayList;
import java.util.List;

/**
 * TagRouter
 *
 * 标签路由
 * http://dubbo.apache.org/zh-cn/docs/user/demos/routing-rule-deprecated.html
 */
public class TagRouter extends AbstractRouter {

    private static final int DEFAULT_PRIORITY = 100;
    private static final URL ROUTER_URL = new URL("tag", Constants.ANYHOST_VALUE, 0, Constants.ANY_VALUE).addParameters(Constants.RUNTIME_KEY, "true");

    public TagRouter() {
        this.url = ROUTER_URL;
        // 获取优先级
        this.priority = url.getParameter(Constants.PRIORITY_KEY, DEFAULT_PRIORITY);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        // filter
        List<Invoker<T>> result = new ArrayList<Invoker<T>>();
        // Dynamic param

        // 从上下文中获取dubbo.tag 扩展属性值标签
        String tag = RpcContext.getContext().getAttachment(Constants.TAG_KEY);
        // Tag request
        if (!StringUtils.isEmpty(tag)) {// 如果tag标签不是空
            // Select tag invokers first
            for (Invoker<T> invoker : invokers) {// 与服务提供者invoker的tag 做对比
                if (tag.equals(invoker.getUrl().getParameter(Constants.TAG_KEY))) {
                    result.add(invoker);// tag标签相等的 设置到结果列表中
                }
            }
        }


        /**
         *
         *
         * request.tag=red 时优先选择 tag=red 的 provider。若集群中不存在与请求标记对应的服务，可以降级请求 tag=null 的 provider，即默认 provider。
         *
         * request.tag=null 时，只会匹配 tag=null 的 provider。即使集群中存在可用的服务，若 tag 不匹配就无法调用，这与规则1不同，携带标签的请求可以降级访问到无标签的服务，但不携带标签/携带其他种类标签的请求永远无法访问到其他标签的服务。
         */
        // If Constants.REQUEST_TAG_KEY unspecified or no invoker be selected, downgrade to normal invokers
        if (result.isEmpty()) {// 如果results 是空的话
            // Only forceTag = true force match, otherwise downgrade


            // 获取force标签  dubbo.force.tag 属性值
            String forceTag = RpcContext.getContext().getAttachment(Constants.FORCE_USE_TAG);

            // 如果forceTag 是空或者是forceTag
            if (StringUtils.isEmpty(forceTag) || "false".equals(forceTag)) {
                for (Invoker<T> invoker : invokers) {
                    //若集群中不存在与请求标记对应的服务，可以降级请求 tag=null 的 provider，即默认 provider。
                    if (StringUtils.isEmpty(invoker.getUrl().getParameter(Constants.TAG_KEY))) {
                        result.add(invoker);
                    }
                }
            }
        }
        return result;
    }

}
