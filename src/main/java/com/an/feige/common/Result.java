package com.an.feige.common;

import java.io.Serializable;

/**
 * 统一响应信封 { code, msg, errorKey, data }。
 * code==200 成功；非 200 附稳定 errorKey，前端勿依赖中文 msg。
 */
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;
    private String msg;
    private String errorKey;
    private T data;

    public Result() {
    }

    public Result(Integer code, String msg, String errorKey) {
        this.code = code;
        this.msg = msg;
        this.errorKey = errorKey;
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>(200, "success", null);
        r.setData(data);
        return r;
    }

    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }

    public static <T> Result<T> err(int code, String msg, String errorKey) {
        return new Result<>(code, msg, errorKey);
    }

    public void putData(String key, Object value) {
        // 兼容旧 Result.putData 风格：data 为空对象时写入字段
        if (data == null) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put(key, value);
            data = (T) map;
        } else if (data instanceof java.util.Map) {
            ((java.util.Map<String, Object>) data).put(key, value);
        }
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getErrorKey() {
        return errorKey;
    }

    public void setErrorKey(String errorKey) {
        this.errorKey = errorKey;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
