package com.an.feige.feige.service;

import com.alibaba.fastjson.JSON;
import com.an.feige.common.WxXPayClient;
import com.an.feige.feige.entity.FeigeOrder;
import com.an.feige.feige.entity.FeigePayGoods;
import com.an.feige.feige.entity.FeigePigeon;
import com.an.feige.feige.entity.PigeonRole;
import com.an.feige.feige.mapper.FeigeOrderMapper;
import com.an.feige.feige.mapper.FeigePayGoodsMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 飞鸽传书-多鸽付费购买服务（规格15，V1.2 真实虚拟支付）。
 *
 * <p>价格绑定「第几个鸽舍位置」（15.3）；支付结果以后端回调为准（15.5）；
 * 订单与权益发放幂等（重复回调不重复创建）；退款不删除历史，鸽子继续可用（15.5）。
 * 开关 PAID_PIGEON_ENABLED（15.6）：关→第2~6只免费创建（无资格期兼容）；开→必须购买。</p>
 *
 * <p>支付模型（真实虚拟支付）：</p>
 * <ol>
 *   <li>下单：生成 outTradeNo(=orderNo) 落库 CREATED，返回 payData（signData/paySig/signature/mode）供前端 wx.requestVirtualPayment；</li>
 *   <li>支付成功：微信平台推送 xpay_goods_deliver_notify → 本服务确认（幂等 CREATED→PAID 并发放权益）；</li>
 *   <li>兜底：定时 query_order 查单，确认支付后补发货并 notify_provide_goods 上报（推送丢失场景）；</li>
 *   <li>前端轮询 GET /feige/order/status 获取最终状态。</li>
 * </ol>
 */
@Service
public class FeigePayService {

    private static final Logger log = LoggerFactory.getLogger(FeigePayService.class);
    /** 免费位置数（第1只小白免费，规格15.1）。 */
    private static final int FREE_SLOTS = 1;
    /** 每用户最多6只（规格15.1）。 */
    private static final int MAX_SLOTS = 6;
    /** outTradeNo 长度需 8~32（字符集 数字/字母/_-|*@，不能 _ 开头）。 */
    private static final int ORDER_NO_MAX = 32;

    @Resource
    private FeigeOrderMapper feigeOrderMapper;

    @Resource
    private FeigePayGoodsMapper feigePayGoodsMapper;

    @Resource
    private FeigePigeonService feigePigeonService;

    @Resource
    private WxXPayClient wxXPayClient;

    @Value("${feige.pigeon.paid-enabled:false}")
    private boolean paidEnabled;

    @Value("${feige.pay.mock-pay:true}")
    private boolean mockPay;

    @Value("${feige.pay.offer-id:}")
    private String offerId;

    /** 前端支付签名用固定 uri（官方 wx.requestVirtualPayment 约定）。 */
    private static final String URI_PAY = "requestVirtualPayment";

