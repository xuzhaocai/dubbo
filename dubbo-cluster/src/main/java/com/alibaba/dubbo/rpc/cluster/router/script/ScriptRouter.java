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
package com.alibaba.dubbo.rpc.cluster.router.script;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.router.AbstractRouter;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ScriptRouter
 */
public class ScriptRouter extends AbstractRouter {

    private static final Logger logger = LoggerFactory.getLogger(ScriptRouter.class);

    private static final int DEFAULT_PRIORITY = 1;  // 默认优先级
    /**
     * 脚本类型与脚本引擎
     */
    private static final Map<String, ScriptEngine> engines = new ConcurrentHashMap<String, ScriptEngine>();

    private final ScriptEngine engine;// 脚本执行引擎

    private final String rule;// 规则

    public ScriptRouter(URL url) {
        this.url = url;

        //获得type 。这里没有设置 下面会默认是javascript
        String type = url.getParameter(Constants.TYPE_KEY);

        //获取优先级 ， 默认是1
        this.priority = url.getParameter(Constants.PRIORITY_KEY, DEFAULT_PRIORITY);

        // rule
        String rule = url.getParameterAndDecoded(Constants.RULE_KEY);
        if (type == null || type.length() == 0) {// 类型是空  默认是javascript
            type = Constants.DEFAULT_SCRIPT_TYPE_KEY;
        }

        // 规则不能为空
        if (rule == null || rule.length() == 0) {// rule不能为空
            throw new IllegalStateException(new IllegalStateException("route rule can not be empty. rule:" + rule));
        }

        // 根据脚本类型从缓存中获取 engine ，如果没有的话就创建，然后塞到缓存中
        ScriptEngine engine = engines.get(type); // 从缓存中获取
        if (engine == null) {

            // 使用java 自带script引擎管理器获得相应的脚本引擎
            engine = new ScriptEngineManager().getEngineByName(type);
            if (engine == null) {  // 没有找到相应的脚本引擎
                throw new IllegalStateException(new IllegalStateException("Unsupported route rule type: " + type + ", rule: " + rule));
            }
            // 缓存
            engines.put(type, engine);
        }
        this.engine = engine;
        this.rule = rule;
    }

    /**
     * 根据规则筛选invokers
     * @param invokers
     * @param url        refer url
     * @param invocation
     * @param <T>
     * @return
     * @throws RpcException
     *
     *
     *
     *
     * （function route(invokers) {
     *     var result = new java.util.ArrayList(invokers.size());
     *     for (i = 0; i < invokers.size(); i ++) {
     *         if ("10.20.153.10".equals(invokers.get(i).getUrl().getHost())) {
     *             result.add(invokers.get(i));
     *         }
     *     }
     *     return result;
     *  } (invokers)）; // 表示立即执行方法
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        try {
            List<Invoker<T>> invokersCopy = new ArrayList<Invoker<T>>(invokers);

            Compilable compilable = (Compilable) engine;

            Bindings bindings = engine.createBindings();// 这个就是往里面设置变量
            bindings.put("invokers", invokersCopy);
            bindings.put("invocation", invocation);
            bindings.put("context", RpcContext.getContext());
            CompiledScript function = compilable.compile(rule);
            Object obj = function.eval(bindings);

            // 处理返回值类型
            if (obj instanceof Invoker[]) {
                invokersCopy = Arrays.asList((Invoker<T>[]) obj);
            } else if (obj instanceof Object[]) {
                invokersCopy = new ArrayList<Invoker<T>>();
                for (Object inv : (Object[]) obj) {
                    invokersCopy.add((Invoker<T>) inv);
                }
            } else {
                invokersCopy = (List<Invoker<T>>) obj;
            }
            return invokersCopy;

        } catch (ScriptException e) {
            // 执行错误只进行打印
            //fail then ignore rule .invokers.
            logger.error("route error , rule has been ignored. rule: " + rule + ", method:" + invocation.getMethodName() + ", url: " + RpcContext.getContext().getUrl(), e);

            //返回全部invoker
            return invokers;

        }
    }

}
