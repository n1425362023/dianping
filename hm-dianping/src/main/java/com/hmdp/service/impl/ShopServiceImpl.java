package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@AllArgsConstructor
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
//        Map<Object, Object> cacheShopMap = stringRedisTemplate.opsForHash().entries(RedisConstants.CACHE_SHOP_KEY + id);
//
//        // 1. 查询缓存
//        if (!cacheShopMap.isEmpty()) {
//            if (cacheShopMap.containsKey(RedisConstants.CACHE_NULL_FIELD)) {
//                return Result.fail("店铺不存在");
//            }
//            return Result.ok(BeanUtil.fillBeanWithMap(cacheShopMap, Shop.class, false));
//        }
//
//        //2.缓存不存在，查询数据库
//        Shop shop = getById(id);
//        //3.数据库不存在，返回错误
//        if(shop == null) {
//            stringRedisTemplate.opsForHash().put(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_NULL_FIELD, "");
//            stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("店铺不存在");
//        }
//        //4.数据库存在，写入缓存
//        Map<String,Object> shopMap = BeanUtil.beanToMap(shop,new HashMap<>(),
//                new CopyOptions().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString()));
//        stringRedisTemplate.opsForHash().putAll(RedisConstants.CACHE_SHOP_KEY + id, shopMap);
//        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);


        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop.getId()==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    public Shop queryWithMutex(Long id)  {
        Map<Object, Object> cacheShopMap = stringRedisTemplate.opsForHash().entries(RedisConstants.CACHE_SHOP_KEY + id);
        // 1. 查询缓存
        if (!cacheShopMap.isEmpty()) {
            if (cacheShopMap.containsKey(RedisConstants.CACHE_NULL_FIELD)) {
                return null;
            }
            return BeanUtil.fillBeanWithMap(cacheShopMap, new Shop(), false);
        }
        // 2.实现缓存重构
        //2.1 获取互斥锁
        Shop shop = null;
        try {
            boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            // 2.2 判断否获取成功
            if(!isLock){
                //2.3 失败，则休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            cacheShopMap = stringRedisTemplate.opsForHash().entries(RedisConstants.CACHE_SHOP_KEY + id);

            // 再次查询缓存
            if (!cacheShopMap.isEmpty()) {
                if (cacheShopMap.containsKey(RedisConstants.CACHE_NULL_FIELD)) {
                    return null;
                }
                return BeanUtil.fillBeanWithMap(cacheShopMap, new Shop(), false);
            }
            //2.4 成功，根据id查询数据库
            shop = getById(id);
            //2.5.不存在，返回错误
            if(shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForHash().put(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_NULL_FIELD, "");
                stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //2.6写入redis
            Map<String,Object> shopMap = BeanUtil.beanToMap(shop,new HashMap<>(),
                    new CopyOptions().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString()));
            stringRedisTemplate.opsForHash().putAll(RedisConstants.CACHE_SHOP_KEY + id, shopMap);
            stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        }catch (Exception e){
            throw new RuntimeException(e);
        }
        finally {
            //2.7.释放互斥锁
            unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        return shop;
    }
}
