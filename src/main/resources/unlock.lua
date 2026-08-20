-- KEYS[1]：需要释放的锁Key
-- ARGV[1]：当前线程持有锁时写入的唯一标识
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0
