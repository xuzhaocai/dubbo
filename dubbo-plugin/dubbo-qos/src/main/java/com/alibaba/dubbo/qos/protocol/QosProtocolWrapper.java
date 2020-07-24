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
package com.alibaba.dubbo.qos.protocol;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.qos.server.Server;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.alibaba.dubbo.common.Constants.ACCEPT_FOREIGN_IP;
import static com.alibaba.dubbo.common.Constants.QOS_ENABLE;
import static com.alibaba.dubbo.common.Constants.QOS_PORT;
import static com.alibaba.dubbo.qos.common.QosConstants.DEFAULT_PORT;
// Qos
public class QosProtocolWrapper implements Protocol {

    private final Logger logger = LoggerFactory.getLogger(QosProtocolWrapper.class);
    // 是否启动的一个标志
    private static AtomicBoolean hasStarted = new AtomicBoolean(false);

    private Protocol protocol;

    public QosProtocolWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }
    //  暴露服务
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {

        //判断是registry
        if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            // 启动Qos服务器？
            startQosServer(invoker.getUrl());
            return protocol.export(invoker);
        }
        return protocol.export(invoker);
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            startQosServer(url);// 启动Qos 服务器
            return protocol.refer(type, url);
        }
        return protocol.refer(type, url);
    }

    @Override
    public void destroy() {
        protocol.destroy();
        stopServer();// 停止Qos 监听
    }

    private void startQosServer(URL url) {
        try {
            //参数    qos.enable  缺省是true
            boolean qosEnable = url.getParameter(QOS_ENABLE,true);
            if (!qosEnable) {
                logger.info("qos won't be started because it is disabled. " +
                        "Please check dubbo.application.qos.enable is configured either in system property, " +
                        "dubbo.properties or XML/spring boot configuration.");
                return;
            }
            // 判断是否已经启动
            if (!hasStarted.compareAndSet(false, true)) {
                return;
            }
            //获取port 默认的port 22222
            int port = url.getParameter(QOS_PORT, DEFAULT_PORT);

            // 获取  qos.accept.foreign.ip  缺省 false  默认接收任何主机发起命令
            boolean acceptForeignIp = Boolean.parseBoolean(url.getParameter(ACCEPT_FOREIGN_IP,"false"));
            Server server = com.alibaba.dubbo.qos.server.Server.getInstance();
            server.setPort(port);
            server.setAcceptForeignIp(acceptForeignIp);
            //server.setWelcome("welcome to dubbo qos");
            server.start();// start

        } catch (Throwable throwable) {
            logger.warn("Fail to start qos server: ", throwable);
        }
    }

    /*package*/ void stopServer() {
        if (hasStarted.compareAndSet(true, false)) {
            Server server = Server.getInstance();
            server.stop();
        }
    }
}
