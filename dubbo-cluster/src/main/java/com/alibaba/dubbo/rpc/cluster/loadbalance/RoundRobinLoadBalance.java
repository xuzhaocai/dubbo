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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 *
 * Smoothly round robin's implementation @since 2.6.5 
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";
    
    private static int RECYCLE_PERIOD = 60000;
    
    protected static class WeightedRoundRobin {
        private int weight;// 权重值
        private AtomicLong current = new AtomicLong(0);
        private long lastUpdate;// 最后修改时间
        public int getWeight() {
            return weight;
        }
        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);// 设置成0
        }

        // 将权重值设置到atomiclong中
        public long increaseCurrent() {
            return current.addAndGet(weight);
        }
        public void sel(int total) {
            current.addAndGet(-1 * total);
        }
        public long getLastUpdate() {
            return lastUpdate;
        }
        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }
    // 缓存   key =接口全类名.方法名      value = map<每个invoker的标识，WeightedRoundRobin >
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();



    private AtomicBoolean updateLock = new AtomicBoolean();
    
    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     * 
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }
    // 进行选择
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {

        /// 组装key = 接口全类名.方法名
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        // 根据key 从缓存中获取
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);

        // 处理是空的情况，如果为空就创建 然后塞进去
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }



        int totalWeight = 0;
        //初始化max 为long最小值
        long maxCurrent = Long.MIN_VALUE;
        // 获取系统当前时间
        long now = System.currentTimeMillis();
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;

        // 遍历invokers
        for (Invoker<T> invoker : invokers) {

            // 将url 转成 身份标识
            String identifyString = invoker.getUrl().toIdentityString();

            // 从map中获取
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);
            // 计算权重
            int weight = getWeight(invoker, invocation);
            if (weight < 0) {// 如果计算的权重小于0 ， 设置成0
                weight = 0;
            }
            if (weightedRoundRobin == null) {// 没有获取到，说明没有缓存
                weightedRoundRobin = new WeightedRoundRobin();//创建WeightedRoundRobin对象
                weightedRoundRobin.setWeight(weight);// 设置权重
                // 将WeightedRoundRobin 对象塞到 map中
                map.putIfAbsent(identifyString, weightedRoundRobin);
                weightedRoundRobin = map.get(identifyString);
            }

            //如果当前计算的权重与之前缓存的比匹配就设置成当前权重
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            // 将权重值设置到atomiclong 中
            long cur = weightedRoundRobin.increaseCurrent();

            // 设置最后修改时间
            weightedRoundRobin.setLastUpdate(now);


            // 如果当前权重 > max
            if (cur > maxCurrent) {
                maxCurrent = cur;// max 设置成赋值为当前权重
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            // 总权重+ 当前权重
            totalWeight += weight;
        }


        // 没有在修改的 && 当前所有的invoker 列表大小 不等于 缓存里面的大小（说明还有没缓存进去的）
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {// cas 操作
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<String, WeightedRoundRobin>();
                    newMap.putAll(map);// 将map添加到 newMap中


                    Iterator<Entry<String, WeightedRoundRobin>> it = newMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, WeightedRoundRobin> item = it.next();
                        // 移除 1分钟没有改动过的
                        if (now - item.getValue().getLastUpdate() > RECYCLE_PERIOD) {
                            it.remove();
                        }
                    }// 替换成新的
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }


        if (selectedInvoker != null) {

            // 将选中的那个weightedRoundRobin 里面的current设置成负的 totalWeight
            selectedWRR.sel(totalWeight);
            return selectedInvoker;// 返回选中的那个invoker
        }
        /// 最后就是选择第一个
        // should not happen here
        return invokers.get(0);
    }

}
