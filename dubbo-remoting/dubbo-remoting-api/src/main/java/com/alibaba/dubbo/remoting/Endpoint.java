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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.URL;

import java.net.InetSocketAddress;

/**
 * Endpoint. (API/SPI, Prototype, ThreadSafe)
 * 端点，可以理解为一个客户端或者服务端就是一个端点
 *
 * @see com.alibaba.dubbo.remoting.Channel
 * @see com.alibaba.dubbo.remoting.Client
 * @see com.alibaba.dubbo.remoting.Server
 */
public interface Endpoint {
    URL getUrl();// 获取url
    ChannelHandler getChannelHandler();//获取channelhandler
    InetSocketAddress getLocalAddress();//获取本地地址
    void send(Object message) throws RemotingException;//send message
    void send(Object message, boolean sent) throws RemotingException;//send message
    void close();// 关闭
    void close(int timeout);// 优雅关闭
    void startClose();// 开始关闭
    boolean isClosed();// 判断是否关闭
}