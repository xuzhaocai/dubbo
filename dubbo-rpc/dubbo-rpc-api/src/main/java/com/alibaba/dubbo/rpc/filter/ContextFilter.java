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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;

import java.util.HashMap;
import java.util.Map;

/**
 * ContextInvokerFilter
 */
@Activate(group = Constants.PROVIDER, order = -10000)
public class ContextFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        Map<String, String> attachments = invocation.getAttachments();// 获取附加参数
        if (attachments != null) {
            attachments = new HashMap<String, String>(attachments);
            attachments.remove(Constants.PATH_KEY);//path
            attachments.remove(Constants.GROUP_KEY);//group
            attachments.remove(Constants.VERSION_KEY);// version
            attachments.remove(Constants.DUBBO_VERSION_KEY);//dubbo
            attachments.remove(Constants.TOKEN_KEY);//token
            attachments.remove(Constants.TIMEOUT_KEY);//timeou
            attachments.remove(Constants.ASYNC_KEY);// Remove async property to avoid being passed to the following invoke chain.//是否异步
        }
        RpcContext.getContext()
                .setInvoker(invoker)
                .setInvocation(invocation)
//                .setAttachments(attachments)  // merged from dubbox
                .setLocalAddress(invoker.getUrl().getHost(),
                        invoker.getUrl().getPort());

        // mreged from dubbox
        // we may already added some attachments into RpcContext before this filter (e.g. in rest protocol)
        if (attachments != null) {
            if (RpcContext.getContext().getAttachments() != null) {// 如果存在attachments
                RpcContext.getContext().getAttachments().putAll(attachments); // 将invoker里面的attachments设置到context中
            } else {
                RpcContext.getContext().setAttachments(attachments);// 设置attachments
            }
        }

        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);// invocation 中设置 invoker
        }
        try {
            RpcResult result = (RpcResult) invoker.invoke(invocation);
            // pass attachments to result result中添加servercontext的参数集
            result.addAttachments(RpcContext.getServerContext().getAttachments());
            return result;
        } finally {
            RpcContext.removeContext();//remove context
            RpcContext.getServerContext().clearAttachments();// 清除attachments
        }
    }
}
