package com.hmdp.utils;

import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@AllArgsConstructor
//生成全局唯一ID
public class RedisWorker {

    private final StringRedisTemplate stringRedisTemplate;
    private static final long BEGIN_TIMESTAMP = 1577836800L;
    private static final int COUNT_BITS = 32;
    public long nextId(String keyPrefix){
        //1. 生成时间错
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2. 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        return timestamp <<COUNT_BITS | count;

    }

//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
//        long second = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(second);
//    }
}
