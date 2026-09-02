package com.an.feige.feige.controller;

import com.an.feige.common.CityGeoService;
import com.an.feige.common.SignUtil;
import com.an.feige.feige.dto.BindLetterRequest;
import com.an.feige.feige.dto.ReplyLetterRequest;
import com.an.feige.feige.dto.ReportRequest;
import com.an.feige.feige.dto.SendLetterRequest;
import com.an.feige.feige.entity.FeigePigeon;
import com.an.feige.feige.entity.PigeonRole;
import com.an.feige.feige.entity.FeigeOrder;
import com.an.feige.feige.service.FeigeLetterService;
import com.an.feige.feige.service.FeigePayService;
import com.an.feige.feige.service.FeigePigeonService;
import com.an.feige.feige.service.FeigeReportService;
import com.an.feige.feige.service.QiniuUploadService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞鸽传书-微信小程序接口。
 *
 * <p>路由前缀 /feige，统一返回 {code, msg, data, errorKey}。
 * 写操作（send/bind/recall/reply/subscribe）走 sign 签名校验；
 * 分享公开参数使用 shareToken；正文与精确坐标仅在拆信后返回。</p>
 */
@Api(tags = "飞鸽传书")
@RestController
@RequestMapping("/feige")
public class FeigeController {

    @Autowired
    private FeigeLetterService feigeLetterService;

    @Autowired
    private FeigePigeonService feigePigeonService;

    @Autowired
    private FeigeReportService feigeReportService;

    @Autowired
    private FeigePayService feigePayService;

    @Autowired
    private QiniuUploadService qiniuUploadService;

    @Autowired
    private SignUtil feigeSignUtil;

    @Autowired
    private CityGeoService feigeCityGeoService;

    // -------------------- 写信并放飞（发送即起飞） --------------------
    @ApiOperation("写信并放飞")
    @PostMapping("/letter/send")
    @ResponseBody
    public Map<String, Object> send(@RequestBody SendLetterRequest req,
                                    HttpServletRequest request) {
        if (!sign(request, req.getOpenid())) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        // 经纬度可选：缺失时由服务端按 province/city 从内置行政区划坐标表兜底
        BigDecimal[] coord = feigeCityGeoService.resolve(
                req.getProvince(), req.getCity(), req.getLat(), req.getLng());
        if (coord == null) {
            return err(400, "缺少定位信息", "INVALID_ARGUMENT");
        }
        return feigeLetterService.send(req.getOpenid(), req.getTitle(), req.getContent(),
                req.getImageUrl(), req.getProvince(), req.getCity(),
                coord[0], coord[1], req.getPigeonId(), req.getSignature());
    }

    // -------------------- 分享预览（不产生认领/状态变更） --------------------
    @ApiOperation("分享预览")
    @GetMapping("/letter/share-preview")
    @ResponseBody
    public Map<String, Object> sharePreview(@RequestParam(name = "shareToken", required = true) String shareToken,
                                            @RequestParam(name = "openid", required = false) String openid) {
        return feigeLetterService.sharePreview(shareToken, openid);
    }

    // -------------------- 收件人原子认领 --------------------
    @ApiOperation("收件人认领(定位)")
    @PostMapping("/letter/bind")
    @ResponseBody
    public Map<String, Object> bind(@RequestBody BindLetterRequest req,
                                     HttpServletRequest request) {
        if (!sign(request, req.getOpenid())) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        // 经纬度缺失时按 province/city 从内置行政区划坐标表兜底（与 send/reply 一致）
        BigDecimal[] coord = feigeCityGeoService.resolve(
                req.getProvince(), req.getCity(), req.getLat(), req.getLng());
        if (coord == null) {
            return err(400, "缺少定位信息", "INVALID_ARGUMENT");
        }
        return feigeLetterService.claim(req.getShareToken(), req.getOpenid(),
                req.getProvince(), req.getCity(), coord[0], coord[1]);
    }

    // -------------------- 发件人免费召回 --------------------
    @ApiOperation("发件人召回")
    @PostMapping("/letter/recall")
    @ResponseBody
    public Map<String, Object> recall(@RequestParam(name = "letterId", required = true) String letterId,
                                      @RequestParam(name = "openid", required = true) String openid,
                                      HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        return feigeLetterService.recall(letterId, openid);
    }

