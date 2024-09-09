package com.heima.schedule.service;

import com.heima.model.schedule.dtos.Task;

import java.util.Date;

/**
 * 对外访问接口
 */
public interface TaskService {

    /**
     * 添加任务
     * @param task   任务对象
     * @return       任务id
     */
    public long addTask(Task task) ;

    boolean addTaskToDb(Task task);
    public boolean cancelTask(long taskId);

    public Task updateDb(long taskId, int executed);
    public Task poll(int type,int priority);

}