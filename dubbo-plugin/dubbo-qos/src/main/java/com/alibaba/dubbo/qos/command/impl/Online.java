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
package com.alibaba.dubbo.qos.command.impl;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.config.model.ApplicationModel;
import com.alibaba.dubbo.config.model.ProviderModel;
import com.alibaba.dubbo.qos.command.BaseCommand;
import com.alibaba.dubbo.qos.command.CommandContext;
import com.alibaba.dubbo.qos.command.annotation.Cmd;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.support.ProviderConsumerRegTable;
import com.alibaba.dubbo.registry.support.ProviderInvokerWrapper;

import java.util.List;
import java.util.Set;

@Cmd(name = "online", summary = "online dubbo", example = {
        "online dubbo",
        "online xx.xx.xxx.service"
})
public class Online implements BaseCommand {/// 服务上线
    private Logger logger = LoggerFactory.getLogger(Online.class);
    private RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getAdaptiveExtension();

    @Override
    public String execute(CommandContext commandContext, String[] args) {
        logger.info("receive online command");
        String servicePattern = ".*";
        if (args != null && args.length > 0) {
            servicePattern = args[0];
        }
        boolean hasService = false;
        // 获取到所有的服务provider
        List<ProviderModel> providerModelList = ApplicationModel.allProviderModels();
        for (ProviderModel providerModel : providerModelList) {
            if (providerModel.getServiceName().matches(servicePattern)) {
                hasService = true;// 设置hasService，表示有这个服务
                Set<ProviderInvokerWrapper> providerInvokerWrapperSet = ProviderConsumerRegTable.getProviderInvoker(providerModel.getServiceName());
                for (ProviderInvokerWrapper providerInvokerWrapper : providerInvokerWrapperSet) {
                    if (providerInvokerWrapper.isReg()) {// 服务是否已经注册
                        continue;
                    }// 获取到注册中心
                    Registry registry = registryFactory.getRegistry(providerInvokerWrapper.getRegistryUrl());
                    registry.register(providerInvokerWrapper.getProviderUrl());// 进行注册
                    providerInvokerWrapper.setReg(true); //设置注册标识为true
                }
            }
        }

        if (hasService) {
            return "OK";
        } else {
            return "service not found";
        }

    }
}
