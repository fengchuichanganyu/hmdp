package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogServiceImplTest {
    private final BlogServiceImpl service = spy(new BlogServiceImpl());
    private final IUserService users = mock(IUserService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ZSetOperations<String, String> sets = mock(ZSetOperations.class);

    @BeforeEach
    void setup() {
        UserHolder.removeUser();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "userService", users);
        when(redis.opsForZSet()).thenReturn(sets);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    private void login() {
        UserDTO user = new UserDTO();
        user.setId(2L);
        UserHolder.saveUser(user);
    }

    @Test
    void likeServiceRequiresUserProvidedByLoginInterceptor() {
        assertThrows(NullPointerException.class, () -> service.likeBlog(1L));
        verifyNoInteractions(redis);
    }

    @Test
    void likeIncrementsAndRecordsUser() {
        login();
        doReturn(new Blog().setId(1L)).when(service).getById(1L);
        UpdateChainWrapper<Blog> update = mock(UpdateChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(service).update();
        when(update.update()).thenReturn(true);
        when(sets.score("blog:liked:1", "2")).thenReturn(null);
        assertTrue(service.likeBlog(1L).getSuccess());
        verify(update).setSql("liked = liked + 1");
        verify(update).eq("id", 1L);
        verify(sets).add(eq("blog:liked:1"), eq("2"), anyDouble());
    }

    @Test
    void secondLikeDecrementsAndRemovesUser() {
        login();
        doReturn(new Blog().setId(1L)).when(service).getById(1L);
        UpdateChainWrapper<Blog> update = mock(UpdateChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(service).update();
        when(update.update()).thenReturn(true);
        when(sets.score("blog:liked:1", "2")).thenReturn(1000D);
        assertTrue(service.likeBlog(1L).getSuccess());
        verify(update).setSql("liked = liked - 1");
        verify(sets).remove("blog:liked:1", "2");
    }

    @Test
    void failedDatabaseUpdateDoesNotChangeMembership() {
        login();
        doReturn(new Blog().setId(1L)).when(service).getById(1L);
        UpdateChainWrapper<Blog> update = mock(UpdateChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(service).update();
        assertTrue(service.likeBlog(1L).getSuccess());
        verify(sets, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void statusReflectsCurrentUser() {
        Blog blog = new Blog().setId(1L);
        ReflectionTestUtils.invokeMethod(service, "isBlogLiked", blog);
        assertNull(blog.getIsLike());
        login();
        when(sets.score("blog:liked:1", "2")).thenReturn(1000D);
        ReflectionTestUtils.invokeMethod(service, "isBlogLiked", blog);
        assertTrue(blog.getIsLike());
    }

    @Test
    void returnsBlogWithAuthor() {
        ReflectionTestUtils.setField(service, "userService", users);
        Blog blog = new Blog().setId(1L).setUserId(2L).setContent("笔记正文");
        doReturn(blog).when(service).getById(1L);
        User author = new User();
        author.setNickName("作者");
        author.setIcon("avatar.png");
        when(users.getById(2L)).thenReturn(author);
        Result result = service.queryBlogById(1L);
        assertTrue(result.getSuccess());
        assertSame(blog, result.getData());
        assertEquals("作者", blog.getName());
        assertEquals("avatar.png", blog.getIcon());
        assertEquals("笔记正文", blog.getContent());
    }

    @Test
    void missingBlogReturnsFailure() {
        ReflectionTestUtils.setField(service, "userService", users);
        doReturn(null).when(service).getById(1L);
        Result result = service.queryBlogById(1L);
        assertFalse(result.getSuccess());
        assertEquals("笔记不存在！", result.getErrorMsg());
        verifyNoInteractions(users);
    }

    @Test
    void courseQueryExpectsExistingAuthor() {
        ReflectionTestUtils.setField(service, "userService", users);
        Blog blog = new Blog().setId(1L).setUserId(2L);
        doReturn(blog).when(service).getById(1L);
        assertThrows(NullPointerException.class, () -> service.queryBlogById(1L));
    }

    @Test
    void emptyRankingReturnsEmptyList() {
        when(sets.range("blog:liked:1", 0, 4)).thenReturn(Collections.emptySet());
        assertEquals(Collections.emptyList(), service.queryBlogLikes(1L).getData());
        verifyNoInteractions(users);
    }

    @Test
    void rankingPreservesLikeOrderAndReturnsDtos() {
        when(sets.range("blog:liked:1", 0, 4)).thenReturn(new LinkedHashSet<>(Arrays.asList("9", "2")));
        QueryChainWrapper<User> query = mock(QueryChainWrapper.class, RETURNS_SELF);
        when(users.query()).thenReturn(query);
        User first = new User();
        first.setId(9L);
        first.setNickName("first");
        User second = new User();
        second.setId(2L);
        when(query.list()).thenReturn(Arrays.asList(first, second));
        List<UserDTO> result = (List<UserDTO>) service.queryBlogLikes(1L).getData();
        assertEquals(9L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
        assertEquals("first", result.get(0).getNickName());
        verify(query).in("id", Arrays.asList(9L, 2L));
        verify(query).last("ORDER BY FIELD(id,9,2)");
    }
}
