package com.an.feige.common;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 行政区划坐标兜底服务：按 province/city 查内置坐标表（geo/city-coords.tsv）。
 *
 * <p>用途：写信/认领时前端未上报精确经纬度（或上报不完整）时，
 * 用城市中心坐标近似，保证信件仍可正常起飞。查找优先级：精确坐标 → 城市 → 省。</p>
 *
 * <p>数据文件格式（UTF-8，TAB 分隔，无表头）：{@code 省\t市\t纬度\t经度}，
 * 城市为空的行是省级兜底坐标。数据扩充只需追加行。</p>
 */
@Component
public class CityGeoService {

    private static final Logger logger = LoggerFactory.getLogger(CityGeoService.class);

    /** 城市级：key = "省\t市" */
    private final Map<String, BigDecimal[]> cityCoords = new HashMap<>();
    /** 省级兜底：key = 省 */
    private final Map<String, BigDecimal[]> provinceCoords = new HashMap<>();

    @PostConstruct
    public void init() {
        try (InputStream in = new ClassPathResource("geo/city-coords.tsv").getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int cityCount = 0, provinceCount = 0;
            while ((line = reader.readLine()) != null) {
                if (StringUtils.isBlank(line) || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 4) {
                    continue;
                }
                String province = parts[0].trim();
                String city = parts[1].trim();
                BigDecimal lat = parseCoord(parts[2]);
                BigDecimal lng = parseCoord(parts[3]);
                if (lat == null || lng == null || StringUtils.isBlank(province)) {
                    continue;
                }
                if (StringUtils.isBlank(city)) {
                    provinceCoords.put(province, new BigDecimal[]{lat, lng});
                    provinceCount++;
                } else {
                    cityCoords.put(province + "\t" + city, new BigDecimal[]{lat, lng});
                    cityCount++;
                }
            }
            logger.info("行政区划坐标表加载完成：省级={} 城市级={}", provinceCount, cityCount);
        } catch (Exception e) {
            logger.error("行政区划坐标表加载失败，城市坐标兜底不可用", e);
        }
    }

    /**
     * 解析坐标：显式经纬度 > 城市级 > 省级。
     *
     * @return 长度为 2 的数组 [lat, lng]；无法解析时返回 null（调用方自行处理）。
     */
    public BigDecimal[] resolve(String province, String city, BigDecimal lat, BigDecimal lng) {
        if (lat != null && lng != null) {
            return new BigDecimal[]{lat, lng};
        }
        if (StringUtils.isNotBlank(city) && StringUtils.isNotBlank(province)) {
            BigDecimal[] c = cityCoords.get(province + "\t" + city);
            if (c != null) {
                return c;
            }
        }
        if (StringUtils.isNotBlank(province)) {
            BigDecimal[] p = provinceCoords.get(province);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private BigDecimal parseCoord(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
