package com.alibaba.dubbo.examples.extension;

public class TableParser  implements Parser {
    @Override
    public void parser(String str) {
        System.out.println(TableParser.class.getName());
    }
}
