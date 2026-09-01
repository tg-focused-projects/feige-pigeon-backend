package com.an.feige.feige.service;

import com.an.feige.feige.mapper.FeigeLetterEventMapper;
import com.an.feige.feige.mapper.FeigeLetterMapper;
import com.an.feige.feige.mapper.FeigePigeonMapper;
import com.an.feige.feige.entity.FeigeLetter;
import com.an.feige.feige.entity.FeigeLetterEvent;
import com.an.feige.feige.entity.FeigePigeon;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 飞鸽传书-信件核心服务（V1.1 发送即起飞版）。
 *
 * <p>关键语义：发送即起飞（departure_time=服务器时间，status=FLYING_UNCLAIMED）；收件人认领时
 * 才根据<em>原始起飞时间</em>与两地距离计算时长/抵达；抵达由定时任务/查询兜底推进并<span>一次</span>结算；
 * 72h 未认领自动过期；30min 后可免费召回；拆信不再结算成长、只置已读与状态迁移。</p>
 */
@Service
public class FeigeLetterService {

    private static final BigDecimal EARTH_RADIUS_KM = new BigDecimal("6371.0");
    private static final int MAX_CONTENT_LEN = 500;
    private static final int CLAIM_EXPIRE_HOURS = 72;
    private static final long RECALL_GRACE_MS = 30L * 60 * 1000;
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Resource
    private FeigeLetterMapper feigeLetterMapper;

    @Resource
    private FeigeLetterEventMapper feigeLetterEventMapper;

    @Resource
    private FeigePigeonMapper feigePigeonMapper;

    @Autowired
    private FeigePigeonService feigePigeonService;

    @Autowired
    private FeigeLifecycleService feigeLifecycleService;

    // ============================ 写信并放飞（发送即起飞） ============================

