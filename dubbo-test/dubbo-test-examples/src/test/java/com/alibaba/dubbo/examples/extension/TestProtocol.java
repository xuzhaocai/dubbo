package com.alibaba.dubbo.examples.extension;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;

public class TestProtocol  implements Protocol {
    @Override
    public int getDefaultPort() {
        return 100;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {

        System.out.println("export");
        return null;
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {

        System.out.println("refer");
        return null;
    }

    @Override
    public void destroy() {
        System.out.println("destroy");
    }
}
