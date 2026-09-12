package com.an.feige.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理：统一 {code,msg,data,errorKey} 信封。
 *
 * <p>场景：multipart 解析（如上传图片超过 {@code spring.servlet.multipart.max-file-size}）发生在
 * 控制器方法映射之前，控制器内的 {@code @ExceptionHandler} 接不到，会退化为 Spring Boot 默认 500；
 * 这里统一转成带 errorKey 的 400，便于前端提示。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 上传文件超过 multipart 上限。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Map<String, Object> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过 multipart 上限: {}", e.getMessage());
        Map<String, Object> map = new HashMap<>();
        map.put("code", 400);
        map.put("msg", "上传文件过大");
        map.put("errorKey", "FILE_TOO_LARGE");
        map.put("data", null);
        return map;
    }
}
