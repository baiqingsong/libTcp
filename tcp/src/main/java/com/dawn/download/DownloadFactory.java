package com.dawn.download;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Message;

import com.dawn.download.broadcast.DownloadBroadcastUtil;
import com.dawn.download.entitis.FileInfo;
import com.dawn.download.service.DownloadService;
import com.dawn.download.service.DownloadTask;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DownloadFactory {
    private  Context mContext;
    //单例模式
    private static DownloadFactory instance = null;

    private DownloadFactory(Context context) {
        mContext = context;
        initDownloadReceiver();
    }

    public static DownloadFactory getInstance(Context context) {
        if (instance == null) {
            synchronized (DownloadFactory.class) {
                if (instance == null) {
                    instance = new DownloadFactory(context);
                }
            }
        }
        return instance;
    }
    private DownloadListener mListener;

    /**
     * 设置回调监听
     */
    public DownloadFactory setListener(DownloadListener listener) {
        this.mListener = listener;
        return instance;
    }

    /**
     * 初始化下载任务
     */
    private void initDownloadReceiver(){
        DownloadReceiver downloadReceiver = new DownloadReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DownloadBroadcastUtil.ACTION_UPDATE);
        intentFilter.addAction(DownloadBroadcastUtil.ACTION_FINISHED);
        intentFilter.addAction(DownloadBroadcastUtil.ACTION_START);
        intentFilter.addAction(DownloadBroadcastUtil.ACTION_ERROR);
        mContext.registerReceiver(downloadReceiver, intentFilter);
    }

    private int failCount;//下载失败次数

    /**
     * 开始下载
     */
    public void download(FileInfo fileInfo){
        Intent intent = new Intent(mContext, DownloadService.class);
        intent.setAction(DownloadBroadcastUtil.ACTION_START);
        intent.putExtra("fileInfo", fileInfo);
        mContext.startService(intent);
    }

    public class DownloadReceiver extends BroadcastReceiver{

        @Override
        public void onReceive(Context context, Intent intent) {
            if(intent == null)
                return;
            FileInfo fileInfo = (FileInfo) intent.getSerializableExtra("fileInfo");
            if(DownloadBroadcastUtil.ACTION_START.equals(intent.getAction())) {//开始下载
                if(mListener != null)
                    mListener.onStart(fileInfo);
            }else if(DownloadBroadcastUtil.ACTION_UPDATE.equals(intent.getAction())) {//更新进度
                int progress = intent.getIntExtra("finished", 0);
                if (mListener != null)
                    mListener.onProgress(fileInfo, progress);
            }else if(DownloadBroadcastUtil.ACTION_FINISHED.equals(intent.getAction())) {//下载完成
                failCount = 0;
                if(mListener != null)
                    mListener.onFinish(fileInfo);
            }else if(DownloadBroadcastUtil.ACTION_STOP.equals(intent.getAction())) {//暂停下载
                if (mListener != null)
                    mListener.onStop(fileInfo);
            }else if(DownloadBroadcastUtil.ACTION_ERROR.equals(intent.getAction())) {//下载失败
                failCount++;
                if(failCount < 3){
                    download(fileInfo);
                }else{
                    failCount = 0;
                    if(mListener != null)
                        mListener.onFail(fileInfo);
                }
            }
        }
    }

    public interface DownloadListener {
        void onStart(FileInfo fileInfo);//开始下载
        void onStop(FileInfo fileInfo);//暂停下载
        void onProgress(FileInfo fileInfo, int progress);//下载进度
        void onFinish(FileInfo fileInfo);//下载完成
        void onFail(FileInfo fileInfo);//下载失败
    }
}
