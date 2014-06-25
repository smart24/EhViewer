/*
 * Copyright (C) 2014 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer;

import java.util.Stack;

import com.hippo.ehviewer.cache.ImageCache;
import com.hippo.ehviewer.network.HttpHelper;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;

public class ImageLoader {
    private static final String TAG = ImageLoader.class.getSimpleName();
    
    private static ImageLoader sInstance;
    private static Handler mHandler;
    
    private class LoadTask {
        public String url;
        public String key;
        public OnGetImageListener listener;
        public Bitmap bitmap;
        
        public LoadTask(String url, String key, OnGetImageListener listener) {
            this.url = url;
            this.key = key;
            this.listener = listener;
        }
    }
    
    private Context mContext;
    private final Stack<LoadTask> mLoadTasks;
    private ImageCache mImageCache;
    private ImageDownloader mImageDownloader;
    private Object mLock;
    
    
    public static final void createHandler() {
        if (mHandler != null)
            return;
        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                LoadTask task = (LoadTask)msg.obj;
                task.listener.onGetImage(task.key, task.bitmap);
            }
        };
    }
    
    public ImageLoader(Context context) {
        mLoadTasks = new Stack<LoadTask>();
        mImageDownloader = new ImageDownloader();
        
        mContext = context;
        mImageCache = ImageCache.getInstance(mContext);
        
        mLock = new Object();
        new Thread(new LoadFromCacheTask()).start();
    }
    
    public final static ImageLoader getInstance(final Context context) {
        if (sInstance == null) {
            sInstance = new ImageLoader(context.getApplicationContext());
        }
        return sInstance;
    }
    
    public void add(String url, String key, OnGetImageListener listener) {
        synchronized (mLock) {
            mLoadTasks.push(new LoadTask(url, key, listener));
            mLock.notify();
        }
    }
    
    private class LoadFromCacheTask implements Runnable {
        @Override
        public void run() {
            LoadTask loadTask;
            while (true) {
                synchronized (mLock) {
                    if (mLoadTasks.isEmpty()) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException e) {}
                        continue;
                    }
                    loadTask = mLoadTasks.pop();
                }
                
                String key = loadTask.key;
                loadTask.bitmap = mImageCache.getCachedBitmap(key);
                
                if (loadTask.bitmap != null) {
                    Message msg = new Message();
                    msg.obj = loadTask;
                    mHandler.sendMessage(msg);
                } else {
                    mImageDownloader.add(loadTask);
                }
            }
        }
    }
    
    private class ImageDownloader {
        private static final int MAX_DOWNLOAD_THREADS = 3;
        private final Stack<LoadTask> mDownloadTasks;
        private int mWorkingThreadNum = 0;
        private Object mLock;
        
        public ImageDownloader() {
            mLock = new Object();
            mDownloadTasks = new Stack<LoadTask>();
        }
        
        public void add(LoadTask loadTask) {
            synchronized (mLock) {
                mDownloadTasks.push(loadTask);
                if (mWorkingThreadNum < MAX_DOWNLOAD_THREADS) {
                    new Thread(new DownloadImageTask()).start();
                    mWorkingThreadNum++;
                }
            }
        }
        
        private class DownloadImageTask implements Runnable {
            @Override
            public void run() {
                LoadTask loadTask;
                HttpHelper httpHelper = new HttpHelper(mContext);
                while (true) {
                    synchronized (mLock) {
                        if (mDownloadTasks.isEmpty()) {
                            loadTask = null;
                            mWorkingThreadNum--;
                            break;
                        }
                        loadTask = mDownloadTasks.pop();
                    }
                    
                    loadTask.bitmap = httpHelper.getImage(loadTask.url);
                    mImageCache.addBitmapToCache(loadTask.key, loadTask.bitmap);
                    
                    Message msg = new Message();
                    msg.obj = loadTask;
                    mHandler.sendMessage(msg);
                }
            }
        }
    }
    
    public interface OnGetImageListener {
        /**
         * bmp is null for fail
         * @param key
         * @param bmp
         */
        void onGetImage(String key, Bitmap bmp);
    }
}