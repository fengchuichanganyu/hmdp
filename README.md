# 黑马点评（hmdp）

基于 Spring Boot 与 Redis 的本地生活服务后端项目。项目围绕登录状态共享、商户缓存、社交 Feed、附近商户和优惠券秒杀等场景，练习 Redis 多种数据结构及高并发场景下的数据一致性处理。

> 本仓库定位为学习和求职展示项目，不是大型互联网生产系统。README 只描述当前代码中真实存在的实现，不包含虚构压测结果。

## 技术栈

- Java 13（`pom.xml` 当前编译目标为 Java 8）
- Spring Boot 2.3.12.RELEASE
- MyBatis-Plus 3.4.3
- MySQL 8.0、Connector/J 5.1.47
- Redis 6.2、Spring Data Redis、Lettuce
- Redisson 3.13.6
- Hutool、Lombok、Maven

## 核心功能

- 手机号验证码登录：验证码当前通过日志模拟发送，登录后生成 UUID Token。
- 登录状态共享：用户摘要以 Redis Hash 保存，拦截器负责装载 `UserHolder`、刷新有效期和清理 ThreadLocal。
- 商户与分类：商户查询缓存、空值缓存防穿透、更新数据库后删除缓存；分类使用 Redis List 缓存。
- 探店社交：发布笔记、点赞/取消点赞、点赞用户排行、关注/取关、共同关注、Feed 推送和滚动分页。
- 附近商户：使用 Redis GEO 按商户类型和距离查询，回查 MySQL 补充完整信息。
- 用户签到：使用 Redis Bitmap 记录用户当月每日签到；当前尚未实现连续签到天数统计。
- 优惠券秒杀：Lua 原子校验库存和一人一单，Redis Stream 异步下单，Pending List 异常恢复，Redisson 用户级锁和数据库事务保证下单流程。
- Redis 全局 ID：时间戳高位与 Redis 日增序列低位组合生成订单 ID。

## 项目架构

```text
HTTP 请求
   │
   ├── RefreshTokenInterceptor：读取 Token、刷新 TTL、写入 UserHolder
   ├── LoginInterceptor：保护需要登录的接口
   ▼
Controller → Service → MyBatis-Plus → MySQL
                 │
                 ├── StringRedisTemplate
                 │     ├── String / Hash / List / Set / ZSet
                 │     ├── GEO / Bitmap / Stream
                 │     └── Lua 脚本
                 └── RedissonClient → 分布式锁
```

当前本地环境使用单机 Redis，不包含 Redis Sentinel 或 Redis Cluster。Spring Data Redis 与 Redisson 都连接同一 Redis 实例。

## Redis 典型应用场景

| 场景 | Redis能力 | 当前用途 |
|---|---|---|
| 验证码 | String + TTL | 两分钟验证码有效期 |
| 登录态 | Hash + TTL | Token 对应用户摘要及滑动续期 |
| 商户缓存 | String | JSON 缓存、空值防穿透 |
| 商户分类 | List | 保持分类顺序 |
| 关注关系 | Set | 关注集合与共同关注交集 |
| 点赞排行 | ZSet | 用户 ID 为 member，点赞时间为 score |
| Feed 收件箱 | ZSet | 笔记 ID 为 member，发布时间为 score |
| 附近商户 | GEO | 五公里范围及距离排序 |
| 用户签到 | Bitmap | 每月一个 key，每天一个 bit |
| 异步订单 | Stream | Consumer Group、ACK、Pending List |
| 秒杀校验 | Lua | 库存判断、重复下单判断、扣库存和投递消息原子执行 |
| 全局 ID | INCR | 每项业务按日期生成自增序列 |

## 登录流程

1. 校验手机号格式，生成六位验证码并写入 Redis；当前仅通过日志模拟短信发送。
2. 登录时校验 Redis 中的验证码，不存在的手机号自动创建用户。
3. 生成 UUID Token，将脱敏后的 `UserDTO` 写入 Redis Hash。
4. 客户端通过 `authorization` Header 携带 Token。
5. `RefreshTokenInterceptor` 查询用户、刷新 TTL 并写入 `UserHolder`。
6. `LoginInterceptor` 拦截需要登录但未获取到用户的请求。
7. 请求结束后从 ThreadLocal 删除用户，避免线程复用导致数据泄漏。
8. 登出时删除 Redis 中的 Token Hash。

## 缓存策略

商户详情当前默认使用缓存空值解决缓存穿透：

