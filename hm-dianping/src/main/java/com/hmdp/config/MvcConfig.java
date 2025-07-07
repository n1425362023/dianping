package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class MvcConfig implements WebMvcConfigurer{
    private final StringRedisTemplate stringRedisTemplate;
    //配置拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //刷新token拦截器
        registry.addInterceptor(new RefreshTokenInterceptor((stringRedisTemplate))).addPathPatterns("/**").order(0);
        //登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/shop/**",
                        "/shop-type/**",
                        "/blog/hot",
                        "/blog/search",
                        "/voucher/**"
                ).order(1);
    }

}
