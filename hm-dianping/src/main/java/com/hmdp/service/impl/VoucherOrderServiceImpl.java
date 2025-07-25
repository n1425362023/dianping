package com.hmdp.service.impl;

import com.alibaba.fastjson.JSON;
import com.google.common.util.concurrent.RateLimiter;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.redismq.MQSender;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimiter rateLimiter = RateLimiter.create(10);
    private final ISeckillVoucherService seckillVoucherService;
    private final MQSender mqSender;
    private final RedissonClient redissonClient;
    private final RedisWorker redisWorker;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private final ExecutorService SECKILL_ORDER_EXECUTOR=Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try{
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                }catch (Exception e){
                    log.error("处理订单异常",e);
                }
            }
        }
    }
    @SneakyThrows
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean islock = lock.tryLock(1,5, TimeUnit.SECONDS);
        if(!islock){
            log.error("不允许重复下单");
        }

        try {
            // 更新秒杀券库存
            boolean success = seckillVoucherService.lambdaUpdate().
                    setSql("stock = stock - 1").set(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                    .gt(SeckillVoucher::getStock, 0).update();
            if (!success) {
                log.error("库存不足");
                return;
            }
            save(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    public Result seckillVoucher(Long voucherId) {
        //令牌桶算法 限流
        if (!rateLimiter.tryAcquire(1000, TimeUnit.MILLISECONDS)){
            return Result.fail("目前网络正忙，请重试");
        }
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisWorker.nextId("order");
        Long execute =  stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString());
        if (Objects.requireNonNull(execute).intValue() != 0) {
            return Result.fail(execute == 1 ? "库存不足" : "不能重复下单");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId);
        mqSender.sendSeckillMessage(JSON.toJSONString(voucherOrder));
        //orderTasks.add(voucherOrder);
        return Result.ok(orderId);
    }
}