    // -------------------- 飞行页 --------------------
    @ApiOperation("飞行页")
    @GetMapping("/letter/flight")
    @ResponseBody
    public Map<String, Object> flight(@RequestParam(name = "letterId", required = true) String letterId,
                                      @RequestParam(name = "openid", required = true) String openid) {
        return feigeLetterService.flight(letterId, openid);
    }

    // -------------------- 收信/拆信 --------------------
    @ApiOperation("收信/拆信")
    @GetMapping("/letter/detail")
    @ResponseBody
    public Map<String, Object> detail(@RequestParam(name = "letterId", required = true) String letterId,
                                      @RequestParam(name = "openid", required = true) String openid) {
        return feigeLetterService.detail(letterId, openid);
    }

    // -------------------- 回信 --------------------
    @ApiOperation("回信")
    @PostMapping("/letter/reply")
    @ResponseBody
    public Map<String, Object> reply(@RequestBody ReplyLetterRequest req,
                                     HttpServletRequest request) {
        if (!sign(request, req.getOpenid())) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        // 经纬度缺失时按 province/city 从内置行政区划坐标表兜底
        BigDecimal[] coord = feigeCityGeoService.resolve(
                req.getProvince(), req.getCity(), req.getLat(), req.getLng());
        if (coord == null) {
            return err(400, "缺少定位信息", "INVALID_ARGUMENT");
        }
        return feigeLetterService.reply(req.getOpenid(), req.getTitle(), req.getContent(),
                req.getImageUrl(), req.getProvince(), req.getCity(),
                coord[0], coord[1], req.getSignature(), req.getLetterId());
    }

    // -------------------- 订阅到达通知 --------------------
    @ApiOperation("订阅到达通知(ARRIVAL当前鸽子抵达/REPLY_ARRIVAL回信抵达)")
    @PostMapping("/letter/subscribe")
    @ResponseBody
    public Map<String, Object> subscribe(@RequestParam(name = "openid", required = true) String openid,
                                         @RequestParam(name = "letterId", required = true) String letterId,
                                         @RequestParam(name = "type", required = false) String type,
                                         HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        return feigeLetterService.subscribe(letterId, openid, type);
    }

    // -------------------- 信箱列表（来信/寄出） --------------------
    @ApiOperation("信箱列表(来信/寄出)")
    @GetMapping("/letter/list")
    @ResponseBody
    public Map<String, Object> letterList(@RequestParam(name = "openid", required = true) String openid,
                                          @RequestParam(name = "type", required = false, defaultValue = "inbox") String type,
                                          @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                                          @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return feigeLetterService.listLetters(openid, type, page, size);
    }

    // -------------------- 内容投诉（规格17.1） --------------------
    @ApiOperation("内容投诉")
    @PostMapping("/report")
    @ResponseBody
    public Map<String, Object> report(@RequestBody ReportRequest req,
                                      HttpServletRequest request) {
        if (!sign(request, req.getOpenid())) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        Long reportId = feigeReportService.report(req.getLetterId(), req.getOpenid(),
                req.getReason(), req.getDescription());
        if (reportId == null) {
            return err(400, "投诉参数错误", "INVALID_ARGUMENT");
        }
        return ok(field("reportId", reportId));
    }

    // -------------------- 我的鸽子 --------------------
    @ApiOperation("我的鸽子")
    @GetMapping("/pigeon/mine")
    @ResponseBody
    public Map<String, Object> mine(@RequestParam(name = "openid", required = true) String openid) {
        FeigePigeon pigeon = feigePigeonService.getOrInitByOpenid(openid);
        Map<String, Object> data = new HashMap<>();
        data.put("name", pigeon.getName());
        data.put("level", pigeon.getLevel());
        data.put("exp", pigeon.getExp());
        data.put("expNext", pigeon.getLevel() * 100);
        data.put("speedKmh", pigeon.getSpeedKmh());
        data.put("stamina", pigeon.getStamina());
        data.put("deliveredCount", pigeon.getDeliveredCount());
        data.put("totalMileage", pigeon.getTotalMileage());
        data.put("farthestDistance", pigeon.getFarthestDistance());
        data.put("status", pigeon.getStatus());
        data.put("motto", "它已经认识回家的路了。");
        return ok(data);
    }

