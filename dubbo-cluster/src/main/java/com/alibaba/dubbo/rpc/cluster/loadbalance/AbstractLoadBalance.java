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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AbstractLoadBalance
 *
 */
public abstract class AbstractLoadBalance implements LoadBalance {


    /**
     *
     * @param uptime  服务起启动时间
     * @param warmup  服务正常提供服务一个缓冲时间，也就是我这个服务器预热，默认预热10分钟
     * @param weight  初始权重（这个是用户自己设置，也可以是默认的100）
     * @return  计算后的权重
     */
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {


        // 服务器启动时间  /  （服务器预热需要的时间/初始权重）

        int ww = (int) ((float) uptime / ((float) warmup / (float) weight));

        // 计算完成的权重 小于1的时候就是1 ， 大于1的时候 与初始阶段的权重做比较，大于初始阶段 就选初始的那个权重，小于的话就选计算后的权重
        return ww < 1 ? 1 : (ww > weight ? weight : ww);
    }
    /**
     * 在invokers选择一个合适的invoker， 这里如果服务提供者们 就一个，直接返回那一个。然后再交由子类doSelect(invokers, url, invocation);进行选择。
     * @param invokers   invokers.  服务提供者们
     * @param url        refer url  引用url
     * @param invocation invocation. 调用信息
     * @param <T>
     * @return  选择出来的那个invoker
     */
    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (invokers == null || invokers.isEmpty())//如果invokers 是空的话
            return null;
        if (invokers.size() == 1)/// 如果就一个的话 ，返回第一个
            return invokers.get(0);
        return doSelect(invokers, url, invocation);// 交由子类实现
    }

    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);
    // 获取权重信息
    protected int getWeight(Invoker<?> invoker, Invocation invocation) {
        // 缺省权重 是100
        int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT);
        if (weight > 0) {

            /// 获取remote.timestamp 属性值 缺省是0
            long timestamp = invoker.getUrl().getParameter(Constants.REMOTE_TIMESTAMP_KEY, 0L);
            if (timestamp > 0L) {
                // 计算invoker 对应的这个服务提供者服务  服务时间（就是从启动到现在）
                int uptime = (int) (System.currentTimeMillis() - timestamp);
                //获取  warmup  属性值 ，缺省是10分钟
                int warmup = invoker.getUrl().getParameter(Constants.WARMUP_KEY, Constants.DEFAULT_WARMUP);
                if (uptime > 0 && uptime < warmup) {
                    //如果机器启动时间小于这个10分钟，就进行计算这个权重值，
                    // 如果是大于这个10分钟，然后权重就是你设置的那个，没有设置就是100
                    // 计算权重值
                    weight = calculateWarmupWeight(uptime, warmup, weight);
                }
            }
        }
        return weight;
    }

}
