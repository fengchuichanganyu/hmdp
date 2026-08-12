package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;

import cn.hutool.json.JSONUtil;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1.从Redis List查询商户分类
        List<String> typeJsonList = stringRedisTemplate.opsForList()
                .range(CACHE_SHOP_TYPE_KEY, 0, -1);

        // 2.缓存命中，逐个反序列化后返回
        if (typeJsonList != null && !typeJsonList.isEmpty()) {
            List<ShopType> typeList = typeJsonList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(typeList);
        }

        // 3.缓存未命中，按sort字段查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList == null || typeList.isEmpty()) {
            return Result.ok(typeList);
        }

        // 4.每个分类转换为JSON，按原顺序写入Redis List
        List<String> jsonList = typeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, jsonList);

        return Result.ok(typeList);
    }
}
