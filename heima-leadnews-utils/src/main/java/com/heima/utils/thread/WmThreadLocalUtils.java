package com.heima.utils.thread;

import com.heima.model.wemedia.pojos.WmUser;

public class WmThreadLocalUtils {
    private  static  final ThreadLocal<WmUser> WM_USER_THREAD_LOCAL=new ThreadLocal<>();
    public static WmUser getWmUser(){
        return WM_USER_THREAD_LOCAL.get();
    }
    public static void setWmUser(WmUser wmUser){
        WM_USER_THREAD_LOCAL.set(wmUser);
    }
    public static void removeWmUser(){
        WM_USER_THREAD_LOCAL.remove();
    }
}
