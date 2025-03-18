package com.dawn.download.db;

import com.dawn.download.entitis.ThreadInfo;
import java.util.List;

/**
 * 數據庫操作的接口類
 *
 */
public interface ThreadDAO {
    // 插入綫程
    void insertThread(ThreadInfo info);
    // 刪除綫程
    void deleteThread(String url);
    // 更新綫程
    void updateThread(String url, int thread_id, int finished);
    // 查詢綫程
    List<ThreadInfo> queryThreads(String url);
    // 判斷綫程是否存在
    boolean isExists(String url, int threadId);

    void deleteAllThread();//删除所有线程
}
