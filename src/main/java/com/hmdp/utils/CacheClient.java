package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(
                key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从Redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.缓存命中，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 3.命中空值，直接返回
        if (json != null) {
            return null;
        }
        // 4.缓存未命中，查询数据库
        R r = dbFallback.apply(id);
        // 5.数据库中不存在，缓存空值
        if (r == null) {
            stringRedisTemplate.opsForValue().set(
                    key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.数据库中存在，写入Redis
        this.set(key, r, time, unit);
        return r;
    }

    private final ExecutorService cacheRebuildExecutor =
            Executors.newFixedThreadPool(10);

    @PreDestroy
    public void shutdownCacheRebuildExecutor() {
        cacheRebuildExecutor.shutdown();
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从Redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.未命中，直接返回
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 3.命中，解析数据和逻辑过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4.未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 5.已过期，异步尝试获取缓存重建锁；当前请求仍然返回旧数据
        String lockKey = LOCK_SHOP_KEY + id;
        cacheRebuildExecutor.submit(() -> {
            RLock lock = redissonClient.getLock(lockKey);
            boolean isLock = false;
            try {
                isLock = lock.tryLock();
                if (!isLock) {
                    return;
                }
                // 获取锁后再次检查，避免排队任务在前一个任务完成后重复重建。
                String latestJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(latestJson)) {
                    RedisData latestData = JSONUtil.toBean(latestJson, RedisData.class);
                    if (latestData.getExpireTime().isAfter(LocalDateTime.now())) {
                        return;
                    }
                }
                R newValue = dbFallback.apply(id);
                this.setWithLogicalExpire(key, newValue, time, unit);
            } catch (Exception e) {
                log.error("重建缓存失败，key={}", key, e);
            } finally {
                if (isLock && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
        // 7.返回旧数据
        return r;
    }
}
