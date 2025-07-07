package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    private TransactionTemplate transactionTemplate;
    private ISeckillVoucherService seckillVoucherService;
    private RedisWorker redisWorker;
    @Override

    public Result seckillVoucher(Long voucherId) {
        // 根据voucherId获取秒杀券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀活动是否已经开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀活动尚未开始");
        }
        // 判断秒杀活动是否已经结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀活动已经结束");
        }
        // 判断库存是否足够
        if(voucher.getStock() < 1){
            return Result.fail("库存不足");
        }
        // 使用用户id作为锁的key，保证同一用户不能重复抢购
        synchronized (UserHolder.getUser().getId().toString().intern()){
            return transactionTemplate.execute(status -> {

                // 查询该用户是否已经抢购过该秒杀券
                long count = lambdaQuery().eq(VoucherOrder::getUserId, UserHolder.getUser().getId()).count();
                if (count > 0) {
                    return Result.fail("不能重复抢购");
                }

                // 更新秒杀券库存
                boolean success = seckillVoucherService.lambdaUpdate().
                        setSql("stock = stock - 1").set(SeckillVoucher::getVoucherId, voucherId)
                        .gt(SeckillVoucher::getStock, 0).update();
                if (!success) {
                    return Result.ok("秒杀失败");
                }

                // 创建订单
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(redisWorker.nextId("order"))
                        .setUserId(UserHolder.getUser().getId())
                        .setVoucherId(voucherId);
                save(voucherOrder);
                return Result.ok("秒杀成功");
            });
        }
    }
}
