package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loadShopData() {
        // 1. 查询全部店铺信息。
        List<Shop> shops = shopService.list();

        // 2. 按店铺类型 typeId 分组；每种类型使用一个独立的 GEO key。
        Map<Long, List<Shop>> shopsByType = shops.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));

        // 3. 将每种类型的店铺坐标批量写入 Redis GEO。
        for (Map.Entry<Long, List<Shop>> entry : shopsByType.entrySet()) {
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            List<Shop> shopsOfType = entry.getValue();

            List<RedisGeoCommands.GeoLocation<String>> locations =
                    new ArrayList<>(shopsOfType.size());
            for (Shop shop : shopsOfType) {
                // GEO member 是店铺ID，Point(x, y) 分别是经度和纬度。
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }

            // 等价于批量执行：GEOADD shop:geo:typeId 经度 纬度 店铺ID。
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

}
