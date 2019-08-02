package com.alibaba.dubbo.examples.extension;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Protocol;

public class ExtensionProtocolTest {
    public static void main(String[] args) {
        ExtensionLoader<Protocol> extensionLoader = ExtensionLoader.getExtensionLoader(Protocol.class);

        Protocol dubbo = extensionLoader.getDefaultExtension();
        int defaultPort = dubbo.getDefaultPort();
        System.out.println(defaultPort);


    }
}
