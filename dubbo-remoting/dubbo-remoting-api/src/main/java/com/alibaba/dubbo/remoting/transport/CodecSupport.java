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

package com.alibaba.dubbo.remoting.transport;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.Serialization;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 编解码工具类
 *
 * 以键值对的形式缓存所有的序列化类对象
 * 通过id ，url 等方式获取序列化对象
 *
 */
public class CodecSupport {

    private static final Logger logger = LoggerFactory.getLogger(CodecSupport.class);



    // id  ---> 序列化对象
    /**
     * 1--->DubboSerialization对象
     */
    private static Map<Byte, Serialization> ID_SERIALIZATION_MAP = new HashMap<Byte, Serialization>();

    // id ----> 序列化的名字
    /**
     * 1--->dubbo
     * 2--->hessian2
     * 3--->java
     * 4--->compectedjava
     * 6--->fastjson
     * 7--->nativejava
     * 8--->kryo
     * 9--->fst
     */
    private static Map<Byte, String> ID_SERIALIZATIONNAME_MAP = new HashMap<Byte, String>();

    static {
        Set<String> supportedExtensions = ExtensionLoader.getExtensionLoader(Serialization.class).getSupportedExtensions();
        for (String name : supportedExtensions) {
            Serialization serialization = ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(name);
            byte idByte = serialization.getContentTypeId();
            if (ID_SERIALIZATION_MAP.containsKey(idByte)) {
                logger.error("Serialization extension " + serialization.getClass().getName()
                        + " has duplicate id to Serialization extension "
                        + ID_SERIALIZATION_MAP.get(idByte).getClass().getName()
                        + ", ignore this Serialization extension");
                continue;
            }
            ID_SERIALIZATION_MAP.put(idByte, serialization);
            ID_SERIALIZATIONNAME_MAP.put(idByte, name);
        }
    }

    private CodecSupport() {
    }

    /**
     * 通过id获取对应的序列化对象
     * @param id
     * @return
     */
    public static Serialization getSerializationById(Byte id) {
        return ID_SERIALIZATION_MAP.get(id);
    }
    /**
     * 通过url获取序列化对象
     * 通过serialization 参数， 默认序列化方式是hessian2
     * @param url
     * @return
     */
    public static Serialization getSerialization(URL url) {
        return ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(
                url.getParameter(Constants.SERIALIZATION_KEY, Constants.DEFAULT_REMOTING_SERIALIZATION));
    }

    /**
     * 根据url与id 获取序列化对象
     * @param url
     * @param id
     * @return
     * @throws IOException
     */
    public static Serialization getSerialization(URL url, Byte id) throws IOException {
        Serialization serialization = getSerializationById(id);
        String serializationName = url.getParameter(Constants.SERIALIZATION_KEY, Constants.DEFAULT_REMOTING_SERIALIZATION);
        // Check if "serialization id" passed from network matches the id on this side(only take effect for JDK serialization), for security purpose.


        // 出于安全的目的，针对 JDK 的序列化方式（对应编号为 3、4、7），检查连接到服务器的 URL 和实际传输的数据，协议是否一致。
        // https://github.com/apache/incubator-dubbo/issues/1138
        // Check if "serialization id" passed from network matches the id on this side(only take effect for JDK serialization), for security purpose.

        if (serialization == null
                || ((id == 3 || id == 7 || id == 4) && !(serializationName.equals(ID_SERIALIZATIONNAME_MAP.get(id))))) {
            throw new IOException("Unexpected serialization id:" + id + " received from network, please check if the peer send the right id.");
        }
        return serialization;
    }

    public static ObjectInput deserialize(URL url, InputStream is, byte proto) throws IOException {
        Serialization s = getSerialization(url, proto);
        return s.deserialize(url, is);
    }

}