    /**
     * 鸽舍槽位（规格16.3/15.4）：第1位小白；空位置展示候选角色与价格。
     *
     * @return { slots:[{ index, roleKey|null, name, status, amountFen, paid }], freeCount, maxSlots }
     */
    public Map<String, Object> slots(String openid) {
        // 物理 6 位置（规格15.1/15.3：价格绑定位置，不绑定角色）
        List<FeigePigeon> owned = feigePigeonService.listByOpenid(openid);
        Map<Integer, FeigePigeon> ownedBySlot = new HashMap<>();
        Map<String, FeigePigeon> ownedByRole = new HashMap<>();
        for (FeigePigeon p : owned) {
            ownedBySlot.put(p.getSlotIndex() == null ? 0 : p.getSlotIndex(), p);
            ownedByRole.put(p.getRoleKey(), p);
        }

        // 商品配置（V5.1 起以 feige_pay_goods 表为准：价格/道具 productId 都从表读）
        Map<Integer, FeigePayGoods> goodsBySlot = goodsBySlot();

        List<Map<String, Object>> slots = new ArrayList<>();
        for (int slotIndex = 1; slotIndex <= MAX_SLOTS; slotIndex++) {
            FeigePayGoods goods = goodsBySlot.get(slotIndex);
            int priceFen = goods == null ? 0 : goods.getPriceFen();
            FeigePigeon pigeon = ownedBySlot.get(slotIndex);
            Map<String, Object> slot = new HashMap<>();
            slot.put("index", slotIndex);
            slot.put("amountFen", slotIndex > FREE_SLOTS ? priceFen : 0);
            // V5.1：收费槽位是否已配置商品（表未配置则前端禁用购买，防止无价下单）
            slot.put("goodsConfigured", slotIndex <= FREE_SLOTS || goods != null);
            if (pigeon != null) {
                slot.put("roleKey", pigeon.getRoleKey());
                slot.put("name", pigeon.getName());
                slot.put("status", pigeon.getStatus());
                slot.put("deliveredCount", pigeon.getDeliveredCount());
                slot.put("totalMileage", pigeon.getTotalMileage());
                slot.put("paid", slotIndex > FREE_SLOTS);
                slot.put("occupied", true);
            } else {
                slot.put("roleKey", null);
                slot.put("name", null);
                slot.put("status", "EMPTY");
                slot.put("deliveredCount", 0);
                slot.put("totalMileage", 0);
                slot.put("paid", false);
                slot.put("occupied", false);
            }
            slots.add(slot);
        }

        // 候选角色：尚未拥有的全部角色（规格15.5：购买位置后从候选角色中选择入住）
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : PigeonRole.all().entrySet()) {
            String roleKey = entry.getKey();
            if (ownedByRole.containsKey(roleKey)) {
                continue;
            }
            Map<String, Object> cand = new HashMap<>();
            cand.put("roleKey", roleKey);
            cand.put("name", PigeonRole.defaultName(roleKey));
            cand.put("motto", PigeonRole.motto(roleKey));
            candidates.add(cand);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("slots", slots);
        data.put("candidates", candidates);
        data.put("freeCount", Math.max(0, MAX_SLOTS - owned.size()));
        data.put("maxSlots", MAX_SLOTS);
        data.put("paidEnabled", paidEnabled);
        data.put("mockPay", mockPay && !wxXPayClient.configured());
        data.put("xpayConfigured", wxXPayClient.configured());
        return data;
    }

    /**
     * 创建购买订单（规格15.5：创建订单→支付→回调确认）。
     *
     * <p>真实虚拟支付：已配置 xpay 时返回 payData 供前端拉起 wx.requestVirtualPayment；
     * 未配置/开关 mock 时保持原 mock 语义（前端走 confirm）。</p>
     *
     * @return null 表示参数/状态非法；否则 { orderNo, roleKey, slotIndex, amountFen, status, [payData] }
     */
    public Map<String, Object> createOrder(String openid, Integer slotIndex, String roleKey) {
        if (!paidEnabled) {
            log.info("付费开关关闭，跳过下单 openid={} slot={}", openid, slotIndex);
            return null;
        }
        if (!PigeonRole.isValid(roleKey) || FeigePigeon.ROLE_XIAOBAI.equals(roleKey)) {
            return null;
        }
        // 位置 2~6 可购买（规格15.3：价格绑定位置；位置1小白免费不售）
        if (slotIndex == null || slotIndex <= FREE_SLOTS || slotIndex > MAX_SLOTS) {
            return null;
        }
        if (feigePigeonService.getByOpenidAndRole(openid, roleKey) != null) {
            return null;
        }
        // 目标位置必须为空（买的是空位置）
        if (feigePigeonService.getByOpenidAndSlot(openid, slotIndex) != null) {
            return null;
        }
        // 防重复下单：该角色或该位置存在未完成订单（CREATED/PAID 均拦截，避免占位重复）
        FeigeOrder existRole = feigeOrderMapper.selectLatestByOpenidAndRole(openid, roleKey);
        if (existRole != null && !FeigeOrder.STATUS_CANCELLED.equals(existRole.getStatus())
                && !FeigeOrder.STATUS_REFUNDED.equals(existRole.getStatus())) {
            return null;
        }
        FeigeOrder existSlot = feigeOrderMapper.selectLatestByOpenidAndSlot(openid, slotIndex);
        if (existSlot != null && !FeigeOrder.STATUS_CANCELLED.equals(existSlot.getStatus())
                && !FeigeOrder.STATUS_REFUNDED.equals(existSlot.getStatus())) {
            return null;
        }
        // V5.1：价格/道具以 feige_pay_goods 表为准——表未配置该槽位商品则拒绝下单（防无价单/错误 productId）
        FeigePayGoods goods = feigePayGoodsMapper.selectBySlotIndex(slotIndex);
        if (goods == null) {
            log.warn("下单拒绝：槽位{}未配置商品(feige_pay_goods) openid={} roleKey={}", slotIndex, openid, roleKey);
            return null;
        }
        int amountFen = goods.getPriceFen();

        Date now = new Date();
        FeigeOrder order = new FeigeOrder();
        order.setOrderNo(newOrderNo());
        order.setOpenid(openid);
        order.setRoleKey(roleKey);
        order.setSlotIndex(slotIndex);
        order.setAmountFen(amountFen);
        order.setStatus(FeigeOrder.STATUS_CREATED);
        order.setCreateAt(now);
        order.setUpdateAt(now);
        feigeOrderMapper.insertSelective(order);

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("roleKey", roleKey);
        data.put("slotIndex", slotIndex);
        data.put("amountFen", amountFen);
        data.put("status", FeigeOrder.STATUS_CREATED);
        data.put("mockPay", mockPay && !wxXPayClient.configured());
        if (wxXPayClient.configured()) {
            // signature(用户态签名) 依赖当次有效 session_key，由 Controller 层拿到 session_key 后补算
            Map<String, Object> payData = buildPayData(order, amountFen, null);
            data.put("payData", payData);
        }
        return data;
    }

