package com.hmdp.service.impl;

import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IUserService;
import org.springframework.data.redis.core.StringRedisTemplate;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1. 获取登录用户：user_id 是关注者，follow_user_id 是被关注者。
        // 当前用户由登录拦截器写入 UserHolder，不能从前端参数获取。
        Long userId = UserHolder.getUser().getId();
        // key 属于关注者；Set 的 member 是他关注的用户ID，不是他的粉丝ID。
        String key = FOLLOW_KEY + userId;
        // 2. true 关注：向 tb_follow 新增一条有方向的关系。
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 数据库新增成功后，将被关注用户ID写入 Redis Set。
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3. false 取关：两个条件以 AND 连接，只删除“我关注他”的记录。
            // DELETE FROM tb_follow WHERE user_id = ? AND follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                // 数据库删除成功后，同步移除 Set 中的关注记录。
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1. 获取当前登录用户。
        Long userId = UserHolder.getUser().getId();
        // 2. 查询这两个用户之间指定方向的关注关系数量。
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId).count();
        // 3. data=true 表示已关注，data=false 表示未关注。
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        // 1. 当前登录用户的关注集合，以及对方的关注集合。
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY + userId;
        String key2 = FOLLOW_KEY + id;
        // 2. SINTER 求交集：两个人都关注的用户ID。Set 不保证结果顺序。
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 3. Redis 的字符串ID转为 Long，供数据库批量查询。
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4. 查询用户并转为 UserDTO，只返回ID、昵称、头像。
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

}
