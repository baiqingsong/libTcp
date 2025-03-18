package com.dawn.download.service;

import static com.dawn.download.broadcast.DownloadBroadcastUtil.ACTION_START;
import static com.dawn.download.broadcast.DownloadBroadcastUtil.ACTION_STOP;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.dawn.download.broadcast.DownloadBroadcastUtil;
import com.dawn.download.entitis.FileInfo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下載服務類，用於執行下載任務，并且將下載進度傳遞到Activity中
 *
 *    private void startDownload(FileInfo fileInfo){
 *         Intent intent = new Intent(MainActivity.this, DownloadService.class);
 *         intent.setAction(DownloadService.ACTION_START);
 *         intent.putExtra("fileInfo", fileInfo);
 *         startService(intent);
 *     }
 *
 *     private void initDownloadReceiver(){
 *         mDownloadReceiver = new DownloadReceiver();
 *         IntentFilter intentFilter = new IntentFilter();
 *         intentFilter.addAction(DownloadService.ACTION_UPDATE);
 *         intentFilter.addAction(DownloadService.ACTION_FINISHED);
 *         intentFilter.addAction(DownloadService.ACTION_START);
 *         intentFilter.addAction(DownloadService.ACTION_ERROR);
 *         registerReceiver(mDownloadReceiver, intentFilter);
 *     }
 */
public class DownloadService extends Service {


    public static final int MSG_INIT = 0;
    public static final int MSG_ERROR = 1;//错误

    private Map<Integer, DownloadTask> mTasks = new LinkedHashMap<>();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null){
            // 获得Activity传来的参数
            if (ACTION_START.equals(intent.getAction())) {//下载开始
                FileInfo fileInfo = (FileInfo) intent.getSerializableExtra("fileInfo");
                InitThread initThread = new InitThread(fileInfo);
                DownloadTask.sExecutorService.execute(initThread);
            } else if (ACTION_STOP.equals(intent.getAction())) {//下载停止
                FileInfo fileInfo = (FileInfo) intent.getSerializableExtra("fileInfo");
                DownloadTask task = mTasks.get(fileInfo.getId());
                if (task != null) {// 停止下载任务
                    task.mIsPause = true;
                }
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    // 從InitThread綫程中獲取FileInfo信息，然後開始下載任務
    Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INIT:
                    FileInfo fileInfo = (FileInfo) msg.obj;
                    // 獲取FileInfo對象，開始下載任務
                    DownloadTask task = new DownloadTask(DownloadService.this, fileInfo, 3);
                    task.download();
                    // 把下载任务添加到集合中
                    mTasks.put(fileInfo.getId(), task);
                    // 发送启动下载的通知
                    DownloadBroadcastUtil.sendDownloadStart(DownloadService.this, fileInfo);
                    break;
                case MSG_ERROR:
                    fileInfo = (FileInfo) msg.obj;
                    DownloadBroadcastUtil.sendDownloadError(DownloadService.this, fileInfo);
                    break;
            }
        };
    };

    /**
     * 初始化线程，获取文件相关信息，文件长度
     */
    class InitThread extends Thread {
        private FileInfo mFileInfo = null;
        public InitThread(FileInfo mFileInfo) {
            super();
            this.mFileInfo = mFileInfo;
        }
        @Override
        public void run() {
            HttpURLConnection conn = null;
            RandomAccessFile raf = null;
            try {
                URL url = new URL(mFileInfo.getUrl());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5 * 1000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                int length = -1;
                if (code == HttpURLConnection.HTTP_OK)
                    length = conn.getContentLength();
                //如果文件长度为小于0，表示获取文件失败，直接返回
                if (length <= 0) {
                    Message msg = Message.obtain();
                    msg.obj = mFileInfo;
                    msg.what = MSG_ERROR;
                    mHandler.sendMessage(msg);
                    return;
                }
                // 創建本地文件夾
                File dir = new File(mFileInfo.getDir());
                if (!dir.exists())
                    dir.mkdir();

                // 創建本地文件
                File file = new File(dir, mFileInfo.getFileName());
                raf = new RandomAccessFile(file, "rwd");
                raf.setLength(length);
                // 設置文件長度
                mFileInfo.setLength(length);
                // 將FileInfo對象傳遞給Handler
                Message msg = Message.obtain();
                msg.obj = mFileInfo;
                msg.what = MSG_INIT;
                mHandler.sendMessage(msg);
//				msg.setTarget(mHandler);
            } catch (Exception e) {
                e.printStackTrace();
                Message msg = Message.obtain();
                msg.obj = mFileInfo;
                msg.what = MSG_ERROR;
                mHandler.sendMessage(msg);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
                try {
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