    /**
     * 构造 wx.requestVirtualPayment 所需 payData（signData/paySig/signature/mode/offerId）。
     *
     * <p>signature 用户态签名需要 session_key —— 由调用方（前端下单时带 session_key，或后端从 fg_user 取）
     * 提供；当前 sessionKey 参数为空时仅返回 signData/paySig（前端可自行算 signature 或后端补齐）。</p>
     *
     * <p>注意：signData 的 JSON 键序必须与 paySig 签名时一致（TreeMap 保证升序）。</p>
     */
    public Map<String, Object> buildPayData(FeigeOrder order, int amountFen, String sessionKey) {
        // V5.1：道具 productId 从 feige_pay_goods 表读（下单时已校验存在，这里兜底再查一次）
        FeigePayGoods goods = feigePayGoodsMapper.selectBySlotIndex(order.getSlotIndex());
        String productId = goods == null ? null : goods.getProductId();
        if (StringUtils.isBlank(productId)) {
            log.warn("槽位{}未配置道具productId(feige_pay_goods), 支付将失败 slot={} orderNo={}",
                    order.getSlotIndex(), order.getSlotIndex(), order.getOrderNo());
        }
        java.util.TreeMap<String, Object> signMap = new java.util.TreeMap<>();
        signMap.put("offerId", offerId);
        signMap.put("buyQuantity", 1);
        signMap.put("env", 0);
        signMap.put("currencyType", "CNY");
        signMap.put("productId", StringUtils.defaultString(productId));
        signMap.put("goodsPrice", amountFen);
        signMap.put("outTradeNo", order.getOrderNo());
        signMap.put("attach", order.getRoleKey());
        String signData = JSON.toJSONString(signMap);
        String paySig = wxXPayClient.paySig(URI_PAY, signData);

        Map<String, Object> payData = new LinkedHashMap<>();
        payData.put("signData", signData);
        payData.put("paySig", paySig);
        payData.put("mode", "short_series_goods");
        payData.put("outTradeNo", order.getOrderNo());
        if (StringUtils.isNotBlank(sessionKey)) {
            payData.put("signature", org.apache.commons.codec.digest.HmacUtils.hmacSha256Hex(sessionKey, signData));
        }
        return payData;
    }

    /** 全量商品配置按槽位建索引（slots 展示用）。 */
    private Map<Integer, FeigePayGoods> goodsBySlot() {
        Map<Integer, FeigePayGoods> map = new HashMap<>();
        for (FeigePayGoods g : feigePayGoodsMapper.selectAll()) {
            map.put(g.getSlotIndex(), g);
        }
        return map;
    }

    /**
     * 支付确认（统一入口：mock 支付 / xpay 发货推送 / 查单兜底都走这里）。
     * 幂等：订单 CREATED→PAID 仅一次生效；PAID 后发放权益（创建鸽子）同样幂等。
     *
     * @param payTradeNo 支付平台交易号（xpay TransactionId；mock 时可为空）
     * @return 支付确认结果；订单不存在/状态非法返回 null
     */
    @Transactional
    public Map<String, Object> confirmPaid(String orderNo, String payTradeNo) {
        FeigeOrder order = feigeOrderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return null;
        }
        if (FeigeOrder.STATUS_PAID.equals(order.getStatus())) {
            // 重复回调：幂等返回当前状态（不重复发放权益）
            return orderResult(order);
        }
        if (!FeigeOrder.STATUS_CREATED.equals(order.getStatus())) {
            return null;
        }

