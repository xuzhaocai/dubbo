package com.alibaba.dubbo.examples.extension;

public class TextParser  implements  Parser {
    @Override
    public void parser(String str) {

        System.out.println(TextParser.class.getName());
    }
}
