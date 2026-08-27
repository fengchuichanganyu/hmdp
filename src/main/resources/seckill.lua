-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据Key
-- 2.1.秒杀券库存Key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.秒杀券订单Key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

-- 3.2.判断用户是否已经下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 3.3.扣减库存
redis.call('incrby', stockKey, -1)
-- 3.4.将用户id保存到当前优惠券的已下单用户集合
redis.call('sadd', orderKey, userId)
-- 3.5.发送订单消息到Stream消息队列
redis.call(
        'xadd', 'stream.orders', '*',
        'id', orderId,
        'userId', userId,
        'voucherId', voucherId
)

return 0
