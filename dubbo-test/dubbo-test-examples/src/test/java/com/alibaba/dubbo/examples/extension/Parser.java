package com.alibaba.dubbo.examples.extension;

import com.alibaba.dubbo.common.extension.SPI;

@SPI
public interface Parser {


    void  parser(String str);
}
