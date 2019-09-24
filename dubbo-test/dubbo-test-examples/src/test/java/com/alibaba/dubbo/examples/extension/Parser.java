package com.alibaba.dubbo.examples.extension;

import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

@SPI("text")
public interface Parser {

   
    void  parser(String str);
}
