package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final String STREAM_ORDERS_KEY = "stream.orders";
    private static final String STREAM_GROUP = "g1";
    private static final String STREAM_CONSUMER = "c1";

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private final ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stream-order-consumer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running = true;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    @Lazy
    private IVoucherOrderService voucherOrderServiceProxy;

    @EventListener(ApplicationReadyEvent.class)
    public void initStreamConsumer() {
        createStreamGroup();
        seckillOrderExecutor.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    public void shutdownStreamConsumer() {
        running = false;
        seckillOrderExecutor.shutdownNow();
    }

    private void createStreamGroup() {
        try {
            stringRedisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            STREAM_ORDERS_KEY.getBytes(StandardCharsets.UTF_8),
                            STREAM_GROUP,
                            ReadOffset.from("0"),
                            true
                    )
            );
            log.info("创建Redis Stream消费者组成功：stream={}, group={}", STREAM_ORDERS_KEY, STREAM_GROUP);
        } catch (RuntimeException e) {
            if (!containsBusyGroup(e)) {
                throw e;
            }
            log.debug("Redis Stream消费者组已存在：stream={}, group={}", STREAM_ORDERS_KEY, STREAM_GROUP);
        }
    }

    private boolean containsBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            // 应用重启后先恢复上次未确认的消息，再读取新消息
            handlePendingList();
            while (running) {
                try {
                    // 1.从消费者组读取尚未投递的新消息，阻塞时间最长2秒
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(STREAM_GROUP, STREAM_CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        continue;
                    }

                    // 2.把Stream消息转换成订单对象并写入数据库
                    MapRecord<String, Object, Object> record = records.get(0);
                    VoucherOrder voucherOrder = toVoucherOrder(record.getValue());
                    handleVoucherOrder(voucherOrder);

                    // 3.业务成功后确认消息，消息才会从Pending List移除
                    stringRedisTemplate.opsForStream()
                            .acknowledge(STREAM_ORDERS_KEY, STREAM_GROUP, record.getId());
                } catch (Exception e) {
                    if (!running) {
                        return;
                    }
                    log.error("处理Stream订单消息异常，准备处理Pending List", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (running) {
            try {
                // ID为0时，读取当前消费者已经接收但尚未ACK的消息
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(STREAM_GROUP, STREAM_CONSUMER),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.from("0"))
                );
                if (records == null || records.isEmpty()) {
                    return;
                }

                MapRecord<String, Object, Object> record = records.get(0);
                VoucherOrder voucherOrder = toVoucherOrder(record.getValue());
                handleVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream()
                        .acknowledge(STREAM_ORDERS_KEY, STREAM_GROUP, record.getId());
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.error("处理Pending List订单消息异常，稍后重试", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private VoucherOrder toVoucherOrder(Map<Object, Object> message) {
        return BeanUtil.fillBeanWithMap(message, new VoucherOrder(), true);
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            throw new IllegalStateException("获取用户订单锁失败，稍后重试");
        }

        try {
            // 通过Spring代理调用，确保异步线程中的数据库事务生效
            voucherOrderServiceProxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.获取当前用户id
        Long userId = UserHolder.getUser().getId();

        // 2.生成订单id，并由Lua一起写入Stream消息
        long orderId = redisIdWorker.nextId("order");

        // 3.执行Lua脚本：判断库存、一人一单、扣库存并发送Stream消息
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        // 4.判断Lua脚本执行结果：0成功，1库存不足，2重复下单
        if (result == null) {
            return Result.fail("秒杀失败，请稍后重试！");
        }
        int resultCode = result.intValue();
        if (resultCode != 0) {
            return Result.fail(resultCode == 1 ? "库存不足！" : "不能重复下单！");
        }

        // 5.返回订单id，数据库订单由Stream消费者异步创建
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1.一人一单：查询当前用户是否已经购买过该优惠券
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            log.error("用户已经购买过该优惠券，userId={}, voucherId={}", userId, voucherId);
            return;
        }

        // 2.扣减库存，stock > 0 防止超卖
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("数据库秒杀券库存不足，voucherId={}", voucherId);
            return;
        }

        // 3.创建订单
        if (!save(voucherOrder)) {
            throw new IllegalStateException("保存优惠券订单失败");
        }
    }
}