```text
查询 Redis → 命中直接返回
           → 命中空值返回不存在
           → 未命中查询 MySQL → 回写数据或短期空值
```

`CacheClient` 同时实现了逻辑过期方案：过期后当前请求返回旧值，由线程池使用 Redisson 锁异步重建缓存。该能力目前保留在代码中，商户详情的逻辑过期调用尚未启用，使用前需要先预热热点数据。

商户更新在事务方法中采用“先更新数据库，再删除缓存”。

## 秒杀业务流程

```text
请求秒杀
   │
   ├── Redis 全局 ID 生成订单号
   ▼
Lua 脚本原子执行
   ├── 判断 Redis 库存
   ├── Set 判断一人一单
   ├── 扣减 Redis 库存
   └── XADD 写入 stream.orders
             │
             ▼
Stream Consumer Group
   ├── Redisson 用户级锁
   ├── 数据库再次检查一人一单
   ├── 条件更新数据库库存
   ├── 事务内创建订单
   └── 成功后 ACK；异常消息从 Pending List 恢复
```

初始化 SQL 为订单表增加了 `(user_id, voucher_id)` 唯一索引，作为 Redis 和应用层校验之外的数据库最终兜底。

## 运行环境

推荐与当前开发环境保持一致：

- JDK 13
- Maven 3.9.x
- MySQL 8.0，地址 `127.0.0.1:3306`
- Redis 6.2，地址 `127.0.0.1:16379`

### 1. 初始化数据库

首次初始化使用：

```text
src/main/resources/db/hmdp.sql
```

如果数据库已经存在，不要重新导入包含 `DROP TABLE` 的完整脚本。先检查重复订单：

```sql
SELECT user_id, voucher_id, COUNT(*) AS count
FROM tb_voucher_order
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;
```

确认没有重复数据后，单独执行：

```sql
ALTER TABLE tb_voucher_order
ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
```

### 2. 启动 Redis

当前开发环境使用 Docker Redis：

```powershell
$env:REDIS_PASSWORD = "请替换为你自己的Redis密码"

docker run -d --name hm-redis `
  -p 16379:6379 `
  -v hm-redis-data:/data `
  redis:6.2 `
  redis-server --appendonly yes --requirepass $env:REDIS_PASSWORD
```

验证：

```powershell
docker exec hm-redis redis-cli --no-auth-warning -a $env:REDIS_PASSWORD ping
```

### 3. 配置环境变量

`application.yaml` 不保存数据库和 Redis 明文密码。PowerShell 当前窗口可以这样设置：

```powershell
$env:MYSQL_USERNAME = "root"
$env:MYSQL_PASSWORD = "你的MySQL密码"
$env:REDIS_PASSWORD = "你的Redis密码"
```

示例配置见：

```text
src/main/resources/application-example.yaml
```

Spring Boot 2.3 默认不会自动读取仓库根目录的 `.env` 文件；如果自行创建 `.env`，需要由 IDE、启动脚本或操作系统负责加载，且不要提交到 Git。

### 4. 编译与启动

```powershell
mvn clean compile
mvn spring-boot:run
```

后端默认端口：

```text
http://127.0.0.1:8081
```

健康检查：

```text
http://127.0.0.1:8081/actuator/health
```

前端静态页面和 Nginx 不属于启动后端的必要条件；只有进行完整页面联调时才需要额外准备。

## 当前不足

- 验证码是日志模拟发送，没有接入真实短信平台，也没有发送频率限制。
- 没有 Redis Sentinel、Redis Cluster 和多实例应用部署验证。
- 没有形成可复现的正式压测报告，因此不声明 QPS 或性能提升比例。
- Bitmap 已实现每日签到，但尚未实现连续签到统计。
- 逻辑过期缓存策略已实现，但商户详情当前默认仍使用缓存穿透方案。
- Redis Stream 消费者名称固定为 `c1`，适用于当前单实例应用；扩展为多实例时应为每个实例分配唯一消费者名称。
- 完整初始化 SQL 包含 `DROP TABLE`，已有数据库应采用单独迁移 SQL，而不是重复导入完整脚本。
- 自动化测试覆盖范围有限，仍需补充关键业务的集成测试和并发测试。

## 求职展示建议

面试时重点说明设计目标、数据结构选择、原子性边界和异常恢复过程，避免只罗列 Redis 命令。所有性能结论都应来自可复现的测试数据；本项目当前未提供虚构的性能指标。
