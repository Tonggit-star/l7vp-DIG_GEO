package com.antv.l7vp.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 单 jar 部署：前端 dist 内嵌于 classpath:/static/，经中台 nginx 剥掉 /l7vp 后
 * 走默认 "/"（WelcomePageHandler 自动返回 index.html）；直连 3001 时
 * /l7vp/ 需显式转发到 /index.html（/l7vp/** 资源处理器只服务具体文件，不解析目录首页）。
 */
@Controller
public class SpaController {

    @GetMapping({"/l7vp", "/l7vp/"})
    public String spaIndex() {
        return "forward:/index.html";
    }
}