    @Transactional
    public Map<String, Object> send(String openid, String content, String imageUrl,
                                    String province, String city, BigDecimal lat, BigDecimal lng, Long pigeonId) {
        FeigePigeon pigeon = pigeonId == null
                ? feigePigeonService.getOrInitByOpenid(openid)
                : feigePigeonService.getById(pigeonId);
        if (pigeon == null) {
            return err(202, "鸽子不存在", "PIGEON_BUSY");
        }
        // 事务内锁定鸽子，防止并发发送两封
        FeigePigeon locked = feigePigeonMapper.selectByPrimaryKeyForUpdate(pigeon.getId());
        if (locked == null || !FeigePigeon.STATUS_IDLE.equals(locked.getStatus())) {
            return err(203, "鸽子正在送信，稍后再试", "PIGEON_BUSY");
        }

        Date now = new Date();
        FeigeLetter letter = new FeigeLetter();
        letter.setLetterId(newLetterId());
        letter.setShareToken(newShareToken());
        letter.setSenderOpenid(openid);
        letter.setSenderProvince(province);
        letter.setSenderCity(city);
        letter.setSenderLat(lat);
        letter.setSenderLng(lng);
        letter.setContent(StringUtils.abbreviate(content, MAX_CONTENT_LEN));
        letter.setImageUrl(imageUrl);
        letter.setPigeonId(locked.getId());
        letter.setPigeonName(locked.getName());
        letter.setSpeedKmh(locked.getSpeedKmh());
        letter.setDepartureTime(now);
        letter.setClaimExpireTime(new Date(now.getTime() + CLAIM_EXPIRE_HOURS * 3600_000L));
        letter.setStatus(FeigeLetter.STATUS_FLYING_UNCLAIMED);
        letter.setSettled(0);
        letter.setSubscribed(0);
        letter.setNotified(0);
        letter.setRead(0);
        letter.setCreateAt(now);
        letter.setUpdateAt(now);
        feigeLetterMapper.insertSelective(letter);
        feigePigeonService.markSending(locked.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("letterId", letter.getLetterId());
        data.put("shareToken", letter.getShareToken());
        data.put("status", FeigeLetter.STATUS_FLYING_UNCLAIMED);
        data.put("departureTime", formatDate(now));
        data.put("claimExpireTime", formatDate(letter.getClaimExpireTime()));
        data.put("serverTime", formatDate(now));
        Map<String, Object> pigeonInfo = new HashMap<>();
        pigeonInfo.put("name", locked.getName());
        pigeonInfo.put("level", locked.getLevel());
        pigeonInfo.put("speedKmh", locked.getSpeedKmh());
        data.put("pigeon", pigeonInfo);
        data.put("senderCity", joinCity(province, city));
        return ok(data);
    }

    // ============================ 分享预览（不发生任何状态变更） ============================

    public Map<String, Object> sharePreview(String shareToken, String openid) {
        FeigeLetter letter = feigeLetterMapper.selectByShareToken(shareToken);
        if (letter == null) {
            return err(404, "信件不存在", "LETTER_NOT_FOUND");
        }
        String claimStatus;
        if (FeigeLetter.STATUS_RECALLED.equals(letter.getStatus())) {
            claimStatus = "RECALLED";
        } else if (FeigeLetter.STATUS_UNCLAIMED_EXPIRED.equals(letter.getStatus())) {
            claimStatus = "EXPIRED";
        } else if (FeigeLetter.STATUS_FLYING_UNCLAIMED.equals(letter.getStatus())) {
            claimStatus = "AVAILABLE";
        } else {
            claimStatus = openid != null && openid.equals(letter.getRecipientOpenid())
                    ? "CLAIMED_BY_ME" : "CLAIMED_BY_OTHER";
        }
        Map<String, Object> data = new HashMap<>();
        data.put("claimStatus", claimStatus);
        data.put("letterId", letter.getLetterId());
        data.put("senderProvince", letter.getSenderProvince());
        data.put("senderCity", letter.getSenderCity());
        data.put("pigeonName", letter.getPigeonName());
        data.put("serverTime", formatDate(new Date()));
        return ok(data);
    }

    // ============================ 收件人原子认领（bind） ============================

    @Transactional
    public Map<String, Object> claim(String shareToken, String openid, String province, String city,
                                     BigDecimal lat, BigDecimal lng) {
        FeigeLetter letter = feigeLetterMapper.selectByShareToken(shareToken);
        if (letter == null) {
            return err(404, "信件不存在", "LETTER_NOT_FOUND");
        }
        if (openid.equals(letter.getSenderOpenid())) {
            return err(403, "不能认领自己发送的信", "SENDER_CANNOT_CLAIM");
        }
        String status = letter.getStatus();
        if (!FeigeLetter.STATUS_FLYING_UNCLAIMED.equals(status)) {
            if (FeigeLetter.STATUS_RECALLED.equals(status)) {
                return err(404, "信件已被召回", "LETTER_RECALLED");
            }
            if (FeigeLetter.STATUS_UNCLAIMED_EXPIRED.equals(status)) {
                return err(404, "认领期已过", "CLAIM_EXPIRED");
            }
            if (openid.equals(letter.getRecipientOpenid())) {
                return flight(letter.getLetterId(), openid);
            }
            return err(409, "已被别人认领", "ALREADY_CLAIMED");
        }
        Date now = new Date();
        if (now.after(letter.getClaimExpireTime())) {
            return err(404, "认领期已过", "CLAIM_EXPIRED");
        }
        if (letter.getDepartureTime() == null || letter.getSenderLat() == null
                || letter.getSenderLng() == null || lat == null || lng == null) {
            return err(400, "缺少定位信息", "INVALID_ARGUMENT");
        }

        // 使用【原始起飞时间】计算，不修改 departure_time
        BigDecimal distance = haversineKm(letter.getSenderLat(), letter.getSenderLng(), lat, lng);
        BigDecimal speed = letter.getSpeedKmh() == null ? new BigDecimal("177.00") : letter.getSpeedKmh();
        BigDecimal flightHours = distance.divide(speed, 2, RoundingMode.HALF_UP);
        Date departure = letter.getDepartureTime();
        Date arrival = new Date(departure.getTime() + hoursToMs(flightHours));

        int rows = feigeLetterMapper.claimLetter(shareToken, openid, province, city, lat, lng,
                distance, flightHours, now, arrival, now, now);
        if (rows <= 0) {
            FeigeLetter latest = feigeLetterMapper.selectByLetterId(letter.getLetterId());
            if (latest == null) {
                return err(404, "信件不存在", "LETTER_NOT_FOUND");
            }
            if (FeigeLetter.STATUS_RECALLED.equals(latest.getStatus())) {
                return err(404, "信件已被召回", "LETTER_RECALLED");
            }
            if (FeigeLetter.STATUS_UNCLAIMED_EXPIRED.equals(latest.getStatus())) {
                return err(404, "认领期已过", "CLAIM_EXPIRED");
            }
            if (openid.equals(latest.getRecipientOpenid())) {
                return flight(latest.getLetterId(), openid);
            }
            return err(409, "已被别人认领", "ALREADY_CLAIMED");
        }

        // 认领成功：确定性生成飞行日志（含起飞/抵达），同信件重试不重复
        generateEvents(letter, departure, arrival);

        // 若认领时已到抵达时间，立即推进一次（幂等）
        feigeLifecycleService.advanceToArrived(letter.getLetterId());

        long totalMs = arrival.getTime() - departure.getTime();
        long usedMs = Math.max(0L, now.getTime() - departure.getTime());
        double progress = totalMs <= 0 ? 1.0 : Math.min(1.0, (double) usedMs / totalMs);
        String firstOpenCase = progress >= 1.0 ? "ARRIVED_WAITING"
                : (progress < 0.10 ? "JUST_DEPARTED" : "ALREADY_FLYING");
        long waitingDurationSeconds = Math.max(0L, usedMs / 1000L);

        Map<String, Object> data = new HashMap<>();
        data.put("letterId", letter.getLetterId());
        data.put("status", now.before(arrival) ? FeigeLetter.STATUS_IN_FLIGHT : FeigeLetter.STATUS_ARRIVED);
        data.put("distanceKm", distance);
        data.put("flightHours", flightHours);
        data.put("departureTime", formatDate(departure));
        data.put("arrivalTime", formatDate(arrival));
        data.put("serverTime", formatDate(now));
        data.put("progress", round(progress));
        data.put("firstOpenCase", firstOpenCase);
        data.put("waitingDurationSeconds", waitingDurationSeconds);
        return ok(data);
    }

    // ============================ 发件人免费召回 ============================

    @Transactional
    public Map<String, Object> recall(String letterId, String openid) {
        FeigeLetter letter = feigeLetterMapper.selectByLetterId(letterId);
        if (letter == null) {
            return err(404, "信件不存在", "LETTER_NOT_FOUND");
        }
        if (!openid.equals(letter.getSenderOpenid())) {
            return err(403, "无权限", "ACCESS_DENIED");
        }
        if (FeigeLetter.STATUS_RECALLED.equals(letter.getStatus())) {
            return ok(field("recalled", true));
        }
        if (FeigeLetter.STATUS_UNCLAIMED_EXPIRED.equals(letter.getStatus())) {
            return err(404, "已过期，无需召回", "CLAIM_EXPIRED");
        }
        if (!FeigeLetter.STATUS_FLYING_UNCLAIMED.equals(letter.getStatus())
                || letter.getRecipientOpenid() != null) {
            return err(409, "当前状态不允许召回", "RECALL_NOT_ALLOWED");
        }
        Date now = new Date();
        if (now.before(new Date(letter.getDepartureTime().getTime() + RECALL_GRACE_MS))) {
            return err(409, "尚未达到召回时间", "RECALL_TOO_EARLY");
        }
        if (now.after(letter.getClaimExpireTime())) {
            return err(404, "认领期已过", "CLAIM_EXPIRED");
        }
        int rows = feigeLetterMapper.markRecalled(letterId, now, now, now);
        if (rows <= 0) {
            FeigeLetter latest = feigeLetterMapper.selectByLetterId(letterId);
            if (latest != null && FeigeLetter.STATUS_RECALLED.equals(latest.getStatus())) {
                return ok(field("recalled", true));
            }
            return err(409, "认领与召回冲突", "ALREADY_CLAIMED");
        }
        feigePigeonService.release(letter.getPigeonId());
        Map<String, Object> data = field("recalled", true);
        data.put("recalledAt", formatDate(now));
        return ok(data);
    }

    // ============================ 飞行页 ============================

    public Map<String, Object> flight(String letterId, String openid) {
        FeigeLetter letter = feigeLetterMapper.selectByLetterId(letterId);
        if (letter == null) {
            return err(404, "信件不存在", "LETTER_NOT_FOUND");
        }
        if (!canView(letter, openid)) {
            return err(403, "无权限", "ACCESS_DENIED");
        }
        feigeLifecycleService.advanceToArrived(letterId);
        letter = feigeLetterMapper.selectByLetterId(letterId);

        Map<String, Object> data = new HashMap<>();
        data.put("letterId", letterId);
        data.put("status", letter.getStatus());
        data.put("departureTime", formatDate(letter.getDepartureTime()));
        data.put("serverTime", formatDate(new Date()));
        data.put("subscribed", letter.getSubscribed() != null && letter.getSubscribed() == 1);

        boolean canRecall = isRecallable(letter);
        data.put("canRecall", canRecall);

        if (FeigeLetter.STATUS_FLYING_UNCLAIMED.equals(letter.getStatus())) {
            data.put("claimExpireTime", formatDate(letter.getClaimExpireTime()));
            data.put("progress", null);
            data.put("flownKm", null);
            data.put("remainKm", null);
            data.put("totalKm", null);
            data.put("arrivalTime", null);
            data.put("usedDuration", null);
            data.put("remainDuration", null);
            data.put("flightLog", new ArrayList<>());
            data.put("pigeon", pigeonBlock(letter.getPigeonName(), "等待认领"));
            return ok(data);
        }
        if (FeigeLetter.STATUS_RECALLED.equals(letter.getStatus())
                || FeigeLetter.STATUS_UNCLAIMED_EXPIRED.equals(letter.getStatus())) {
            data.put("progress", null);
            data.put("flownKm", null);
            data.put("remainKm", null);
            data.put("totalKm", null);
            data.put("arrivalTime", null);
            data.put("flightLog", new ArrayList<>());
            data.put("pigeon", pigeonBlock(letter.getPigeonName(), "已归巢"));
            return ok(data);
        }

        Date now = new Date();
        long totalMs = letter.getArrivalTime().getTime() - letter.getDepartureTime().getTime();
        long usedMs = Math.max(0L, now.getTime() - letter.getDepartureTime().getTime());
        double progress = totalMs <= 0 ? 1.0 : Math.min(1.0, (double) usedMs / totalMs);
        BigDecimal distance = letter.getDistanceKm() == null ? BigDecimal.ZERO : letter.getDistanceKm();
        BigDecimal flown = distance.multiply(BigDecimal.valueOf(progress)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remain = distance.subtract(flown).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        data.put("arrivalTime", formatDate(letter.getArrivalTime()));
        data.put("distanceKm", distance);
        data.put("flightHours", letter.getFlightHours());
        data.put("progress", round(progress));
        data.put("flownKm", flown);
        data.put("remainKm", remain);
        data.put("totalKm", distance);
        data.put("usedDuration", formatDurationMs(usedMs));
        data.put("remainDuration", formatDurationMs(Math.max(0L, totalMs - usedMs)));
        data.put("pigeon", pigeonBlock(letter.getPigeonName(), pigeonFlightState(letter.getStatus())));
        data.put("flightLog", flightLog(letterId, now));
        return ok(data);
    }

    // ============================ 收信/拆信（不再结算成长） ============================

    public Map<String, Object> detail(String letterId, String openid) {
        FeigeLetter letter = feigeLetterMapper.selectByLetterId(letterId);
        if (letter == null) {
            return err(404, "信件不存在", "LETTER_NOT_FOUND");
        }
        if (!canView(letter, openid)) {
            return err(403, "无权限", "ACCESS_DENIED");
        }
        feigeLifecycleService.advanceToArrived(letterId);
        letter = feigeLetterMapper.selectByLetterId(letterId);
        if (!FeigeLetter.STATUS_ARRIVED.equals(letter.getStatus())
                && !FeigeLetter.STATUS_DELIVERED.equals(letter.getStatus())) {
            return err(404, "信件还未送达", "NOT_ARRIVED");
        }
        boolean isRecipient = openid.equals(letter.getRecipientOpenid());
        // 仅收件人拆信标记已读 & ARRIVED->DELIVERED；发件人查看不触发已读
        if (isRecipient && FeigeLetter.STATUS_ARRIVED.equals(letter.getStatus())) {
            feigeLetterMapper.markDelivered(letterId, new Date());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("letterId", letterId);
        data.put("content", letter.getContent());
        data.put("imageUrl", letter.getImageUrl());
        data.put("senderProvince", letter.getSenderProvince());
        data.put("senderCity", letter.getSenderCity());
        data.put("arriveTime", formatDate(letter.getArrivalTime()));
        data.put("flightHours", letter.getFlightHours());
        data.put("settleExpDelta", letter.getSettleExpDelta());
        data.put("settleLevelBefore", letter.getSettleLevelBefore());
        data.put("settleLevelAfter", letter.getSettleLevelAfter());
        data.put("settleLevelUp", letter.getSettleLevelUp());
        data.put("canReply", isRecipient);
        return ok(data);
    }

    // ============================ 订阅到达通知 ============================

    public Map<String, Object> subscribe(String letterId, String openid) {
        FeigeLetter letter = feigeLetterMapper.selectByLetterId(letterId);
        if (letter == null) {
            return err(404, "信件不存在", "LETTER_NOT_FOUND");
        }
        if (!canView(letter, openid)) {
            return err(403, "无权限", "ACCESS_DENIED");
        }
        if (FeigeLetter.STATUS_ARRIVED.equals(letter.getStatus())
                || FeigeLetter.STATUS_DELIVERED.equals(letter.getStatus())) {
            return ok(field("subscribed", false));
        }
        feigeLetterMapper.updateSubscribed(letterId, 1, new Date());
        return ok(field("subscribed", true));
    }

    // ============================ 回信 ============================

    @Transactional
    public Map<String, Object> reply(String openid, String content, String imageUrl,
                                     String province, String city, BigDecimal lat, BigDecimal lng, String letterId) {
        FeigeLetter original = feigeLetterMapper.selectByLetterId(letterId);
        if (original == null) {
            return err(404, "原信件不存在", "LETTER_NOT_FOUND");
        }
        if (!FeigeLetter.STATUS_DELIVERED.equals(original.getStatus())) {
            return err(404, "原信件还未送达", "NOT_ARRIVED");
        }
        if (!openid.equals(original.getRecipientOpenid())) {
            return err(403, "仅收件人可回信", "ACCESS_DENIED");
        }
        FeigePigeon pigeon = feigePigeonService.getOrInitByOpenid(openid);
        FeigePigeon locked = pigeon == null ? null : feigePigeonMapper.selectByPrimaryKeyForUpdate(pigeon.getId());
        if (locked == null || !FeigePigeon.STATUS_IDLE.equals(locked.getStatus())) {
            return err(203, "鸽子正在送信，稍后再试", "PIGEON_BUSY");
        }
        Date now = new Date();
        FeigeLetter reply = new FeigeLetter();
        reply.setLetterId(newLetterId());
        reply.setShareToken(newShareToken());
        reply.setSenderOpenid(openid);
        reply.setSenderProvince(province);
        reply.setSenderCity(city);
        reply.setSenderLat(lat);
        reply.setSenderLng(lng);
        reply.setContent(StringUtils.abbreviate(content, MAX_CONTENT_LEN));
        reply.setImageUrl(imageUrl);
        reply.setPigeonId(locked.getId());
        reply.setPigeonName(locked.getName());
        reply.setSpeedKmh(locked.getSpeedKmh());
        reply.setRecipientOpenid(original.getSenderOpenid());
        reply.setDepartureTime(now);
        reply.setClaimExpireTime(new Date(now.getTime() + CLAIM_EXPIRE_HOURS * 3600_000L));
        reply.setStatus(FeigeLetter.STATUS_FLYING_UNCLAIMED);
        reply.setSettled(0);
        reply.setSubscribed(0);
        reply.setNotified(0);
        reply.setRead(0);
        reply.setCreateAt(now);
        reply.setUpdateAt(now);
        feigeLetterMapper.insertSelective(reply);
        feigePigeonService.markSending(locked.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("newLetterId", reply.getLetterId());
        data.put("shareToken", reply.getShareToken());
        return ok(data);
    }

    /** 认领成功后确定性生成飞行日志（同信件重试不重复；含起飞与抵达记录）。 */
    private void generateEvents(FeigeLetter letter, Date departure, Date arrival) {
        String letterId = letter.getLetterId();
        if (!feigeLetterEventMapper.selectByLetterId(letterId).isEmpty()) {
            return;
        }
        long flightMs = Math.max(0L, arrival.getTime() - departure.getTime());
        List<FeigeLetterEvent> events = new ArrayList<>();
        events.add(buildEvent(letterId, 1, FeigeLetterEvent.TYPE_DEPART, "信鸽出发了",
                "它带着你的信，开始了这段旅程。", departure));

        Random rnd = new Random(letterId.hashCode());
        double[] fractions = {0.25, 0.5, 0.72, 0.9};
        String[][] pool = {
                {FeigeLetterEvent.TYPE_CITY_OVER, "飞过一片云海", "脚下是连绵的云。"},
                {FeigeLetterEvent.TYPE_COUNTERWIND, "遇到了一阵逆风", "它多花了些力气。"},
                {FeigeLetterEvent.TYPE_RAIN, "遇上了一阵小雨", "它在雨里继续飞。"},
                {FeigeLetterEvent.TYPE_REST, "偷懒休息了一会儿", "在路过的屋檐上歇了歇脚。"},
                {FeigeLetterEvent.TYPE_CAT_SCARE, "被一只猫吓了一跳", "它赶紧飞高了一些。"},
                {FeigeLetterEvent.TYPE_FOOD, "偷吃了点干粮", "补充体力后继续赶路。"},
                {FeigeLetterEvent.TYPE_DRIFT, "原地转了个圈", "像是认路认错了方向。"}
        };
        int seq = 2;
        for (double fraction : fractions) {
            String[] item = pool[rnd.nextInt(pool.length)];
            long at = departure.getTime() + (long) (flightMs * fraction);
            events.add(buildEvent(letterId, seq++, item[0], item[1], item[2], new Date(at)));
        }
        events.add(buildEvent(letterId, seq, FeigeLetterEvent.TYPE_ARRIVE, "信鸽抵达了",
                "它把这封信交到了你手里。", arrival));
        for (FeigeLetterEvent event : events) {
            feigeLetterEventMapper.insertSelective(event);
        }
    }

    private FeigeLetterEvent buildEvent(String letterId, int seq, String type, String title,
                                        String description, Date atTime) {
        FeigeLetterEvent event = new FeigeLetterEvent();
        event.setLetterId(letterId);
        event.setSeq(seq);
        event.setType(type);
        event.setTitle(title);
        event.setDescription(description);
        event.setAtTime(atTime);
        event.setCreateAt(new Date());
        return event;
    }

    // ============================ 内部工具 ============================

    private boolean canView(FeigeLetter letter, String openid) {
        if (openid == null) {
            return false;
        }
        return openid.equals(letter.getSenderOpenid())
                || (letter.getRecipientOpenid() != null && openid.equals(letter.getRecipientOpenid()));
    }

    private boolean isRecallable(FeigeLetter letter) {
        if (!FeigeLetter.STATUS_FLYING_UNCLAIMED.equals(letter.getStatus())
                || letter.getRecipientOpenid() != null || letter.getDepartureTime() == null) {
            return false;
        }
        Date now = new Date();
        return !now.before(new Date(letter.getDepartureTime().getTime() + RECALL_GRACE_MS))
                && (letter.getClaimExpireTime() == null || now.before(letter.getClaimExpireTime()));
    }

    private List<Map<String, Object>> flightLog(String letterId, Date now) {
        List<Map<String, Object>> log = new ArrayList<>();
        for (FeigeLetterEvent event : feigeLetterEventMapper.selectByLetterId(letterId)) {
            if (event.getAtTime() != null && event.getAtTime().after(now)) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("type", event.getType());
            item.put("title", event.getTitle());
            item.put("description", event.getDescription());
            item.put("atTime", formatDate(event.getAtTime()));
            log.add(item);
        }
        return log;
    }

    private Map<String, Object> pigeonBlock(String name, String status) {
        Map<String, Object> block = new HashMap<>();
        block.put("name", name);
        block.put("status", status);
        return block;
    }

    private String newLetterId() {
        return "FG" + UUID.randomUUID().toString().replace("-", "");
    }

    private String newShareToken() {
        return "ST" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Haversine 直线距离(km)。 */
    private BigDecimal haversineKm(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue()))
                * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM.multiply(BigDecimal.valueOf(c)).setScale(2, RoundingMode.HALF_UP);
    }

    private long hoursToMs(BigDecimal hours) {
        return hours.multiply(BigDecimal.valueOf(3600_000L)).longValue();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String joinCity(String province, String city) {
        return StringUtils.defaultString(province) + " · " + StringUtils.defaultString(city);
    }

    private String pigeonFlightState(String letterStatus) {
        if (FeigeLetter.STATUS_ARRIVED.equals(letterStatus) || FeigeLetter.STATUS_DELIVERED.equals(letterStatus)) {
            return "已抵达";
        }
        return "精神不错";
    }

    private String formatDurationMs(long ms) {
        if (ms <= 0) {
            return "0分钟";
        }
        long totalMinutes = ms / 60000L;
        return (totalMinutes / 60) + "小时" + (totalMinutes % 60) + "分";
    }

    private String formatDate(Date date) {
        return date == null ? "" : new SimpleDateFormat(DATE_PATTERN).format(date);
    }

    private Map<String, Object> field(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
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
}
