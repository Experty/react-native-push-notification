package com.dieam.reactnativepushnotification.modules;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

import com.dieam.reactnativepushnotification.BuildConfig;
import com.dieam.reactnativepushnotification.R;
import com.facebook.react.modules.storage.AsyncLocalStorageUtil;
import com.facebook.react.modules.storage.ReactDatabaseSupplier;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.dieam.reactnativepushnotification.helpers.ApplicationBadgeHelper;
import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Random;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.dieam.reactnativepushnotification.modules.RNPushNotification.LOG_TAG;

public class RNPushNotificationListenerService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String from = message.getFrom();
        RemoteMessage.Notification remoteNotification = message.getNotification();

        final Bundle bundle = new Bundle();
        // Putting it from remoteNotification first so it can be overriden if message
        // data has it
        if (remoteNotification != null) {
            // ^ It's null when message is from GCM
            bundle.putString("title", remoteNotification.getTitle());
            bundle.putString("message", remoteNotification.getBody());
        }

        for(Map.Entry<String, String> entry : message.getData().entrySet()) {
            bundle.putString(entry.getKey(), entry.getValue());
        }
        JSONObject data = getPushData(bundle.getString("data"));
        // Copy `twi_body` to `message` to support Twilio
        if (bundle.containsKey("twi_body")) {
            bundle.putString("message", bundle.getString("twi_body"));
        }

        if (data != null) {
            if (!bundle.containsKey("message")) {
                bundle.putString("message", data.optString("alert", null));
            }
            if (!bundle.containsKey("title")) {
                bundle.putString("title", data.optString("title", null));
            }
            if (!bundle.containsKey("sound")) {
                bundle.putString("soundName", data.optString("sound", null));
            }
            if (!bundle.containsKey("color")) {
                bundle.putString("color", data.optString("color", null));
            }

            final int badge = data.optInt("badge", -1);
            if (badge >= 0) {
                ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(this, badge);
            }
        }

        Log.v(LOG_TAG, "onMessageReceived: " + bundle);

        // We need to run this on the main thread, as the React code assumes that is true.
        // Namely, DevServerHelper constructs a Handler() without a Looper, which triggers:
        // "Can't create handler inside thread that has not called Looper.prepare()"
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                // Construct and load our normal React JS code bundle
                ReactInstanceManager mReactInstanceManager = ((ReactApplication) getApplication()).getReactNativeHost().getReactInstanceManager();
                ReactContext context = mReactInstanceManager.getCurrentReactContext();
                // If it's constructed, send a notification
                if (context != null) {
                    handleRemotePushNotification((ReactApplicationContext) context, bundle);
                } else {
                    // Otherwise wait for construction, then send the notification
                    mReactInstanceManager.addReactInstanceEventListener(new ReactInstanceManager.ReactInstanceEventListener() {
                        public void onReactContextInitialized(ReactContext context) {
                            handleRemotePushNotification((ReactApplicationContext) context, bundle);
                        }
                    });
                    if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
                        // Construct it in the background
                        mReactInstanceManager.createReactContextInBackground();
                    }
                }
            }
        });
    }

    private JSONObject getPushData(String dataString) {
        try {
            return new JSONObject(dataString);
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean shouldWakeUp(Bundle b) {
        Boolean shouldWakeUp = false;
        if (b.containsKey("default")) {
            JSONObject json = new JSONObject();
            try {
                json.put("default", b.get("default"));
                JSONObject body = new JSONObject(json.getString("default"));
                Log.d(LOG_TAG, "body  " + body);
                shouldWakeUp = body.has("wakeUp");
            } catch (JSONException e) {
                Log.e(LOG_TAG, e.getMessage());
            }
        }
        return shouldWakeUp;
    }

    private Boolean isCallNotification(Bundle b) {
        Boolean isCallNotification = false;
        if (b.containsKey("default")) {
            JSONObject json = new JSONObject();
            try {
                json.put("default", b.get("default"));
                JSONObject body = new JSONObject(json.getString("default"));

                if (body.has("event")) {
                    String event = body.getString("event");
                    if (event.equals("N_NEW_CALL"))
                        isCallNotification = true;
                }
            } catch (JSONException e) {
                Log.e(LOG_TAG, e.getMessage());
            }
        }
        return isCallNotification;
    }

    private Boolean isGiftCallNotification(Bundle b) {
        Boolean isGiftCall = false;
        if (b.containsKey("default")) {
            JSONObject json = new JSONObject();
            try {
                json.put("default", b.get("default"));
                JSONObject body = new JSONObject(json.getString("default"));

                Log.e(LOG_TAG, "JSONObject " + body.toString());

                if (body.has("data")) {
                    JSONObject bodyData = new JSONObject(body.getString("data"));
                    if (bodyData.has("giftCall")) {
                        Boolean giftCallData = bodyData.getBoolean("giftCall");
                        if (giftCallData)
                            isGiftCall = true;
                    }
                }
            } catch (JSONException e) {
                Log.e(LOG_TAG, e.getMessage());
            }
        }
        return isGiftCall;
    }

    private void handleRemotePushNotification(ReactApplicationContext context, Bundle bundle) {
        // If notification ID is not provided by the user for push notification, generate one at random
        if (bundle.getString("id") == null) {
            Random randomNumberGenerator = new Random(System.currentTimeMillis());
            bundle.putString("id", String.valueOf(randomNumberGenerator.nextInt()));
        }

        Boolean isForeground = isApplicationInForeground();

        RNPushNotificationJsDelivery jsDelivery = new RNPushNotificationJsDelivery(context);

        // NOTE: Customization issue for
        String packageName = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        String className = launchIntent.getComponent().getClassName();
        Class intentClass = null;
        try {
            intentClass = Class.forName(className);
        } catch (ClassNotFoundException e) {
            Log.e(LOG_TAG, e.getMessage());
        }

        if (shouldWakeUp(bundle)) {
            if(!BuildConfig.DEBUG  && isCallNotification(bundle)) {
                SendSeEvent sendSeEvent = new SendSeEvent(bundle, context);
                Thread t = new Thread(sendSeEvent);
                t.start();
            }

            // TODO: 1. open app to foreground
            Intent intent = new Intent();
            Intent IncomingCallService = new Intent();
            intent.setClassName(context, "com.experty.MyTaskService");
            IncomingCallService.setClassName(context, "com.experty.IncomingCallService");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            intent.putExtra("call", bundle);
            IncomingCallService.putExtra("call", bundle);
            try {
                // TODO: 2. send notification to js handlers (see example below)
                if (isForeground) {
                    jsDelivery.notifyNotification(bundle);
                } else {
                    if (isCallNotification(bundle) && isGiftCallNotification(bundle)) {
                        context.startService(IncomingCallService);
                        Boolean appRunning = isAppRunning();
                        Log.e(LOG_TAG, "appRunning" + appRunning);
                        if(appRunning)
                            jsDelivery.notifyNotification(bundle);
                    } else {
                        context.startService(intent);
                        HeadlessJsTaskService.acquireWakeLockNow(context);
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, e.getMessage());
            }
            return;
        }

        bundle.putBoolean("foreground", isForeground);
        bundle.putBoolean("userInteraction", false);
        jsDelivery.notifyNotification(bundle);

        // If contentAvailable is set to true, then send out a remote fetch event
        if (bundle.getString("contentAvailable", "false").equalsIgnoreCase("true")) {
            jsDelivery.notifyRemoteFetch(bundle);
        }

        Log.v(LOG_TAG, "sendNotification: " + bundle);

        Application applicationContext = (Application) context.getApplicationContext();
        RNPushNotificationHelper pushNotificationHelper = new RNPushNotificationHelper(applicationContext);
        pushNotificationHelper.sendToNotificationCentre(bundle);
    }

    private boolean isApplicationInForeground() {
        ActivityManager activityManager = (ActivityManager) this.getSystemService(ACTIVITY_SERVICE);
        List<RunningAppProcessInfo> processInfos = activityManager.getRunningAppProcesses();
        if (processInfos != null) {
            for (RunningAppProcessInfo processInfo : processInfos) {
                if (processInfo.processName.equals(getApplication().getPackageName())) {
                    if (processInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        for (String d : processInfo.pkgList) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isAppRunning() {
        final ActivityManager activityManager = (ActivityManager) this.getSystemService(ACTIVITY_SERVICE);
        final List<ActivityManager.RunningAppProcessInfo> procInfos = activityManager.getRunningAppProcesses();
        if (procInfos != null)
        {
            for (final ActivityManager.RunningAppProcessInfo processInfo : procInfos) {
                if (processInfo.processName.equals("com.experty")) {
                    return true;
                }
            }
        }
        return false;
    }
}

class SendSeEvent implements Runnable {
    Bundle b;
    Context context;

    public SendSeEvent(Bundle b, Context context) {
        this.b = b;
        this.context = context;
    }

    public void run() {
        try {
            AsyncStorage as = new AsyncStorage((ReactApplicationContext) context);
            Bundle asBundle = as.getBundle();
            if (asBundle.getString("host") == null) throw new Exception("Can't get AsyncStorage");
            String host = asBundle.getString("host") + "/api/es?data=";
            String dataParam = getDataParam(b, asBundle.getString("id"), asBundle.getString("sid"));
            String url = host + dataParam;

            Request request = new Request.Builder()
                    .url(url)
                    .build();

            OkHttpClient client = new OkHttpClient();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(LOG_TAG, "onFailure: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {

                    Log.e(LOG_TAG, "onResponse: " + response.body().toString());
                }
            });
        } catch (Exception e) {
            Log.e(LOG_TAG, "" + e.getMessage());
        }
    }

    private String getDataParam(Bundle b, String id, String sid) throws JSONException, UnsupportedEncodingException {
        JSONObject mainDataObj = new JSONObject();
        mainDataObj.put("mobile", "android");
        mainDataObj.put("platform", "android");
        mainDataObj.put("timestamp", new Date().getTime());
        if (b.containsKey("default")) {
            JSONObject json = new JSONObject();
            try {
                json.put("default", b.get("default"));
                JSONObject body = new JSONObject(json.getString("default"));

                if (body.has("data")) {
                    JSONObject data = body.getJSONObject("data");
                    if (data.has("giftCall")) {
                        if(data.getBoolean("giftCall")) {
                            mainDataObj.put("callType", "quick-call-line");
                        } else {
                            mainDataObj.put("callType", "contact-call");
                        }
                    } else {
                        mainDataObj.put("callType", "contact-call");
                    }
                    if (id != null) {
                        mainDataObj.put("id", id);
                    }

                    if (data.has("callItemId")) {
                        mainDataObj.put("callId", data.getString("callItemId"));
                    }

                }
            } catch (JSONException e) {
                Log.e(LOG_TAG, e.getMessage());
            }
        }

        JSONObject mainObj = new JSONObject();
        mainObj.put("args", mainDataObj);
        mainObj.put("type", "CALL_NOTIFICATION_RECEIVED");
        if (sid == null) {
            mainObj.put("sid", "NATIVE_ES");
        } else {
            mainObj.put("sid", sid);
        }


        String temp = mainObj.toString();
        byte[] data = temp.getBytes("UTF-8");
        return Base64.encodeToString(data, Base64.DEFAULT);
    }
}

class AsyncStorage {

    public ReactApplicationContext context;
    final Bundle bundle = new Bundle();

    Cursor catalystLocalStorage = null;
    SQLiteDatabase readableDatabase = null;

    public AsyncStorage (ReactApplicationContext context) {
        this.context = context;
        this.fetch();
    }

    public void fetch() {
        try {
            readableDatabase = ReactDatabaseSupplier.getInstance(context).getReadableDatabase();
            catalystLocalStorage = readableDatabase.query("catalystLocalStorage", new String[]{"key", "value"}, null, null, null, null, null);
            if (readableDatabase != null) {
                String host = AsyncLocalStorageUtil.getItemImpl(readableDatabase, "HOST");
                bundle.putString("host", host);
                String sid = AsyncLocalStorageUtil.getItemImpl(readableDatabase, "e-sid");
                bundle.putString("sid", sid);
            }
            if (catalystLocalStorage.moveToFirst()) {
                do {
                    try {
                        // one row with all AsyncStorage: { "user": { ... }, ... }
                        String json = catalystLocalStorage.getString(catalystLocalStorage.getColumnIndex("value"));
                        JSONObject obj = new JSONObject(json);
                        Log.d(LOG_TAG, obj.toString());

                        String user = obj.getString("userData");
                        JSONObject userData = new JSONObject(user);
                        String id = userData.getString("id");

                        bundle.putString("id", id);
                    } catch(Exception e) {
                        Log.e(LOG_TAG, e.toString());
                    }
                } while(catalystLocalStorage.moveToNext());
            }
        } finally {
            if (catalystLocalStorage != null) {
                catalystLocalStorage.close();
            }
            if (readableDatabase != null) {
                readableDatabase.close();
            }
        }
    }

    public Bundle getBundle() {
        return bundle;
    }
}
