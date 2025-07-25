package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import lombok.AllArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        System.out.println(list);
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
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