        Date now = new Date();
        int rows = feigeOrderMapper.markPaid(orderNo, payTradeNo, now, now);
        if (rows <= 0) {
            FeigeOrder latest = feigeOrderMapper.selectByOrderNo(orderNo);
            return latest == null ? null : orderResult(latest);
        }

        // 权益发放：已支付才创建鸽子并入住订单位置（幂等：grantByRole 内部查重+位置占用检查）
        FeigePigeon pigeon = feigePigeonService.grantByRole(order.getOpenid(), order.getRoleKey(),
                order.getSlotIndex());
        if (pigeon == null) {
            log.warn("权益发放失败(可能已拥有/位置已占/超上限) orderNo={} roleKey={} slot={}",
                    orderNo, order.getRoleKey(), order.getSlotIndex());
        }
        log.info("支付确认成功 orderNo={} roleKey={} slot={} openid={}",
                orderNo, order.getRoleKey(), order.getSlotIndex(), order.getOpenid());
        // 返回最新订单状态（避免内存旧对象显示 CREATED）
        FeigeOrder latest = feigeOrderMapper.selectByOrderNo(orderNo);
        return orderResult(latest == null ? order : latest);
    }

    /**
     * xpay 发货推送/查单兜底统一处理：确认支付 → 上报发货完成。
     * 幂等：已 PAID/已处理直接成功。
     *
     * @return true=处理成功（可回 success 给微信）
     */
    public boolean confirmFromXPayNotify(String orderNo, String transactionId) {
        if (StringUtils.isBlank(orderNo)) {
            return false;
        }
        Map<String, Object> result = confirmPaid(orderNo, transactionId);
        if (result == null) {
            FeigeOrder order = feigeOrderMapper.selectByOrderNo(orderNo);
            // 未知订单号不应回成功（微信会重试也没意义），返回 false
            return order != null;
        }
        // 主动上报发货完成（参考 search111：兜底/推送确认后都调 notify_provide_goods，避免微信侧一直等发货）
        wxXPayClient.notifyProvideGoods(orderNo);
        return true;
    }

    /**
     * 退款（规格15.5：不删除历史；若鸽子正在执行任务，当前旅程完成后再停止发起新任务——V1.2 仅记录状态）。
     * 幂等：仅 PAID 可退款。
     */
    public boolean refund(String orderNo) {
        FeigeOrder order = feigeOrderMapper.selectByOrderNo(orderNo);
        if (order == null || !FeigeOrder.STATUS_PAID.equals(order.getStatus())) {
            return false;
        }
        Date now = new Date();
        return feigeOrderMapper.markRefunded(orderNo, now, now) > 0;
    }

    /** 查询订单（鸽舍购买记录展示）。 */
    public List<FeigeOrder> ordersOf(String openid) {
        return feigeOrderMapper.selectByOpenid(openid);
    }

    /** 按订单号查订单（Controller 归属校验用）。 */
    public FeigeOrder getOrder(String orderNo) {
        return feigeOrderMapper.selectByOrderNo(orderNo);
    }

    /** 订单状态查询（前端轮询用；仅本人可查已在 Controller 校验）。 */
    public Map<String, Object> orderStatus(String orderNo) {
        FeigeOrder order = feigeOrderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return null;
        }
        return orderResult(order);
    }

    public boolean isPaidEnabled() {
        return paidEnabled;
    }

    /** 真实虚拟支付是否已配置（offer-id + app-key 齐全）。 */
    public boolean xpayConfigured() {
        return wxXPayClient.configured();
    }

    private Map<String, Object> orderResult(FeigeOrder order) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("roleKey", order.getRoleKey());
        data.put("slotIndex", order.getSlotIndex());
        data.put("amountFen", order.getAmountFen());
        data.put("status", order.getStatus());
        data.put("paid", FeigeOrder.STATUS_PAID.equals(order.getStatus()));
        return data;
    }

    /** 业务订单号：8~32 位，字母/数字/_-|*@，不以 _ 开头。格式 OD+yyMMddHHmmssSSS+3位随机 ≈ 21 位。 */
    private String newOrderNo() {
        String ts = new SimpleDateFormat("yyMMddHHmmssSSS").format(new Date());
        int rand = new Random().nextInt(900) + 100;
        String no = "OD" + ts + rand;
        return no.length() <= ORDER_NO_MAX ? no : no.substring(0, ORDER_NO_MAX);
    }
}
