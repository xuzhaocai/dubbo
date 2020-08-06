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
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ConsistentHashLoadBalance
 * 一致性hash 算法实现
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {

        // 获取方法名
        String methodName = RpcUtils.getMethodName(invocation);
        // 拼接key， 接口全类名.方法名
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;

        //根据invokers 计算个hashcode
        int identityHashCode = System.identityHashCode(invokers);

        // 根据key 从缓存中获取ConsistentHashSelector
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);


        // selector==null 或者是 selector的hashcode ！= 现在算出来的，说明这个invokers 变了
        if (selector == null || selector.identityHashCode != identityHashCode) {

            // 创建ConsistentHashSelector 放入缓存中
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            // 获取新的selector
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }

        // 是用selector 进行选择
        return selector.select(invocation);
    }

    private static final class ConsistentHashSelector<T> {

        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        private final int replicaNumber;

        private final int identityHashCode;

        private final int[] argumentIndex;

        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {

            // 虚拟的invoker
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            // hashcode
            this.identityHashCode = identityHashCode;
            // 获取url
            URL url = invokers.get(0).getUrl();


            //获取hash.nodes ，缺省是160  每个实例节点的个数
            this.replicaNumber = url.getMethodParameter(methodName, "hash.nodes", 160);


            // 获取hash.arguments 缺省是0 然后进行切割
            String[] index = Constants.COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, "hash.arguments", "0"));

            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            for (Invoker<T> invoker : invokers) {
                // 获取地址
                String address = invoker.getUrl().getAddress();

                for (int i = 0; i < replicaNumber / 4; i++) {
                    byte[] digest = md5(address + i);
                    for (int h = 0; h < 4; h++) {
                        long m = hash(digest, h);//计算位置
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        public Invoker<T> select(Invocation invocation) {
            // 将参数转成key
            String key = toKey(invocation.getArguments());
            byte[] digest = md5(key);
            return selectForKey(hash(digest, 0));
        }

        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);// 参数
                }
            }
            return buf.toString();
        }

        private Invoker<T> selectForKey(long hash) {//tailMap 是返回键值大于或等于key的那部分 ，然后再取第一个
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.tailMap(hash, true).firstEntry();
            if (entry == null) {//如果没有取到的话就说明hash就是最大的了，下面那个就是 treemap 第一个了
                entry = virtualInvokers.firstEntry();
            }// 返回对应的那个invoker
            return entry.getValue();
        }

        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes;
            try {
                bytes = value.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.update(bytes);
            return md5.digest();
        }

    }

}
