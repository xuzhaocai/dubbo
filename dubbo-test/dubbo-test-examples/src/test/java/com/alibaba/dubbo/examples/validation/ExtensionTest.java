package com.alibaba.dubbo.examples.validation;

import com.alibaba.dubbo.common.extension.ExtensionFactory;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.examples.extension.Parser;

public class ExtensionTest {


    public static void main(String[] args) {
        ExtensionLoader<Parser> extensionLoader = ExtensionLoader.getExtensionLoader(Parser.class);


        Parser textParser = extensionLoader.getExtension("text");
        Parser defaultExtension = extensionLoader.getDefaultExtension();


        System.out.println(defaultExtension);
        textParser.parser("ss");
    }
}
