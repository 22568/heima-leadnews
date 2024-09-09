package com.heima.wemedia.interceptor;

import com.heima.model.wemedia.pojos.WmUser;
import com.heima.utils.thread.WmThreadLocalUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
@Slf4j
public class WmTokenInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String usrId = request.getHeader("userId");
        if (usrId != null) {
            WmUser wmUser=new WmUser();
            wmUser.setId(Integer.parseInt(usrId));
            WmThreadLocalUtils.setWmUser(wmUser);
            log.info("wmTokenFilter设置用户信息到threadlocal中...");
        }
        return true;
    }
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
            log.info("清理threadlocal...");
            WmThreadLocalUtils.removeWmUser();
    }

}
