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

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.Random;

/**
 * LeastActiveLoadBalance
 * 最活跃的
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    private final Random random = new Random();

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 服务提供者列表的长度
        int length = invokers.size(); // Number of invokers


        // 最活跃初始值是-1
        int leastActive = -1; // The least active value of all invokers
        //具有相同的最小活动值的调用程序的数量(leastActive)
        int leastCount = 0; // The number of invokers having the same least active value (leastActive)
        int[] leastIndexs = new int[length]; // The index of invokers having the same least active value (leastActive)

        // 权重和
        int totalWeight = 0; // The sum of with warmup weights
        //初始值 用于比较
        int firstWeight = 0; // Initial value, used for comparision

        // 每个invoker是否有不同的权重？
        boolean sameWeight = true; // Every invoker has the same weight value?
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);

            // 获取活跃数
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive(); // Active number
            // 计算权重值
            int afterWarmup = getWeight(invoker, invocation); // Weight

            // 第一个元素的后 或者 当前invoker活跃数 小于 活跃数
            if (leastActive == -1 || active < leastActive) { // Restart, when find a invoker having smaller least active value.
                // 记录leastActive 为当前的活跃数
                leastActive = active; // Record the current least active value
                //重置最小计数，基于当前最小计数重新计数
                leastCount = 1; // Reset leastCount, count again based on current leastCount


                //在0下标出放入这个索引
                leastIndexs[0] = i; // Reset

                // 总权重就是 当前invoker的权重
                totalWeight = afterWarmup; // Reset
                //第一个权重
                firstWeight = afterWarmup; // Record the weight the first invoker

                sameWeight = true; // Reset, every invoker has the same weight value?
            } else if (active == leastActive) {

                // 当前invoker的活跃数 与 leastActive相等

                // If current invoker's active value equals with leaseActive, then accumulating.

                // 记录索引位置，具有相同最小活跃数的计数器 +1
                leastIndexs[leastCount++] = i; // Record index number of this invoker

                //总权重 =  总权重+当前权重
                totalWeight += afterWarmup; // Add this invoker's weight to totalWeight.
                // If every invoker has the same weight?
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // assert(leastCount > 0)
        if (leastCount == 1) {//如果我们恰好有一个调用程序具有最少的活动值，那么直接返回这个调用程序。
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexs[0]);
        }





        // 如果每个invoker有不同的权重 &&  totalWeight > 0
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.

            // 在totalWeight 范围内随机一个值
            int offsetWeight = random.nextInt(totalWeight) + 1;
            // Return a invoker based on the random value.
            for (int i = 0; i < leastCount; i++) {
                // 获取i位置的那个最小活跃 在invokers 里面的位置信息
                int leastIndex = leastIndexs[i];

                //offsetWeight - leastIndex 位置invoker的权重
                offsetWeight -= getWeight(invokers.get(leastIndex), invocation);

                // offsetWeight 小于0的话
                if (offsetWeight <= 0)
                    // 返回这个位置的这个
                    return invokers.get(leastIndex);
            }
        }
        // 具有相同权重或者是 总权重=0 的话就均匀返回
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(leastIndexs[random.nextInt(leastCount)]);
    }
}
