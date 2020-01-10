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

package com.alibaba.dubbo.rpc.cluster.merger;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.rpc.cluster.Merger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MergerFactory {
    /**
     *merge缓存
     */
    private static final ConcurrentMap<Class<?>, Merger<?>> mergerCache =
            new ConcurrentHashMap<Class<?>, Merger<?>>();


    /**
     * 根据类型获得merge类
     * @param returnType
     * @param <T>
     * @return
     */
    public static <T> Merger<T> getMerger(Class<T> returnType) {
        Merger result;
        if (returnType.isArray()) {// 是array
            Class type = returnType.getComponentType();// 获得类型
            result = mergerCache.get(type);
            if (result == null) {
                loadMergers();
                result = mergerCache.get(type);
            }
            if (result == null && !type.isPrimitive()) { // 如果没有找到， 并且不是自带类型
                result = ArrayMerger.INSTANCE;  // 返回通用数组merge
            }
        } else {
            result = mergerCache.get(returnType);
            if (result == null) {
                loadMergers();
                result = mergerCache.get(returnType);
            }
        }
        return result;
    }

    /**
     * 使用spi技术加载merge
     */
    static void loadMergers() {
        Set<String> names = ExtensionLoader.getExtensionLoader(Merger.class)
                .getSupportedExtensions();
        for (String name : names) {// 将所有的merge缓存到map中
            Merger m = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(name);
            mergerCache.putIfAbsent(ReflectUtils.getGenericClass(m.getClass()), m);
        }
    }

}