    // -------------------- 鸽舍（多鸽，规格14.3/16.3） --------------------
    @ApiOperation("鸽舍列表")
    @GetMapping("/pigeon/list")
    @ResponseBody
    public Map<String, Object> pigeonList(@RequestParam(name = "openid", required = true) String openid) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (FeigePigeon pigeon : feigePigeonService.listByOpenid(openid)) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", pigeon.getId());
            item.put("name", pigeon.getName());
            item.put("roleKey", pigeon.getRoleKey());
            item.put("level", pigeon.getLevel());
            item.put("exp", pigeon.getExp());
            item.put("speedKmh", pigeon.getSpeedKmh());
            item.put("stamina", pigeon.getStamina());
            item.put("deliveredCount", pigeon.getDeliveredCount());
            item.put("totalMileage", pigeon.getTotalMileage());
            item.put("farthestDistance", pigeon.getFarthestDistance());
            item.put("status", pigeon.getStatus());
            item.put("motto", PigeonRole.motto(pigeon.getRoleKey()));
            list.add(item);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", list.size());
        return ok(data);
    }

    @ApiOperation("创建角色鸽子")
    @PostMapping("/pigeon/create")
    @ResponseBody
    public Map<String, Object> pigeonCreate(@RequestParam(name = "openid", required = true) String openid,
                                            @RequestParam(name = "roleKey", required = true) String roleKey,
                                            HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        FeigePigeon pigeon = feigePigeonService.createByRole(openid, roleKey);
        if (pigeon == null) {
            return err(409, "角色不可用或已拥有", "ROLE_UNAVAILABLE");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("id", pigeon.getId());
        data.put("name", pigeon.getName());
        data.put("roleKey", pigeon.getRoleKey());
        data.put("status", pigeon.getStatus());
        return ok(data);
    }

    @ApiOperation("鸽子改名")
    @PostMapping("/pigeon/rename")
    @ResponseBody
    public Map<String, Object> pigeonRename(@RequestParam(name = "openid", required = true) String openid,
                                            @RequestParam(name = "pigeonId", required = true) Long pigeonId,
                                            @RequestParam(name = "name", required = true) String name,
                                            HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        FeigePigeon pigeon = feigePigeonService.getById(pigeonId);
        if (pigeon == null || !openid.equals(pigeon.getOpenid())) {
            return err(404, "鸽子不存在", "PIGEON_NOT_FOUND");
        }
        // 规格3.2：首次送达后才邀请改名；未送达过不允许
        if (pigeon.getDeliveredCount() == null || pigeon.getDeliveredCount() <= 0) {
            return err(403, "完成第一次旅程后才能改名", "RENAME_NOT_ALLOWED");
        }
        int rows = feigePigeonService.rename(pigeonId, name);
        if (rows <= 0) {
            return err(400, "名字不合法", "INVALID_ARGUMENT");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("id", pigeonId);
        data.put("name", name.trim().length() > 12 ? name.trim().substring(0, 12) : name.trim());
        data.put("renamed", true);
        return ok(data);
    }

    @ApiOperation("鸽子旅程履历")
    @GetMapping("/pigeon/journeys")
    @ResponseBody
    public Map<String, Object> pigeonJourneys(@RequestParam(name = "openid", required = true) String openid,
                                              @RequestParam(name = "pigeonId", required = true) Long pigeonId) {
        FeigePigeon pigeon = feigePigeonService.getById(pigeonId);
        if (pigeon == null || !openid.equals(pigeon.getOpenid())) {
            return err(404, "鸽子不存在", "PIGEON_NOT_FOUND");
        }
        return feigePigeonService.journeys(pigeon);
    }

    // -------------------- 多鸽付费购买（V1.2，规格15） --------------------
    @ApiOperation("鸽舍槽位(空位置/候选角色/价格)")
    @GetMapping("/pigeon/slots")
    @ResponseBody
    public Map<String, Object> pigeonSlots(@RequestParam(name = "openid", required = true) String openid) {
        return ok(feigePayService.slots(openid));
    }

    @ApiOperation("创建购买订单(购买空位置+选择角色入住)")
    @PostMapping("/pigeon/order")
    @ResponseBody
    public Map<String, Object> pigeonOrder(@RequestParam(name = "openid", required = true) String openid,
                                           @RequestParam(name = "slotIndex", required = true) Integer slotIndex,
                                           @RequestParam(name = "roleKey", required = true) String roleKey,
                                           HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        Map<String, Object> order = feigePayService.createOrder(openid, slotIndex, roleKey);
        if (order == null) {
            return err(409, "下单失败：开关关闭/位置不可售/角色已拥有/位置已占用", "ORDER_CREATE_FAILED");
        }
        return ok(order);
    }

    @ApiOperation("支付确认(mock/回调统一入口)")
    @PostMapping("/pigeon/confirm")
    @ResponseBody
    public Map<String, Object> pigeonConfirm(@RequestParam(name = "openid", required = true) String openid,
                                             @RequestParam(name = "orderNo", required = true) String orderNo,
                                             @RequestParam(name = "payTradeNo", required = false) String payTradeNo,
                                             HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        FeigeOrder order = feigePayService.getOrder(orderNo);
        if (order == null || !openid.equals(order.getOpenid())) {
            return err(404, "订单不存在", "ORDER_NOT_FOUND");
        }
        // mock 支付模式（资格申请中）：凭证未配时 confirm 直接确认；生产走微信回调
        Map<String, Object> result = feigePayService.confirmPaid(orderNo, payTradeNo);
        if (result == null) {
            return err(409, "订单状态不允许确认", "ORDER_STATE_INVALID");
        }
        return ok(result);
    }

    @ApiOperation("微信支付回调(服务端对服务端)")
    @PostMapping("/pay/callback")
    @ResponseBody
    public Map<String, Object> payCallback(@RequestBody Map<String, Object> body) {
        // 微信支付回调（资格开通后接入验签；当前 mock 模式直接按 orderNo 确认）
        String orderNo = body == null ? null : String.valueOf(body.get("orderNo"));
        String payTradeNo = body == null ? null : String.valueOf(body.get("payTradeNo"));
        if (orderNo == null) {
            return err(400, "参数错误", "INVALID_ARGUMENT");
        }
        Map<String, Object> result = feigePayService.confirmPaid(orderNo, payTradeNo);
        if (result == null) {
            return err(409, "订单状态不允许", "ORDER_STATE_INVALID");
        }
        return ok(result);
    }

    @ApiOperation("我的购买订单")
    @GetMapping("/pigeon/orders")
    @ResponseBody
    public Map<String, Object> pigeonOrders(@RequestParam(name = "openid", required = true) String openid) {
        return ok(feigePayService.ordersOf(openid));
    }

    // -------------------- 七牛上传凭证 --------------------
    @ApiOperation("获取七牛上传凭证")
    @PostMapping("/upload/token")
    @ResponseBody
    public Map<String, Object> uploadToken(@RequestParam(name = "suffix[]", required = false) String[] suffix) {
        List<Map<String, Object>> tokens = qiniuUploadService.uploadTokens(suffix);
        if (tokens.isEmpty()) {
            return err(500, "七牛凭证未配置", "QINIU_NOT_CONFIGURED");
        }
        return ok(tokens);
    }

    // -------------------- 内部工具 --------------------

    /** 小程序签名校验：sign = md5(openid + sign-secret)，同登录接口返回的一致。 */
    private boolean sign(HttpServletRequest request, String openid) {
        String sign = request.getHeader("sign");
        if (StringUtils.isBlank(sign)) {
            return false;
        }
        return feigeSignUtil.verify(openid, sign);
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", 200);
        map.put("msg", "success");
        map.put("data", data);
        return map;
    }

    private Map<String, Object> err(int code, String msg, String errorKey) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", code);
        map.put("msg", msg);
        map.put("errorKey", errorKey);
        map.put("data", null);
        return map;
    }

    private Map<String, Object> field(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}