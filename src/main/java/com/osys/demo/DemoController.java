package com.osys.demo;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping(path = "/")
public class DemoController {

    @Resource
    private DemoService demoService;

    @RequestMapping(path = {"/demo"}, method = {RequestMethod.POST}, produces = {"application/json;charset=UTF-8"})
    public String demo(HttpServletRequest request, HttpServletResponse response) {
        demoService.demo();
        return "{}";
    }
}
