package com.osys.redislock.demo.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osys.redislock.demo.service.DemoService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;

/**
 * <p><b>{@link DemoController} Description</b>:
 * </p>
 *
 * @author osys
 */
@RestController(value = "demoController")
@RequestMapping(path = "/")
public class DemoController {

    @Resource(name = "demoService")
    private DemoService demoService;

    @RequestMapping(path = {"/demo"}, method = {RequestMethod.POST}, produces = {"application/json;charset=UTF-8"})
    public String demo(HttpServletRequest request, HttpServletResponse response) {
        demoService.demo();
        return "{}";
    }

    /** 一个线程，获取+释放redis锁（不存在锁竞争） */
    @RequestMapping(path = {"/oneThreadGetAndReleaseReentrantLock"}, method = {RequestMethod.POST})
    public String oneThreadGetAndReleaseReentrantLock(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ExecutionException, InterruptedException {
        JsonObject requestParams = getParams(request);
        return demoService.oneThreadGetAndReleaseReentrantLock(requestParams, true).toString();
    }

    /** 多个线程，获取+释放redis锁（存在锁竞争） */
    @RequestMapping(path = {"/multipleThreadGetAndReleaseReentrantLock"}, method = {RequestMethod.POST})
    public String multipleThreadGetAndReleaseReentrantLock(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ExecutionException, InterruptedException {
        JsonObject requestParams = getParams(request);
        return demoService.multipleThreadGetAndReleaseReentrantLock(requestParams, true).toString();
    }

    /** 获取参数 */
    public JsonObject getParams(HttpServletRequest request) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()));
        StringBuilder stringBuilder = new StringBuilder();
        String requestParamsStr;
        while ((requestParamsStr = reader.readLine()) != null) {
            stringBuilder.append(requestParamsStr);
        }
        JsonObject requestParamsObj = null;
        JsonObject result = new JsonObject();
        String toString = stringBuilder.toString();
        try {
            if (!"".equals(toString)) {
                requestParamsObj = new JsonParser().parse(toString).getAsJsonObject();
            }
        } catch (Exception e) {
            String[] keyValues = toString.split("&");
            for (String keyValue : keyValues) {
                String[] split = keyValue.split("=");
                try {
                    result.addProperty(split[0], split[1]);
                } catch (Exception ignored) {
                }
            }
            return result;
        }
        if (requestParamsObj == null) {
            requestParamsObj = new JsonObject();
        }
        if (requestParamsObj.size() == 0) {
            Enumeration<String> names = request.getParameterNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                requestParamsObj.addProperty(name, request.getParameter(name));
            }
        }
        return requestParamsObj;
    }
}
