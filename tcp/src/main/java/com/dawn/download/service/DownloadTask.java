package com.dawn.download.service;


import android.content.Context;
import android.content.Intent;

import com.dawn.download.broadcast.DownloadBroadcastUtil;
import com.dawn.download.db.ThreadDAO;
import com.dawn.download.db.ThreadDAOImple;
import com.dawn.download.entitis.FileInfo;
import com.dawn.download.entitis.ThreadInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadTask {
    private Context mContext = null;
    private FileInfo mFileInfo = null;
    private ThreadDAO mDao = null;
    private int mFinished = 0;
    private int mThreadCount = 1;
    public boolean mIsPause = false;
    private List<DownloadThread> mThreadlist = null;
    public static ExecutorService sExecutorService = Executors.newCachedThreadPool();

    public DownloadTask(Context comtext, FileInfo fileInfo, int threadCount) {
        super();
        this.mThreadCount = threadCount;
        this.mContext = comtext;
        this.mFileInfo = fileInfo;
        this.mDao = new ThreadDAOImple(mContext);
    }

    public void download() {
        // 从数据库中获取下载的信息
        List<ThreadInfo> list = mDao.queryThreads(mFileInfo.getUrl());
        int pre_size = list.size();
        if (pre_size == 0) {
            int length = mFileInfo.getLength();
            int block = length / mThreadCount;
            for (int i = 0; i < mThreadCount; i++) {
                // 划分每个线程开始下载和结束下载的位置
                int start = i * block;
                int end = (i + 1) * block - 1;
                if (i == mThreadCount - 1) {
                    end = length - 1;
                }
                ThreadInfo threadInfo = new ThreadInfo(i, mFileInfo.getUrl(), start, end, 0);
                list.add(threadInfo);
            }
        }
        mThreadlist = new ArrayList<>();
        for (ThreadInfo info : list) {
            DownloadThread thread = new DownloadThread(info);
//			thread.start();
            // 使用线程池执行下载任务
            DownloadTask.sExecutorService.execute(thread);
            mThreadlist.add(thread);
            // 如果數據庫不存在下載信息，添加下載信息
            if(pre_size == 0)//添加到数据库
                mDao.insertThread(info);
        }
    }

    public synchronized void checkAllFinished() {
        boolean allFinished = true;
        for (DownloadThread thread : mThreadlist) {
            if (!thread.isFinished) {
                allFinished = false;
                break;
            }
        }
        if (allFinished) {
            // 下載完成后，刪除數據庫信息
            mDao.deleteThread(mFileInfo.getUrl());
            // 通知UI哪个线程完成下载
            DownloadBroadcastUtil.sendDownloadFinish(mContext, mFileInfo);

        }
    }

    class DownloadThread extends Thread {
        private ThreadInfo threadInfo = null;
        // 标识线程是否执行完毕
        public boolean isFinished = false;
        public DownloadThread(ThreadInfo threadInfo) {
            this.threadInfo = threadInfo;
        }

        @Override
        public void run() {
            HttpURLConnection conn = null;
            RandomAccessFile raf = null;
            InputStream is = null;
            try {
                URL url = new URL(mFileInfo.getUrl());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10 * 1000);
                conn.setRequestMethod("GET");
                int start = threadInfo.getStart() + threadInfo.getFinished();//开始位置+完成了的进度
                // 設置下載文件開始到結束的位置
                conn.setRequestProperty("Range", "bytes=" + start + "-" + threadInfo.getEnd());
                File dir = new File(mFileInfo.getDir());
                if (!dir.exists())
                    dir.mkdir();

                File file = new File(dir, mFileInfo.getFileName());
                raf = new RandomAccessFile(file, "rwd");
                raf.seek(start);
                mFinished += threadInfo.getFinished();
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    is = conn.getInputStream();
                    byte[] bt = new byte[1024];
                    int len = -1;
                    // 定义UI刷新时间
                    long time = System.currentTimeMillis();
                    while ((len = is.read(bt)) != -1) {
                        raf.write(bt, 0, len);
                        // 累计整个文件完成进度
                        mFinished += len;
                        // 累加每个线程完成的进度
                        threadInfo.setFinished(threadInfo.getFinished() + len);
                        // 設置爲500毫米更新一次
                        if (System.currentTimeMillis() - time > 1000) {
                            time = System.currentTimeMillis();
                            // 发送已完成多少
                            DownloadBroadcastUtil.sendDownloadUpdate(mContext, mFileInfo, mFinished / (mFileInfo.getLength()/100));

                        }
                        if (mIsPause) {//如果停止下载，保存下载进度
                            mDao.updateThread(threadInfo.getUrl(), threadInfo.getId(), threadInfo.getFinished());
                            return;
                        }
                    }
                }
                // 标识线程是否执行完毕
                isFinished = true;
                // 判断是否所有线程都执行完毕
                checkAllFinished();

            } catch (Exception e) {
                e.printStackTrace();
                //下载失败，保存下载进度
                mDao.updateThread(threadInfo.getUrl(), threadInfo.getId(), threadInfo.getFinished());
                //发送下载失败广播
                DownloadBroadcastUtil.sendDownloadError(mContext, mFileInfo);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
                try {
                    if (is != null) {
                        is.close();
                    }
                    if (raf != null) {
                        raf.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            super.run();
        }
    }

}

